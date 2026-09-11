"""岗位评估 agent：DECIDE（完整评估）与 DECIDE_PREVIEW（轻量预览）。

流水线严格分步并可观测，与页面规格一致：
``parse_jd → load_profile → match_score → 生成建议``

打分口径（三项融合，权重固定、结果可复现）：
- 岗位要求覆盖度 0.60：岗位技能在画像中的覆盖率，这是"能不能过筛"的主因素；
- 过往经历相关性 0.25：画像中经历/项目文本对岗位技能的命中情况；
- 平台内面试表现 0.15：历史面试均分，缺失时用 0.6 中位基线，并在风险点里标注置信度下降。
薄弱点命中额外惩罚：每条 -8 分。反向修正（而非直接减总分）会让用户看不懂分数，因此显式列出。

预览模式刻意不调用模型：用户在首页输入 JD 时只需秒级看到分数区间，
真实模型调用留给正式评估，避免"随手粘贴一次 JD 就打一次模型"的成本浪费。
"""

from __future__ import annotations

import time

from ..core.errors import PayloadInvalid
from ..core.prompts import DECIDE_ADVICE_PROMPT, PROMPT_VERSION, SYSTEM_BASE
from ..llm.client import LlmMessage, task_marker
from ..schemas import AgentOutcome, BizType, Usage
from .registry import AgentContext, StepRecorder
from .skills import extract_skills, normalize_profile

W_COVERAGE = 0.60
W_EXPERIENCE = 0.25
W_INTERVIEW = 0.15
WEAK_PENALTY = 8
INTERVIEW_BASELINE = 0.60

CONCLUDE_APPLY = "APPLY"
CONCLUDE_HOLD = "HOLD"
CONCLUDE_REJECT = "REJECT"


async def handle(ctx: AgentContext) -> AgentOutcome:
    if ctx.request.biz_type is BizType.DECIDE_PREVIEW:
        return await _preview(ctx)
    return await _decide(ctx)


# ---------------------------------------------------------------------------
# DECIDE
# ---------------------------------------------------------------------------

async def _decide(ctx: AgentContext) -> AgentOutcome:
    payload = ctx.payload
    jd_text = str(payload.get("jdText") or payload.get("jd") or "").strip()
    if not jd_text:
        raise PayloadInvalid("DECIDE 需要 payload.jdText")

    recorder = StepRecorder(ctx.emit)

    started = await recorder.start("parse_jd")
    required_skills = extract_skills(jd_text)
    hard_requirements = _hard_requirements(jd_text)
    await recorder.done("parse_jd",
                        f"岗位技能 {len(required_skills)} 项，硬性要求 {len(hard_requirements)} 条", started)

    started = await recorder.start("load_profile")
    resume_text = str(payload.get("resumeText") or "")
    profile = normalize_profile(payload.get("profile"), resume_text=resume_text or None)
    await recorder.done("load_profile",
                        f"画像技能 {len(profile['skills'])} 项，"
                        f"经历 {len(profile['experiences'])} 段，"
                        f"面试均分 {profile['interviewAvgScore'] if profile['interviewAvgScore'] is not None else '无'}"
                        f"（简历数据由平台内部带入，无需重复上传）",
                        started)

    started = await recorder.start("match_score")
    scoring = _score(required_skills, profile)
    await recorder.done("match_score", f"综合匹配分 {scoring['score']} / 100", started)

    started = await recorder.start("generate_advice")
    messages = [
        LlmMessage(role="system", content=DECIDE_ADVICE_PROMPT.format(system=SYSTEM_BASE)),
        LlmMessage(role="user", content=(
            f"{task_marker('DECIDE_ADVICE')}\n"
            f"岗位要求技能：{'、'.join(required_skills) or '未识别'}\n"
            f"候选人技能：{'、'.join(profile['skills']) or '未识别'}\n"
            f"已覆盖：{'、'.join(scoring['covered']) or '无'}\n"
            f"未覆盖：{'、'.join(scoring['missing']) or '无'}\n"
            f"薄弱点命中：{'、'.join(scoring['weakHits']) or '无'}\n"
            f"综合匹配分：{scoring['score']}，结论：{scoring['conclusion']}"
        )),
    ]
    advice = await ctx.llm.chat(messages)
    await recorder.done("generate_advice", "已生成结论建议", started)

    output = {
        "score": scoring["score"],
        "conclusion": scoring["conclusion"],
        "dimensions": scoring["dimensions"],
        "requiredSkills": required_skills,
        "coveredSkills": scoring["covered"],
        "missingSkills": scoring["missing"],
        "weakPointsHit": scoring["weakHits"],
        "hardRequirements": hard_requirements,
        "risks": scoring["risks"],
        "advice": advice.text,
        "todos": scoring["todos"],
        "profileUsed": {
            "skills": profile["skills"],
            "weakPoints": profile["weakPoints"],
            "interviewAvgScore": profile["interviewAvgScore"],
        },
        "steps": [step.model_dump() for step in recorder.steps],
    }
    return AgentOutcome(output=output, steps=recorder.steps,
                        usage=Usage(prompt_tokens=advice.prompt_tokens, output_tokens=advice.output_tokens),
                        model=advice.model, prompt_version=PROMPT_VERSION,
                        mock=advice.model == "deterministic-mock")


