"""agent-service 契约与行为测试。

覆盖点不是"跑通即可"，而是把产品的硬性策略钉死在测试里：
- 同输入必须同分数（否则面试记录不可比）；
- 薄弱点必须被追问（这是页面规格里的明确策略，退化会被测试拦住）；
- 失败必须带稳定 errorCode（否则 BFF 的重试策略无法按码分类）。
"""

from __future__ import annotations

import json

import pytest
from fastapi.testclient import TestClient

from app.main import app

JD_TEXT = (
    "岗位职责：负责核心交易系统的设计与开发。"
    "任职要求：本科及以上学历，3 年以上 Java 开发经验；"
    "精通 Spring Boot、MySQL、Redis；熟悉 Kafka、Docker；"
    "有高并发与分布式事务实践经验者优先。"
)

RESUME_TEXT = """
张三
zhangsan@example.com 13800138000
本科 5 年工作经验

项目经历
- 主导订单中心重构，引入 Redis 缓存与本地缓存二级结构，接口耗时下降 60%
- 使用 Kafka 做异步解耦，峰值 QPS 提升 3 倍，日均处理 200 万消息

技能
Java、Spring Boot、MySQL、Kafka、Docker
"""


@pytest.fixture(scope="module")
def client():
    with TestClient(app) as test_client:
        yield test_client


def _invoke(client: TestClient, biz_type: str, payload: dict, stage: str | None = None) -> dict:
    resp = client.post("/v1/agent/invoke", json={
        "runId": "run-test-1", "bizType": biz_type, "bizId": 1, "userId": 1,
        "stage": stage, "specHash": "spec-test", "payload": payload,
    })
    assert resp.status_code == 200, resp.text
    return resp.json()


# ---------------------------------------------------------------------------
# 健康与基础契约
# ---------------------------------------------------------------------------

def test_health_reports_model_impl(client: TestClient):
    body = client.get("/health").json()
    assert body["status"] == "UP"
    assert body["model"] == "deterministic-mock"  # 无 key 时必须自动兜底，不允许启动失败


def test_missing_jd_returns_failed_with_stable_code(client: TestClient):
    body = _invoke(client, "DECIDE", {"profile": {"skills": ["Java"]}})
    assert body["status"] == "FAILED"
    assert body["errorCode"] == "AGENT_STAGE_FAILED"


def test_unknown_stage_fails_with_code(client: TestClient):
    body = _invoke(client, "INTERVIEW", {"mode": "UNKNOWN"})
    assert body["status"] == "FAILED"
    assert body["errorCode"] == "AGENT_STAGE_FAILED"


# ---------------------------------------------------------------------------
# DECIDE
# ---------------------------------------------------------------------------

def test_decide_pipeline_and_deterministic_score(client: TestClient):
    payload = {"jdText": JD_TEXT, "profile": {
        "skills": ["Java", "Spring Boot", "MySQL", "Redis"],
        "weakPoints": ["分布式事务"],
        "experiences": ["交易系统开发"],
        "interviewAvgScore": 80,
    }}
    first = _invoke(client, "DECIDE", payload)
    second = _invoke(client, "DECIDE", payload)

    assert first["status"] == "SUCCEEDED"
    output = first["output"]
    assert [step["name"] for step in output["steps"]] == [
        "parse_jd", "load_profile", "match_score", "generate_advice"]
    assert output["steps"][0]["status"] == "DONE"

    # 岗位要求里的 Kafka / Docker / 高并发未覆盖，薄弱点命中 1 项
    assert "Redis" in output["coveredSkills"]
    assert "Kafka" in output["missingSkills"]
    assert output["weakPointsHit"] == ["分布式事务"]
    assert output["score"] == second["output"]["score"]  # 同输入同分数
    assert output["dimensions"]["interviewPerformance"] == 80.0
    assert output["todos"]
    assert output["meta"]["promptVersion"]


def test_decide_preview_is_local_and_cheap(client: TestClient):
    body = _invoke(client, "DECIDE_PREVIEW", {"jdText": JD_TEXT, "profile": {"skills": ["Java"]}})
    output = body["output"]
    assert output["preview"] is True
    assert output["meta"]["mock"] is True
    assert output["meta"]["model"] == "preview-local"
    assert body["promptTokens"] == 0  # 预览不得消耗模型额度
    assert output["scoreBand"] in {"高匹配", "可争取", "差距明显"}


