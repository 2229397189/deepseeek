"""模拟面试 agent。

出题策略（页面规格明确要求，必须落在代码里而不是提示词里）：
1. **简历驱动**：题目从候选人简历命中的技能里长出来，岗位方向决定题目基调；
2. **薄弱点刻意追问**：低分回答所涉及的技能会被记入 ``weakPointsUpdate``，
   并在后续轮次中被反复追问，直到答稳为止；
3. 结合个人经历特点生成个性化问题。

评分用"关键词命中 + 回答结构"两维计算，保证同一份回答永远得到同一分数；
模型只负责措辞与追问表达，不参与打分——分数漂移会让"面试记录列表"失去可比性。
"""

from __future__ import annotations

from ..core.errors import PayloadInvalid
from ..core.prompts import (INTERVIEW_FOLLOWUP_PROMPT, INTERVIEW_QUESTION_PROMPT,
                            INTERVIEW_REPORT_PROMPT, PROMPT_VERSION, SYSTEM_BASE)
from ..llm.client import LlmMessage, task_marker
from ..schemas import AgentOutcome, Usage
from .registry import AgentContext, StepRecorder
from .skills import extract_skills, follow_up_for, normalize_text_list

# 每题的关键词期望库：命中越多说明回答越具体
KEYWORD_BANK: dict[str, tuple[str, ...]] = {
    "Redis": ("缓存", "过期", "穿透", "击穿", "雪崩", "持久化", "集群", "一致"),
    "Spring Boot": ("自动配置", "starter", "bean", "事务", "aop", "拦截器", "条件装配"),
    "JVM 调优": ("gc", "堆", "栈", "对象", "内存", "调优", "工具", "dump", "引用"),
    "MySQL": ("索引", "执行计划", "事务", "隔离级别", "锁", "explain", "慢查询"),
    "多线程与并发": ("线程池", "锁", "cas", "可见性", "队列", "拒绝策略", "volatile"),
    "分布式事务": ("最终一致", "补偿", "幂等", "消息", "tcc", "对账"),
    "Kafka": ("分区", "顺序", "消费组", "堆积", "幂等", "重平衡"),
    "Docker": ("镜像", "容器", "网络", "编排", "资源限制"),
    "Kubernetes": ("pod", "service", "探针", "发布", "调度", "扩缩容"),
    "高并发设计": ("限流", "熔断", "降级", "缓存", "压测", "容量", "队列"),
}

DEFAULT_KEYWORDS = ("背景", "方案", "结果", "取舍", "数据", "问题", "优化")

QUESTION_BANK: dict[str, str] = {
    "Redis": "你在项目里怎么用 Redis 的？请说明缓存粒度、失效策略，以及一次真实的缓存问题排查过程。",
    "Spring Boot": "请介绍一个你主导的 Spring Boot 服务，重点说明分层设计、事务边界与异常处理策略。",
    "JVM 调优": "说一下你做过的一次 JVM 调优：现象、定位工具、根因与最终收益。",
    "MySQL": "请讲一个你优化过的慢查询：表结构与数据量、执行计划、索引方案与优化前后指标。",
    "多线程与并发": "你项目里哪些场景用了多线程？线程池怎么配的，为什么这么配？",
    "分布式事务": "跨服务写入的场景你是怎么保证一致性的？失败了怎么补偿？",
    "Kafka": "消息堆积你是怎么发现和处理的？消费幂等又是怎么保证的？",
    "Docker": "容器化改造后你们遇到了哪些问题？镜像是怎么瘦身和分层构建的？",
    "Kubernetes": "发布过程中如何做到不丢流量？探针和优雅停机你是怎么配的？",
    "高并发设计": "请设计一个瞬时高并发场景的方案，说明容量评估、限流位置与降级策略。",
}

GENERIC_QUESTION = ("请挑一个你最有成就感的项目，说明背景、你的独立贡献、遇到的最大技术挑战"
                    "以及最终的可量化结果。")

DIMENSION_HINT = {
    "技术深度": ("索引", "gc", "锁", "源码", "原理", "参数", "并发", "调优"),
    "表达结构": ("背景", "职责", "方案", "结果", "因为", "所以", "首先", "其次"),
    "数据意识": ("qps", "tps", "ms", "毫秒", "%", "万", "倍", "提升了", "下降了"),
}

MAX_QUESTIONS = 8


async def handle(ctx: AgentContext) -> AgentOutcome:
    mode = str(ctx.payload.get("mode") or _infer_mode(ctx)).upper()
    if mode == "START":
        return await _start(ctx)
    if mode == "ANSWER":
        return await _answer(ctx)
    if mode == "FINISH":
        return await _finish(ctx)
    raise PayloadInvalid(f"不支持的面试模式：{mode}（可选 START / ANSWER / FINISH）")


def _infer_mode(ctx: AgentContext) -> str:
    if ctx.request.stage:
        return ctx.request.stage.upper()
    if ctx.payload.get("answer"):
        return "ANSWER"
    if ctx.payload.get("history"):
        return "FINISH"
    return "START"


