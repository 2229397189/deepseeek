"""简历中心 agent：RESUME_PARSE（结构化解析）与 RESUME_QUESTION（润色 / 生成）。"""

from __future__ import annotations

import re
import time

from ..core.errors import PayloadInvalid
from ..core.extract import decode_base64, extract_text
from ..core.prompts import PROMPT_VERSION, RESUME_POLISH_PROMPT, SYSTEM_BASE
from ..llm.client import LlmMessage, task_marker
from ..schemas import AgentOutcome, BizType, PipelineStep, Usage
from .registry import AgentContext, StepRecorder
from .skills import extract_skills, normalize_text_list

_EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.]+")
_PHONE = re.compile(r"1[3-9]\d{9}")
_YEARS = re.compile(r"(\d+)\s*年")
_DEGREE = re.compile(r"(博士|硕士|研究生|本科|大专)")
_QUANTIFIED = re.compile(r"\d+\s*(%|％|ms|毫秒|万|亿|qps|tps|倍)")

_SECTION_KEYS = {
    "experience": ("工作经历", "实习经历", "工作经验"),
    "project": ("项目经历", "项目经验", "项目"),
    "education": ("教育经历", "教育背景", "学历"),
}


async def handle(ctx: AgentContext) -> AgentOutcome:
    if ctx.request.biz_type is BizType.RESUME_PARSE:
        if str(ctx.payload.get("mode") or "").upper() == "JD_PARSE":
            return await _jd_extract(ctx)
        return await _parse(ctx)
    return await _polish(ctx)


# ---------------------------------------------------------------------------
# RESUME_PARSE(JD_PARSE)：JD 文件只做文本抽取与要求识别
# ---------------------------------------------------------------------------

async def _jd_extract(ctx: AgentContext) -> AgentOutcome:
    """JD 抽取与简历解析共用同一套文件解码能力，但**不套用简历画像口径**。

    把 JD 硬套简历结构（项目 / 学历 / 工作年限）会产出"该 JD 有 3 年经验"这类无意义字段，
    因此这里只做两件事：抽出全文、识别硬性要求与技能词。
    """
    text, source_kind = _resolve_source_text(ctx)
    if not text.strip():
        raise PayloadInvalid("JD 内容为空，无法抽取")

    recorder = StepRecorder(ctx.emit)
    started = await recorder.start("extract_text")
    await recorder.done("extract_text", f"来源 {source_kind}，{len(text)} 字符", started)

    started = await recorder.start("extract_requirements")
    hard_requirements = _jd_hard_requirements(text)
    skills = extract_skills(text)
    await recorder.done("extract_requirements",
                        f"硬性要求 {len(hard_requirements)} 条，技能 {len(skills)} 项", started)

    output = {
        "sourceKind": source_kind,
        "text": text,
        "charCount": len(text),
        "hardRequirements": hard_requirements,
        "skills": skills,
        "steps": [step.model_dump() for step in recorder.steps],
    }
    return AgentOutcome(output=output, steps=recorder.steps, usage=Usage(), model=ctx.llm.name,
                        prompt_version=PROMPT_VERSION, mock=ctx.llm.name == "deterministic-mock")


_HARD_HINT = re.compile(r"(必须|要求|至少|熟悉|掌握|精通|具备|经验|以上|优先|负责)")


def _jd_hard_requirements(text: str, limit: int = 12) -> list[str]:
    """按行挑出"门槛句"：JD 的价值集中在带要求/优先字样的那几行。"""
    lines = (re.sub(r"^[\s\-•·*>\d.、)）]+", "", line).strip() for line in text.splitlines())
    picked = [line for line in lines if line and 4 <= len(line) <= 120 and _HARD_HINT.search(line)]
    return list(dict.fromkeys(picked))[:limit]


# ---------------------------------------------------------------------------
# RESUME_PARSE
# ---------------------------------------------------------------------------