def test_decide_uses_platform_profile_without_reupload(client: TestClient):
    """画像由平台内部带入，调用 JD 分析时无需重复上传简历。

    同一份技能画像下，只把"经历描述"写具体，分数就应当从 HOLD 抬到 APPLY——
    这条用例锁住的是"经历相关性维度真的在起作用"，而不是某个魔法分数。
    """
    skills = ["Java", "Spring Boot", "MySQL", "Redis", "Kafka", "Docker", "高并发设计"]
    thin = _invoke(client, "DECIDE", {"jdText": JD_TEXT, "profile": {
        "skills": skills, "experiences": ["Spring Boot 服务开发"], "interviewAvgScore": 90}})["output"]
    rich = _invoke(client, "DECIDE", {"jdText": JD_TEXT, "profile": {
        "skills": skills,
        "experiences": ["主导 Spring Boot 交易系统重构，覆盖 Java、MySQL、Redis、Kafka、Docker 与高并发设计"],
        "interviewAvgScore": 90}})["output"]

    assert thin["dimensions"]["interviewPerformance"] == 90.0
    assert thin["dimensions"]["experienceRelevance"] < rich["dimensions"]["experienceRelevance"]
    assert rich["score"] > thin["score"]
    assert rich["score"] >= 75 and rich["conclusion"] == "APPLY"
    # 缺失的岗位技能必须如实列出，不允许为了好看而抹平
    assert "分布式事务" in rich["missingSkills"]


# ---------------------------------------------------------------------------
# INTERVIEW
# ---------------------------------------------------------------------------

def test_interview_flow_follows_up_on_weak_answer(client: TestClient):
    start = _invoke(client, "INTERVIEW", {
        "mode": "START", "resumeText": RESUME_TEXT, "jdText": JD_TEXT,
        "weakPoints": ["分布式事务"], "jobTitle": "后端工程师",
    })["output"]
    assert start["question"]
    assert start["questionPlan"]

    answer = _invoke(client, "INTERVIEW", {
        "mode": "ANSWER",
        "question": start["question"], "skill": start["skill"] or "Redis",
        "answer": "用过。",  # 刻意给一个空洞回答
        "weakPoints": ["分布式事务"], "history": [], "followUpCount": 0,
    })["output"]
    assert answer["score"] < 70
    assert answer["isWeak"] is True
    assert answer["hasFollowUp"] is True
    assert answer["followUp"]
    assert answer["weakPointsUpdate"]
    # P0-6: ANSWER 必须直接返回下一题，BFF 才能在同一往返里推进出题计划
    assert answer["question"]
    assert "isFinished" in answer

    finish = _invoke(client, "INTERVIEW", {
        "mode": "FINISH", "jobTitle": "后端工程师",
        "history": [
            {"question": start["question"], "answer": "用过。", "score": answer["score"],
             "skill": start["skill"] or "Redis"},
            {"question": "讲讲缓存穿透", "answer": "用布隆过滤器并做了空值缓存，压测 QPS 提升 2 倍，"
                                                    "平均耗时下降 30 毫秒。", "score": 85, "skill": "Redis"},
        ],
    })["output"]
    assert finish["questionCount"] == 2
    assert finish["totalScore"] == round((answer["score"] + 85) / 2)
    assert finish["weakPointsUpdate"]
    assert finish["report"]
    assert set(finish["dimensions"]) == {"技术深度", "表达结构", "数据意识"}


def test_interview_good_answer_skips_follow_up(client: TestClient):
    output = _invoke(client, "INTERVIEW", {
        "mode": "ANSWER", "question": "讲讲缓存", "skill": "Redis",
        "answer": "我用 Redis 做缓存，针对穿透做了空值缓存与布隆过滤器，"
                  "针对击穿加了互斥锁与逻辑过期，雪崩用随机过期时间规避；"
                  "持久化选 AOF 每秒刷盘，集群三主三从，一致性通过延时双删保证。",
        "weakPoints": [], "history": [], "followUpCount": 0,
    })["output"]
    assert output["score"] >= 70
    assert output["isWeak"] is False
    assert output["hasFollowUp"] is False


def test_interview_next_advances_through_plan_and_closes(client: TestClient):
    """NEXT 阶段：依据 questionPlan 推进到下一个未问技能，计划走完进入收尾反问。"""
    start = _invoke(client, "INTERVIEW", {
        "mode": "START", "resumeText": RESUME_TEXT, "jdText": JD_TEXT,
        "weakPoints": [], "jobTitle": "后端工程师",
    })["output"]
    plan = start["questionPlan"]
    assert plan

    # 已问过首题，history 里带 skill；NEXT 应挑下一个未问技能
    asked = [{"question": start["question"], "answer": "答了", "score": 80, "skill": start["skill"]}]
    nxt = _invoke(client, "INTERVIEW", {
        "mode": "NEXT", "jobTitle": "后端工程师",
        "questionPlan": plan, "history": asked, "weakPoints": [],
    })["output"]
    assert nxt["mode"] == "NEXT"
    assert nxt["question"]
    # 下一题的技能必须是计划里、且不等于已问的首题技能
    assert nxt["skill"] != start["skill"]
    assert any(entry["skill"] == nxt["skill"] for entry in plan)
    assert nxt["isFinished"] is False

    # 把所有计划技能都标记为已问，NEXT 应进入收尾反问
    all_asked = asked + [{"question": f"q-{e['skill']}", "answer": "答了", "score": 80,
                          "skill": e["skill"]} for e in plan if e["skill"] != start["skill"]]
    closing = _invoke(client, "INTERVIEW", {
        "mode": "NEXT", "jobTitle": "后端工程师",
        "questionPlan": plan, "history": all_asked, "weakPoints": [],
    })["output"]
    assert closing["isFinished"] is True
    assert "补充" in closing["question"] or "反问" in closing["question"]


