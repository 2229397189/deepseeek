"""技能词典与画像归一化。

为什么把技能识别放在确定性逻辑而不是模型里：
匹配分必须"可复现、可解释、可回归测试"。模型负责叙述与追问，词典负责事实判定，
两者的边界一旦模糊，分数就会随模型随机性漂移，用户也无法追问"为什么扣这 8 分"。
"""

from __future__ import annotations

from typing import Any, Iterable

# 规范名 -> 命中别名（全部小写匹配）
SKILL_ALIASES: dict[str, tuple[str, ...]] = {
    "Java": ("java",),
    "Spring Boot": ("spring boot", "springboot", "spring-boot"),
    "Spring Cloud": ("spring cloud", "springcloud", "微服务"),
    "MyBatis": ("mybatis",),
    "MySQL": ("mysql",),
    "PostgreSQL": ("postgresql", "postgres", "pgvector"),
    "Redis": ("redis",),
    "Kafka": ("kafka",),
    "RocketMQ": ("rocketmq",),
    "Elasticsearch": ("elasticsearch", "elastic search"),
    "Docker": ("docker", "容器化"),
    "Kubernetes": ("kubernetes", "k8s"),
    "JVM 调优": ("jvm", "gc 调优", "gc调优", "内存模型"),
    "多线程与并发": ("多线程", "并发编程", "juc", "线程池"),
    "分布式事务": ("分布式事务", "最终一致性", "tcc"),
    "高并发设计": ("高并发", "限流", "熔断", "降级", "缓存击穿"),
    "Python": ("python",),
    "TypeScript": ("typescript",),
    "React": ("react",),
    "Vue": ("vue",),
    "AI 应用开发": ("langchain", "大模型", "llm", "rag", "agent 开发"),
}

# 追问模板：命中薄弱点时按技能取用，保证"薄弱点刻意追问"这一策略可测
FOLLOW_UP_BANK: dict[str, tuple[str, ...]] = {
    "Redis": (
        "你刚提到用 Redis 做缓存，如果出现缓存击穿并伴随热点 key 失效，你的兜底方案是什么？",
        "Redis 持久化你是怎么选的？RDB 与 AOF 混用时，恢复时间你能接受的上限是多少？",
    ),
    "Spring Boot": (
        "你项目里的自动配置是如何生效的？如果两个 Starter 冲突，你从哪里排查？",
        "事务在同一个类内部方法调用时为什么会失效？你实际是怎么规避的？",
    ),
    "JVM 调优": (
        "线上老年代持续增长但 Full GC 后回落有限，你的定位路径是什么？",
        "你如何判断一次 Full GC 是内存泄漏还是元空间膨胀？给出具体证据。",
    ),
    "MySQL": (
        "这条 SQL 走了全表扫描，你如何用执行计划证明问题出在索引选择上？",
        "间隙锁在你的业务里造成了死锁，你会怎么改写事务边界？",
    ),
    "多线程与并发": (
        "线程池参数是按什么依据设定的？队列满了之后你的拒绝策略是什么？",
        "你在哪里见过可见性问题？怎么用工具证明结论？",
    ),
    "分布式事务": (
        "你选的方案在 Partial Failure 场景下如何保证最终一致？补偿失败又怎么办？",
    ),
}

DEFAULT_FOLLOW_UP = (
    "这个结论是你实测得到的还是推断的？如果让你给出量化证据，你会补哪些数据？",
)


def extract_skills(text: str | None, *, ordered: bool = True) -> list[str]:
    """从文本中抽取规范技能名。保持首次出现顺序，便于前端稳定展示。"""
    if not text:
        return []
    lowered = text.lower()
    hits: list[tuple[int, str]] = []
    for canonical, aliases in SKILL_ALIASES.items():
        positions = [lowered.find(alias) for alias in aliases if lowered.find(alias) >= 0]
        if positions:
            hits.append((min(positions), canonical))
    hits.sort(key=lambda item: item[0])
    names = [name for _, name in hits]
    return names if ordered else sorted(names)


def normalize_text_list(value: Any) -> list[str]:
    """把 payload 里可能是字符串 / 列表 / None 的字段统一成字符串列表。"""
    if value is None:
        return []
    if isinstance(value, str):
        return [value] if value.strip() else []
    if isinstance(value, Iterable):
        return [str(item).strip() for item in value if str(item).strip()]
    return [str(value)]


def normalize_profile(profile: Any, *, resume_text: str | None = None) -> dict[str, Any]:
    """归一化用户能力画像。

    平台内部打通简历数据的意义就在这里：调用 JD 分析时无需重复上传简历，
    画像由 BFF 从库中直接带入；本次若显式传了简历文本，则与画像技能做并集。
    """
    data = profile if isinstance(profile, dict) else {}
    skills = normalize_text_list(data.get("skills"))
    if resume_text:
        for skill in extract_skills(resume_text):
            if skill not in skills:
                skills.append(skill)

    interview_score = data.get("interviewAvgScore")
    try:
        interview_score = float(interview_score) if interview_score is not None else None
    except (TypeError, ValueError):
        interview_score = None

    return {
        "skills": skills,
        "weakPoints": normalize_text_list(data.get("weakPoints")),
        "experiences": normalize_text_list(data.get("experiences")),
        "projects": normalize_text_list(data.get("projects")),
        "interviewAvgScore": interview_score,
        "resumeText": resume_text or data.get("resumeText") or "",
    }


def follow_up_for(skill: str, used_count: int = 0) -> str:
    """按技能取追问句；同一技能多次追问时轮换，避免整场重复同一句。"""
    candidates = FOLLOW_UP_BANK.get(skill)
    if not candidates:
        return DEFAULT_FOLLOW_UP
    return candidates[used_count % len(candidates)]