# ---------------------------------------------------------------------------
# DECIDE_PREVIEW：不调用模型，供首页输入即算
# ---------------------------------------------------------------------------

async def _preview(ctx: AgentContext) -> AgentOutcome:
    payload = ctx.payload
    jd_text = str(payload.get("jdText") or payload.get("jd") or "").strip()
    if not jd_text:
        raise PayloadInvalid("DECIDE_PREVIEW 需要 payload.jdText")

    recorder = StepRecorder(ctx.emit)
    started = await recorder.start("parse_jd")
    required_skills = extract_skills(jd_text)
    await recorder.done("parse_jd", f"岗位技能 {len(required_skills)} 项", started)

    started = await recorder.start("load_profile")
    profile = normalize_profile(payload.get("profile"), resume_text=payload.get("resumeText"))
    await recorder.done("load_profile", f"画像技能 {len(profile['skills'])} 项", started)

    started = await recorder.start("match_score")
    scoring = _score(required_skills, profile)
    await recorder.done("match_score", f"预览分 {scoring['score']}", started)

    output = {
        "score": scoring["score"],
        "scoreBand": _band(scoring["score"]),
        "conclusion": scoring["conclusion"],
        "preview": True,
        "coveredSkills": scoring["covered"],
        "missingSkills": scoring["missing"],
        "weakPointsHit": scoring["weakHits"],
        "risks": scoring["risks"][:2],
        "advice": "预览模式仅给出分数与缺口，正式评估将补充结论建议与补强清单。",
        "steps": [step.model_dump() for step in recorder.steps],
    }
    return AgentOutcome(output=output, steps=recorder.steps, usage=Usage(),
                        model="preview-local", prompt_version=PROMPT_VERSION, mock=True)


# ---------------------------------------------------------------------------
# 打分
# ---------------------------------------------------------------------------

def _score(required: list[str], profile: dict) -> dict:
    owned = {skill.lower() for skill in profile["skills"]}
    covered = [skill for skill in required if skill.lower() in owned]
    missing = [skill for skill in required if skill.lower() not in owned]

    coverage = len(covered) / len(required) if required else 0.0

    experience_pool = " ".join(profile["experiences"] + profile["projects"])
    exp_skills = {skill.lower() for skill in extract_skills(experience_pool)}
    exp_hit = [skill for skill in required if skill.lower() in exp_skills]
    experience_relevance = (len(exp_hit) / len(required)) if required else 0.0

    interview_score = profile["interviewAvgScore"]
    performance = (interview_score / 100.0) if interview_score is not None else INTERVIEW_BASELINE

    weak_hits = [skill for skill in profile["weakPoints"] if skill in required or not required]

    raw = 100 * (W_COVERAGE * coverage + W_EXPERIENCE * experience_relevance + W_INTERVIEW * performance)
    score = max(0, min(100, round(raw - WEAK_PENALTY * len(weak_hits))))

    risks: list[str] = []
    if missing:
        risks.append("岗位要求 " + "、".join(missing[:4]) + "，画像中未覆盖")
    if weak_hits:
        risks.append("曾判定为薄弱的知识点（" + "、".join(weak_hits) + "）在整场面试中会被刻意追问")
    if interview_score is None:
        risks.append("缺少平台内面试表现数据，评分置信度中等（已按中位基线估算）")
    if not required:
        risks.append("未能从 JD 中识别出已知技能词，建议补充职责细节后重新评估")

    todos: list[str] = []
    for skill in missing[:3]:
        todos.append(f"补齐 {skill}：完成 2 道相关题目并复盘答题录音")
    for skill in weak_hits[:2]:
        todos.append(f"重点巩固 {skill}：用「讲解 + 追问」方式自测，直至能给出量化证据")

    return {
        "score": score,
        "conclusion": _conclusion(score),
        "covered": covered,
        "missing": missing,
        "weakHits": weak_hits,
        "risks": risks,
        "todos": todos,
        "dimensions": {
            "skillCoverage": round(coverage * 100, 1),
            "experienceRelevance": round(experience_relevance * 100, 1),
            "interviewPerformance": round(performance * 100, 1),
        },
    }


def _conclusion(score: int) -> str:
    if score >= 75:
        return CONCLUDE_APPLY
    if score >= 60:
        return CONCLUDE_HOLD
    return CONCLUDE_REJECT


def _band(score: int) -> str:
    if score >= 75:
        return "高匹配"
    if score >= 60:
        return "可争取"
    return "差距明显"


def _hard_requirements(jd_text: str) -> list[str]:
    """抽取硬性要求（学历 / 年限 / 地点等），这些条件不参与打分但必须显式提示。"""
    keywords = ("本科", "硕士", "博士", "统招", "经验", "年以上", "985", "211", "全日制", "英语")
    found: list[str] = []
    for raw in jd_text.replace("；", "\n").replace("。", "\n").splitlines():
        line = raw.strip()
        if line and any(keyword in line for keyword in keywords) and len(line) <= 120:
            found.append(line)
    return found[:5]


__all__ = ["handle"]