# ---------------------------------------------------------------------------
# 开场：生成首题与整场题目计划
# ---------------------------------------------------------------------------

async def _start(ctx: AgentContext) -> AgentOutcome:
    resume_text = str(ctx.payload.get("resumeText") or "")
    job_title = str(ctx.payload.get("jobTitle") or "目标岗位")
    weak_points = normalize_text_list(ctx.payload.get("weakPoints"))
    jd_skills = extract_skills(str(ctx.payload.get("jdText") or ""))
    resume_skills = extract_skills(resume_text)

    recorder = StepRecorder(ctx.emit)
    started = await recorder.start("plan_questions")
    plan: list[dict] = []

    # 1) 岗位要求但简历未覆盖的技能：优先探底，避免面试全程"聊得开心但能力缺口没被验证"
    for skill in [s for s in jd_skills if s not in resume_skills][:2]:
        plan.append({"skill": skill, "reason": "岗位要求但简历未覆盖，需要现场验证"})
    # 2) 简历驱动
    for skill in resume_skills[:3]:
        plan.append({"skill": skill, "reason": "简历技能，做深做透"})
    # 3) 已知薄弱点，刻意追问
    for skill in weak_points[:2]:
        plan.append({"skill": skill, "reason": "历史薄弱点，本场重复追问"})
    if not plan:
        plan.append({"skill": None, "reason": "简历信息不足，使用通用项目问题"})

    await recorder.done("plan_questions", f"出题计划 {len(plan)} 题："
                                         + "、".join(item["skill"] or "通用" for item in plan), started)

    first = plan[0]
    started = await recorder.start("ask_question")
    messages = [
        LlmMessage(role="system", content=INTERVIEW_QUESTION_PROMPT.format(system=SYSTEM_BASE)),
        LlmMessage(role="user", content=(
            f"{task_marker('INTERVIEW_QUESTION')}\n"
            f"岗位：{job_title}\n简历摘要：{resume_text[:800]}\n"
            f"本题考察方向：{first['skill'] or '综合项目能力'}\n"
            f"考察原因：{first['reason']}"
        )),
    ]
    question_text = await ctx.llm.chat(messages)
    question = _question_for(first["skill"], question_text.text)
    await recorder.done("ask_question", f"首题考察 {first['skill'] or '综合能力'}", started)

    output = {
        "mode": "START",
        "question": question,
        "skill": first["skill"],
        "reason": first["reason"],
        "questionIndex": 1,
        "maxQuestions": MAX_QUESTIONS,
        "questionPlan": plan,
        "weakPointsTracking": weak_points,
        "steps": [step.model_dump() for step in recorder.steps],
    }
    return AgentOutcome(output=output, steps=recorder.steps,
                        usage=Usage(prompt_tokens=question_text.prompt_tokens,
                                    output_tokens=question_text.output_tokens),
                        model=question_text.model, prompt_version=PROMPT_VERSION,
                        mock=question_text.model == "deterministic-mock")


# ---------------------------------------------------------------------------
# 作答：评分 + 薄弱点判定 + 追问
# ---------------------------------------------------------------------------

async def _answer(ctx: AgentContext) -> AgentOutcome:
    payload = ctx.payload
    answer = str(payload.get("answer") or "").strip()
    if not answer:
        raise PayloadInvalid("INTERVIEW(ANSWER) 需要 payload.answer")
    question = str(payload.get("question") or "")
    skill = payload.get("skill")
    weak_points = normalize_text_list(payload.get("weakPoints"))
    history = payload.get("history") or []
    follow_up_count = int(payload.get("followUpCount") or 0)

    recorder = StepRecorder(ctx.emit)
    started = await recorder.start("score_answer")
    keywords = KEYWORD_BANK.get(skill, DEFAULT_KEYWORDS) if skill else DEFAULT_KEYWORDS
    hits = [word for word in keywords if word.lower() in answer.lower()]
    coverage = len(hits) / len(keywords) if keywords else 0.0
    length_score = min(len(answer) / 300.0, 1.0)
    score = max(0, min(100, round(100 * (0.75 * coverage + 0.25 * length_score))))
    await recorder.done("score_answer",
                        f"本题 {score} 分，命中关键词 {len(hits)}/{len(keywords)}", started)

    started = await recorder.start("detect_weak_point")
    is_weak = score < 70
    updated_weak = list(weak_points)
    if is_weak and skill and skill not in updated_weak:
        updated_weak.append(skill)
    await recorder.done("detect_weak_point",
                        f"{'判定为薄弱点，将进入刻意追问' if is_weak else '回答达标'}"
                        f"（当前薄弱点 {len(updated_weak)} 项）", started)

    need_follow_up = (is_weak or (skill in weak_points)) and follow_up_count < 3
    follow_up = None
    usage = Usage()
    model = "scoring-local"
    if need_follow_up:
        started = await recorder.start("ask_follow_up")
        messages = [
            LlmMessage(role="system", content=INTERVIEW_FOLLOWUP_PROMPT.format(system=SYSTEM_BASE)),
            LlmMessage(role="user", content=(
                f"{task_marker('INTERVIEW_FOLLOWUP')}\n"
                f"考察方向：{skill or '综合能力'}\n上一题：{question}\n"
                f"候选人回答：{answer[:1500]}\n"
                f"命中要点：{'、'.join(hits) or '无'}\n"
                f"缺失要点：{'、'.join(w for w in keywords if w not in hits) or '无'}"
            )),
        ]
        generated = await ctx.llm.chat(messages)
        follow_up = generated.text.strip() or follow_up_for(skill or "", follow_up_count)
        usage = Usage(prompt_tokens=generated.prompt_tokens, output_tokens=generated.output_tokens)
        model = generated.model
        await recorder.done("ask_follow_up", "已生成追问", started)

    output = {
        "mode": "ANSWER",
        "score": score,
        "matchedKeywords": hits,
        "missingKeywords": [word for word in keywords if word not in hits],
        "isWeak": is_weak,
        "hasFollowUp": follow_up is not None,
        "followUp": follow_up,
        "followUpCount": follow_up_count + (1 if follow_up else 0),
        "nextSkill": skill if follow_up else _next_skill(payload, history),
        "weakPointsUpdate": updated_weak,
        "historyLength": len(history),
        "steps": [step.model_dump() for step in recorder.steps],
    }
    return AgentOutcome(output=output, steps=recorder.steps, usage=usage, model=model,
                        prompt_version=PROMPT_VERSION, mock=model == "deterministic-mock")