# ---------------------------------------------------------------------------
# RESUME
# ---------------------------------------------------------------------------

def test_decide_advice_never_contradicts_conclusion(client: TestClient):
    """建议文案必须服从分数结论。

    这条用例拦的是一个真实出现过的自相矛盾：低分场景下结论是 REJECT，
    但建议文案仍写着"建议推进申请"。用户在同一个页面里看到互相打架的两句话，
    会直接怀疑打分逻辑是否可信。
    """
    weak = _invoke(client, "DECIDE", {"jdText": JD_TEXT, "profile": {
        "skills": ["Java"], "experiences": ["测试"], "interviewAvgScore": 50}})["output"]
    assert weak["conclusion"] == "REJECT"
    assert "建议推进" not in weak["advice"]
    assert "暂不建议" in weak["advice"]

    strong = _invoke(client, "DECIDE", {"jdText": JD_TEXT, "profile": {
        "skills": ["Java", "Spring Boot", "MySQL", "Redis", "Kafka", "Docker",
                   "高并发设计", "分布式事务"],
        "experiences": ["主导 Java 交易系统，覆盖 Spring Boot、MySQL、Redis、Kafka、Docker、"
                        "高并发设计、分布式事务"],
        "interviewAvgScore": 95}})["output"]
    assert strong["conclusion"] == "APPLY"
    assert "建议推进" in strong["advice"]
    assert strong["missingSkills"] == []


def test_resume_parse_extracts_skills_and_weak_points(client: TestClient):
    output = _invoke(client, "RESUME_PARSE", {"resumeText": RESUME_TEXT})["output"]
    assert "Java" in output["skills"]
    assert "Redis" in output["skills"]
    assert output["contact"]["email"] == "zhangsan@example.com"
    assert output["experienceYears"] == 5
    assert output["contact"]["degree"] == "本科"
    assert output["projects"]
    assert 0 <= output["completeness"] <= 100


def test_resume_polish_requires_selection(client: TestClient):
    body = _invoke(client, "RESUME_QUESTION", {"mode": "POLISH", "resumeText": RESUME_TEXT})
    assert body["status"] == "FAILED"

    ok = _invoke(client, "RESUME_QUESTION", {
        "mode": "POLISH", "selectedText": "负责订单系统的开发与维护",
        "jobTitle": "后端工程师", "jdText": JD_TEXT,
    })["output"]
    assert ok["polished"]
    assert ok["applyMode"] == "REPLACE_SELECTION"
    assert ok["reasons"]


# ---------------------------------------------------------------------------
# RAG
# ---------------------------------------------------------------------------

def test_rag_reports_recall_gap_instead_of_hallucinating(client: TestClient):
    output = _invoke(client, "RAG_SEARCH", {"query": "Redis 缓存击穿怎么处理"})["output"]
    assert output["recallGap"] is True
    assert output["hits"] == []
    assert "未检索到" in output["answer"]


def test_rag_reranks_by_relevance(client: TestClient):
    output = _invoke(client, "RAG_SEARCH", {
        "query": "Redis 缓存击穿 处理方案",
        "documents": [
            {"documentId": 1, "title": "面试记录", "content": "今天天气不错，聊了聊职业规划。"},
            {"documentId": 2, "title": "缓存治理", "content": "缓存击穿处理方案：互斥锁 + 逻辑过期，"
                                                            "配合 Redis 热点 key 探测。"},
        ],
    })["output"]
    assert output["recallGap"] is False
    assert output["hits"][0]["documentId"] == 2
    assert output["hits"][0]["coverage"] > 0


# ---------------------------------------------------------------------------
# 流式
# ---------------------------------------------------------------------------

def test_stream_emits_step_events_then_result(client: TestClient):
    with client.stream("POST", "/v1/agent/stream", json={
        "runId": "run-stream-1", "bizType": "DECIDE", "payload": {
            "jdText": JD_TEXT, "profile": {"skills": ["Java"]}},
    }) as resp:
        assert resp.status_code == 200
        events = [json.loads(line[5:]) for line in resp.iter_lines() if line.startswith("data:")]

    types = [event["type"] for event in events]
    assert types[0] == "step"
    assert types[-1] == "result"
    assert any(event.get("step", {}).get("status") == "RUNNING" for event in events[:2])
    assert events[-1]["status"] == "SUCCEEDED"
    assert events[-1]["output"]["score"] >= 0