async def _parse(ctx: AgentContext) -> AgentOutcome:
    resume_text, source_kind = _resolve_source_text(ctx)

    recorder = StepRecorder(ctx.emit)
    started = await recorder.start("extract_basic")

    contact = {
        "name": _guess_name(resume_text),
        "email": _first(_EMAIL.findall(resume_text)),
        "phone": _first(_PHONE.findall(resume_text)),
        "degree": _first(_DEGREE.findall(resume_text)),
    }
    years = _YEARS.findall(resume_text)
    experience_years = max((int(y) for y in years), default=None)
    await recorder.done("extract_basic", f"识别到 {contact['name'] or '未知'}，"
                                        f"学历 {contact['degree'] or '未识别'}，"
                                        f"年限 {experience_years if experience_years is not None else '未识别'}",
                        started)

    started = await recorder.start("extract_blocks")
    blocks = _split_sections(resume_text)
    await recorder.done("extract_blocks",
                        f"经历 {len(blocks['experience'])} 段、项目 {len(blocks['project'])} 段",
                        started)

    started = await recorder.start("extract_skills")
    skills = extract_skills(resume_text)
    await recorder.done("extract_skills", f"命中技能 {len(skills)} 项：{'、'.join(skills[:8])}", started)

    started = await recorder.start("infer_profile")
    weak_points, notes = _infer_weak_points(resume_text, blocks, skills, experience_years)
    await recorder.done("infer_profile", f"推断薄弱点 {len(weak_points)} 项", started)

    output = {
        "sourceKind": source_kind,
        "contact": contact,
        "experienceYears": experience_years,
        "skills": skills,
        "experiences": blocks["experience"],
        "projects": blocks["project"],
        "education": blocks["education"],
        "summary": _summary(contact, experience_years, skills),
        "weakPoints": weak_points,
        "notes": notes,
        "completeness": _completeness(contact, blocks, skills, resume_text),
        "steps": [step.model_dump() for step in recorder.steps],
    }
    return AgentOutcome(output=output, steps=recorder.steps, usage=Usage(), model=ctx.llm.name,
                        prompt_version=PROMPT_VERSION, mock=ctx.llm.name == "deterministic-mock")


# ---------------------------------------------------------------------------
# RESUME_QUESTION：选中文本润色 / 按岗位定制生成
# ---------------------------------------------------------------------------

async def _polish(ctx: AgentContext) -> AgentOutcome:
    mode = str(ctx.payload.get("mode") or "POLISH").upper()
    selected = str(ctx.payload.get("selectedText") or "").strip()
    resume_text = str(ctx.payload.get("resumeText") or "").strip()
    instruction = str(ctx.payload.get("instruction") or "").strip()
    if mode == "POLISH" and not selected:
        raise PayloadInvalid("RESUME_QUESTION(POLISH) 需要 payload.selectedText")
    if mode != "POLISH" and not (resume_text or selected):
        raise PayloadInvalid("RESUME_QUESTION 需要 payload.resumeText 或 payload.selectedText")

    source = selected or resume_text
    target = str(ctx.payload.get("jobTitle") or ctx.payload.get("targetRole") or "目标岗位")
    jd_text = str(ctx.payload.get("jdText") or "")

    recorder = StepRecorder(ctx.emit)
    started = await recorder.start("analyze_source")
    hit_skills = extract_skills(source)
    quantified = len(_QUANTIFIED.findall(source))
    await recorder.done("analyze_source",
                        f"原文 {len(source)} 字，命中技能 {len(hit_skills)} 项，量化数据 {quantified} 处",
                        started)

    started = await recorder.start("generate_suggestion")
    messages = [
        LlmMessage(role="system", content=RESUME_POLISH_PROMPT.format(system=SYSTEM_BASE)),
        LlmMessage(role="user", content=(
            f"{task_marker('RESUME_POLISH')}\n"
            f"模式：{mode}\n目标岗位：{target}\n岗位要求：{jd_text[:800]}\n"
            f"用户指令：{instruction or '（无）'}\n"
            f"待处理文本：\n{source[:3000]}"
        )),
    ]
    result = await ctx.llm.chat(messages)
    await recorder.done("generate_suggestion", "已生成建议文本", started)

    started = await recorder.start("build_diff")
    reasons = _polish_reasons(hit_skills, quantified, jd_text, source)
    await recorder.done("build_diff", f"给出 {len(reasons)} 条改动理由", started)

    output = {
        "mode": mode,
        "target": target,
        "polished": result.text,
        "original": source,
        "reasons": reasons,
        "matchedSkills": hit_skills,
        "steps": [step.model_dump() for step in recorder.steps],
        "applyMode": "REPLACE_SELECTION" if mode == "POLISH" else "APPEND",
    }
    return AgentOutcome(output=output, steps=recorder.steps,
                        usage=Usage(prompt_tokens=result.prompt_tokens, output_tokens=result.output_tokens),
                        model=result.model, prompt_version=PROMPT_VERSION,
                        mock=result.model == "deterministic-mock")


# ---------------------------------------------------------------------------
# 内部工具
# ---------------------------------------------------------------------------