# ---------------------------------------------------------------------------
# 结束：整场评分报告
# ---------------------------------------------------------------------------

async def _finish(ctx: AgentContext) -> AgentOutcome:
    history = ctx.payload.get("history") or []
    if not isinstance(history, list) or not history:
        raise PayloadInvalid("INTERVIEW(FINISH) 需要 payload.history（逐题问答记录）")

    recorder = StepRecorder(ctx.emit)
    started = await recorder.start("aggregate_scores")
    item_scores: list[int] = []
    dimensions = {"技术深度": 0, "表达结构": 0, "数据意识": 0}
    weak_skills: list[str] = []
    for item in history:
        score = int(item.get("score") or 0)
        item_scores.append(score)
        answer = str(item.get("answer") or "")
        for dimension, words in DIMENSION_HINT.items():
            if any(word in answer.lower() for word in words):
                dimensions[dimension] += 1
        if score < 70 and item.get("skill") and item["skill"] not in weak_skills:
            weak_skills.append(item["skill"])
    total = round(sum(item_scores) / len(item_scores)) if item_scores else 0
    await recorder.done("aggregate_scores", f"共 {len(item_scores)} 题，均分 {total}", started)

    started = await recorder.start("generate_report")
    messages = [
        LlmMessage(role="system", content=INTERVIEW_REPORT_PROMPT.format(system=SYSTEM_BASE)),
        LlmMessage(role="user", content=(
            f"{task_marker('INTERVIEW_REPORT')}\n"
            f"岗位：{ctx.payload.get('jobTitle') or '目标岗位'}\n"
            f"逐题得分：{item_scores}\n薄弱技能：{'、'.join(weak_skills) or '无明显薄弱点'}\n"
            + "\n".join(f"Q{i + 1}：{str(it.get('question'))[:120]}\nA：{str(it.get('answer'))[:300]}"
                        for i, it in enumerate(history[:6]))
        )),
    ]
    report = await ctx.llm.chat(messages)
    await recorder.done("generate_report", "已生成面试评语", started)

    total_dimensions = len(item_scores) or 1
    output = {
        "mode": "FINISH",
        "totalScore": total,
        "questionCount": len(item_scores),
        "itemScores": item_scores,
        "dimensions": {name: round(100 * value / total_dimensions) for name, value in dimensions.items()},
        "weakPointsUpdate": weak_skills,
        "report": report.text,
        "steps": [step.model_dump() for step in recorder.steps],
    }
    return AgentOutcome(output=output, steps=recorder.steps,
                        usage=Usage(prompt_tokens=report.prompt_tokens, output_tokens=report.output_tokens),
                        model=report.model, prompt_version=PROMPT_VERSION,
                        mock=report.model == "deterministic-mock")


# ---------------------------------------------------------------------------
# 工具
# ---------------------------------------------------------------------------

def _question_for(skill: str | None, llm_text: str) -> str:
    """优先使用题库中的标准问法：题库经过人工打磨，模型输出仅作为补位。"""
    if skill and skill in QUESTION_BANK:
        return QUESTION_BANK[skill]
    text = (llm_text or "").strip()
    return text or GENERIC_QUESTION


def _next_skill(payload: dict, history: list) -> str | None:
    plan = payload.get("questionPlan") or []
    asked = {str(item.get("skill")) for item in history if isinstance(item, dict)}
    for entry in plan:
        skill = entry.get("skill") if isinstance(entry, dict) else None
        if skill and skill not in asked:
            return skill
    return None


__all__ = ["handle"]