# ---------------------------------------------------------------------------
# RESUME_PARSE：文件来源（BFF 上传直传 base64）
# ---------------------------------------------------------------------------

def test_resume_parse_accepts_uploaded_txt_file(client: TestClient):
    import base64

    payload = {
        "fileName": "resume.txt",
        "fileBase64": base64.b64encode(RESUME_TEXT.encode("utf-8")).decode(),
    }
    body = _invoke(client, "RESUME_PARSE", payload)
    assert body["status"] == "SUCCEEDED", body
    output = body["output"]
    assert output["sourceKind"] == "FILE"
    # 走文件入口与走文本入口必须得到同一份画像，否则两个入口的分数不可比
    assert output["contact"]["email"] == "zhangsan@example.com"
    assert "Java" in output["skills"]
    assert output["completeness"] > 0


def test_resume_parse_rejects_unsupported_file_type(client: TestClient):
    import base64

    payload = {
        "fileName": "resume.exe",
        "fileBase64": base64.b64encode(b"binary").decode(),
    }
    body = _invoke(client, "RESUME_PARSE", payload)
    assert body["status"] == "FAILED"
    assert body["errorCode"] == "UNSUPPORTED_FILE_TYPE"
    assert "不支持的文件类型" in body["errorMsg"]


def test_resume_parse_requires_text_or_file(client: TestClient):
    body = _invoke(client, "RESUME_PARSE", {})
    assert body["status"] == "FAILED"
    assert body["errorCode"] == "AGENT_STAGE_FAILED"


# ---------------------------------------------------------------------------
# RESUME_PARSE(JD_PARSE)：JD 与简历共用解码，但口径必须彼此独立
# ---------------------------------------------------------------------------

def test_jd_parse_extracts_requirements_without_resume_profile(client: TestClient):
    """JD 抽取不得被套上简历画像结构。

    JD 是"岗位要什么"，简历是"我会什么"。若把 JD 塞进简历模板，会产出
    "该 JD 具备 3 年经验 / 学历 本科 / completeness 62" 这类无意义字段，
    前端也会误把岗位要求渲染成候选人信息，这条用例把这个边界钉住。
    """
    import base64

    jd_file = (
        "高级 Java 后端工程师\n"
        "任职要求：本科及以上学历，5 年以上 Java 开发经验，精通 Spring Boot 与 MySQL；\n"
        "熟悉 Redis、Kafka，具备高并发与分布式事务实践者优先。\n"
        "岗位职责：负责核心交易链路的稳定性建设。\n"
    )
    body = _invoke(client, "RESUME_PARSE", {
        "mode": "JD_PARSE",
        "fileName": "jd.txt",
        "fileBase64": base64.b64encode(jd_file.encode("utf-8")).decode(),
    })
    assert body["status"] == "SUCCEEDED", body
    output = body["output"]

    assert output["sourceKind"] == "FILE"
    # 抽取会去掉首尾空白，但正文不允许被截断：charCount 必须与 text 严格自洽
    assert output["charCount"] == len(output["text"])
    assert output["text"].startswith("高级 Java 后端工程师")
    assert "分布式事务" in output["text"]
    assert [step["name"] for step in output["steps"]] == ["extract_text", "extract_requirements"]

    assert any("本科及以上学历" in item for item in output["hardRequirements"])
    assert any("5 年以上" in item for item in output["hardRequirements"])
    assert "Java" in output["skills"] and "Kafka" in output["skills"]

    # 简历口径字段一个都不许出现在 JD 抽取结果里
    for resume_only in ("contact", "completeness", "experienceYears", "weakPoints"):
        assert resume_only not in output, f"JD 抽取泄漏了简历字段：{resume_only}"


def test_jd_parse_without_content_fails_with_clear_reason(client: TestClient):
    body = _invoke(client, "RESUME_PARSE", {"mode": "JD_PARSE"})
    assert body["status"] == "FAILED"
    assert body["errorCode"] == "AGENT_STAGE_FAILED"
    assert "payload" in body["errorMsg"]


def test_jd_parse_accepts_pasted_text(client: TestClient):
    """粘贴文本也要能走通，否则首页"贴一段 JD"的入口会被文件分支挡住。"""
    body = _invoke(client, "RESUME_PARSE", {"mode": "JD_PARSE", "jdText": JD_TEXT})
    assert body["status"] == "SUCCEEDED", body
    assert body["output"]["sourceKind"] == "TEXT"
    assert body["output"]["charCount"] == len(JD_TEXT)