def _resolve_source_text(ctx: AgentContext) -> tuple[str, str]:
    """正文来源：直接给文本（内部调用）或给文件（BFF 上传）。

    简历与 JD 共用这段解码逻辑（简历入口传 resumeText，JD 入口传 jdText），
    但两边的画像口径彼此独立——见 `_jd_extract` 与 `_parse` 的注释。

    返回固定为 TEXT / FILE 两种 sourceKind，前端据此区分"粘贴文本"与"上传文件"，
    也便于排查"同一份材料两种入口解析结果不一致"的客诉。
    """
    text = str(ctx.payload.get("resumeText") or ctx.payload.get("jdText") or "").strip()
    if text:
        return text, "TEXT"

    raw = str(ctx.payload.get("fileBase64") or "").strip()
    if not raw:
        raise PayloadInvalid("缺少正文：需要 payload.resumeText / payload.jdText 或 payload.fileBase64")

    file_name = str(ctx.payload.get("fileName") or "resume.txt")
    extracted = extract_text(file_name, decode_base64(raw))
    return extracted, "FILE"


def _first(values: list[str]) -> str | None:
    return values[0] if values else None


def _guess_name(text: str) -> str | None:
    for raw in text.splitlines()[:5]:
        line = raw.strip().strip("#*· ")
        if 2 <= len(line) <= 4 and re.fullmatch(r"[\u4e00-\u9fa5]{2,4}", line):
            return line
    return None


def _split_sections(text: str) -> dict[str, list[str]]:
    """按小标题切分简历段落。同一段内的行合并为一条，避免预览区碎片化。"""
    sections: dict[str, list[str]] = {"experience": [], "project": [], "education": []}
    current: str | None = None
    buffer: list[str] = []

    def flush() -> None:
        if current and buffer:
            content = " ".join(part.strip() for part in buffer if part.strip())
            if content:
                sections[current].append(content)

    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        matched = None
        for key, keywords in _SECTION_KEYS.items():
            if any(keyword in line for keyword in keywords) and len(line) <= 12:
                matched = key
                break
        if matched:
            flush()
            current, buffer = matched, []
            continue
        if current:
            buffer.append(line)
    flush()
    return sections


def _infer_weak_points(text: str, blocks: dict[str, list[str]],
                       skills: list[str], experience_years: int | None) -> tuple[list[str], list[str]]:
    """薄弱点推断口径固定且可解释，前端"用户能力追踪"页要能逐条回溯。"""
    weak: list[str] = []
    notes: list[str] = []

    if not skills:
        weak.append("岗位技能标签缺失")
        notes.append("简历未命中任何已知技能词，建议补充技术栈章节")
    if len(blocks["project"]) == 0:
        weak.append("项目经历缺失")
        notes.append("没有识别到项目经历段落，面试出题深度会受限")
    if len(_QUANTIFIED.findall(text)) < 2:
        weak.append("缺少量化成果")
        notes.append("量化数据不足 2 处，AI 面试会重点追问实际收益")
    if experience_years is None:
        notes.append("未识别到工作年限，匹配分不启用年限系数")
    if "Redis" not in skills:
        notes.append("简历未提及 Redis，若岗位要求缓存能力需重点准备")
    return weak, notes


def _completeness(contact: dict[str, str | None], blocks: dict[str, list[str]],
                  skills: list[str], text: str) -> int:
    checks = [
        bool(contact.get("name")), bool(contact.get("email")) or bool(contact.get("phone")),
        bool(contact.get("degree")), bool(skills), bool(blocks["experience"]),
        bool(blocks["project"]), len(text) >= 500,
    ]
    return round(100 * sum(1 for hit in checks if hit) / len(checks))


def _summary(contact: dict[str, str | None], years: int | None, skills: list[str]) -> str:
    parts = []
    if contact.get("degree"):
        parts.append(contact["degree"])
    if years:
        parts.append(f"{years} 年经验")
    if skills:
        parts.append("技术栈：" + "、".join(skills[:6]))
    return "；".join(parts) if parts else "信息不足，建议补充教育背景与技术栈"


def _polish_reasons(hit_skills: list[str], quantified: int, jd_text: str, source: str) -> list[str]:
    reasons: list[str] = []
    if quantified < 2:
        reasons.append("补充量化结果（规模、耗时、增量），让贡献可被验证")
    jd_skills = extract_skills(jd_text)
    overlap = [skill for skill in hit_skills if skill in jd_skills]
    if jd_skills and not overlap:
        reasons.append("当前表述未覆盖岗位要求的关键词，建议按岗位要求调整措辞顺序")
    if len(source) > 200:
        reasons.append("原文偏长，建议压缩为「动作 + 技术方案 + 结果」三段式")
    if not reasons:
        reasons.append("原文已具备结果导向表述，仅做措辞精炼")
    return reasons


__all__ = ["handle"]
