"""知识库检索 agent：RAG_SEARCH。

检索主路在 BFF 侧（pgvector + PostgreSQL FTS + RRF 融合，见 HybridRetriever），
agent 侧提供 **search_documents 检索能力**（Agent RetrievalPort 的适配实现）：
- 对 BFF 传入的候选片段（payload.documents）按 RetrievalProfile 权重做二段融合重排，
  避免"检索结果顺序即结论"；
- 语料为空或排序异常时返回 degraded 标记，而不是编造答案；
- 拼装提示词前经 context 预算裁剪（slice_recent_texts），超预算如实降级。
"""

from __future__ import annotations

from dataclasses import dataclass

from ..core.context_snapshot import estimate_tokens, slice_recent_texts
from ..core.errors import PayloadInvalid
from ..core.prompts import PROMPT_VERSION, RAG_ANSWER_PROMPT, SYSTEM_BASE
from ..llm.client import LlmMessage, task_marker
from ..schemas import AgentOutcome, Usage
from .registry import AgentContext, StepRecorder
from .skills import extract_skills

RRF_K = 60
"""compose 提示词的 token 预算：超预算裁掉最旧片段并标记 degraded。"""
COMPOSE_TOKEN_BUDGET = 6000


@dataclass(frozen=True)
class RetrievalProfile:
    """语料级检索画像：多路权重 + RRF 常数（与 BFF HybridRetriever 同口径）。"""

    w_fts: float = 1.0
    w_trgm: float = 0.8
    w_vector: float = 1.2
    rrf_k: int = RRF_K
    top_k: int = 5

    @classmethod
    def from_payload(cls, payload: dict | None) -> "RetrievalProfile":
        data = payload or {}
        try:
            return cls(
                w_fts=float(data.get("wFts", 1.0)),
                w_trgm=float(data.get("wTrgm", 0.8)),
                w_vector=float(data.get("wVector", 1.2)),
                rrf_k=max(1, int(data.get("rrfK", RRF_K))),
                top_k=max(1, int(data.get("topK", 5))),
            )
        except (TypeError, ValueError):
            return cls()


async def search_documents(
    query: str,
    documents: list,
    top_k: int = 5,
    profile: dict | None = None,
) -> dict:
    """search_documents 检索能力（RetrievalPort 适配实现）。

    两路 RRF 融合：A 路 = agent 侧词项召回分排序（lexical）；
    B 路 = BFF 已融合的传入顺序（编码 FTS+trigram+向量三路权重，用 RetrievalProfile
    的 w_fts+w_trgm+w_vector 作为整路权重）。返回 hits 与 degraded 标记。
    """
    p = RetrievalProfile.from_payload({**(profile or {}), "topK": top_k})
    terms = _terms(query)
    fused: dict[str, dict] = {}
    if not terms or not documents:
        return {"hits": [], "degraded": not documents,
                "degradedReason": None if documents else "候选片段为空（语料缺失）"}

    # A 路：词项召回
    lexical = _rerank(terms, documents)
    for rank, item in enumerate(lexical, start=1):
        key = str(item.get("documentId"))
        entry = fused.setdefault(key, {**item, "_score": 0.0})
        entry["_score"] += 1.0 / (p.rrf_k + rank)

    # B 路：BFF 检索顺序（已按 RetrievalProfile 融合三路），整路权重 = 三路权重之和
    bff_weight = p.w_fts + p.w_trgm + p.w_vector
    for rank, raw in enumerate([d for d in documents if isinstance(d, dict)], start=1):
        key = str(raw.get("documentId") or raw.get("id") or "")
        if not key:
            continue
        entry = fused.setdefault(key, {
            "documentId": key,
            "title": str(raw.get("title") or ""),
            "content": str(raw.get("content") or ""),
            "matchedTerms": [],
            "coverage": 0.0,
            "_score": 0.0,
        })
        entry["_score"] += bff_weight / (p.rrf_k + rank)

    ordered = sorted(fused.values(), key=lambda item: item["_score"], reverse=True)[:top_k]
    hits = []
    for item in ordered:
        item.pop("_score", None)
        hits.append(item)
    return {"hits": hits, "degraded": False, "degradedReason": None}


async def handle(ctx: AgentContext) -> AgentOutcome:
    payload = ctx.payload
    query = str(payload.get("query") or "").strip()
    if not query:
        raise PayloadInvalid("RAG_SEARCH 需要 payload.query")
    documents = payload.get("documents") or []
    top_k = int(payload.get("topK") or 5)
    profile = RetrievalProfile.from_payload({**(payload.get("profile") or {}), "topK": top_k})

    recorder = StepRecorder(ctx.emit)
    started = await recorder.start("prepare_query")
    query_terms = _terms(query)
    await recorder.done("prepare_query", f"查询词 {len(query_terms)} 个，候选文档 {len(documents)} 篇", started)

    started = await recorder.start("hybrid_rerank")
    try:
        result = await search_documents(query, documents, top_k, payload.get("profile"))
        ranked = result["hits"]
        degraded = result["degraded"]
        degraded_reason = result["degradedReason"]
    except Exception as exc:  # noqa: BLE001 - 检索能力异常不炸主流程，降级为空结果
        ranked, degraded, degraded_reason = [], True, f"search_documents 异常：{exc}"
    ranked = ranked[:top_k]
    await recorder.done("hybrid_rerank", f"融合排序后取前 {len(ranked)} 篇", started)

    started = await recorder.start("compose_answer")
    if ranked:
        snippets = [f"[{item['documentId']}] {item['title']}\n{item['content'][:1200]}" for item in ranked]
        # token 预算裁剪：从最近（最相关）的片段往前保留，超出即降级
        kept = slice_recent_texts(snippets, COMPOSE_TOKEN_BUDGET - estimate_tokens(query))
        if len(kept) < len(snippets):
            degraded = True
            degraded_reason = (degraded_reason or "") + f" 拼装超预算，裁剪 {len(snippets) - len(kept)} 个片段"
        messages = [
            LlmMessage(role="system", content=RAG_ANSWER_PROMPT.format(system=SYSTEM_BASE)),
            LlmMessage(role="user", content=(
                f"{task_marker('RAG_ANSWER')}\n问题：{query}\n\n" + "\n\n".join(kept)
            )),
        ]
        generated = await ctx.llm.chat(messages)
        answer = generated.text
        usage = Usage(prompt_tokens=generated.prompt_tokens, output_tokens=generated.output_tokens)
        model = generated.model
    else:
        answer = "未检索到可用文档片段，无法回答。请先向知识库导入资料，或检查筛选条件。"
        usage = Usage()
        model = "retrieval-local"
    await recorder.done("compose_answer", "已基于检索片段作答", started)

    output = {
        "query": query,
        "topK": top_k,
        "hits": ranked,
        "answer": answer,
        "recallGap": not ranked,
        "degraded": degraded,
        "degradedReason": degraded_reason,
        "steps": [step.model_dump() for step in recorder.steps],
    }
    return AgentOutcome(output=output, steps=recorder.steps, usage=usage, model=model,
                        prompt_version=PROMPT_VERSION, mock=model == "deterministic-mock")


def _terms(query: str) -> list[str]:
    """中文按 2-gram 切分 + 英文单词，够用且不引入分词依赖。"""
    words = [token for token in query.replace("，", " ").replace(",", " ").split() if token]
    terms: list[str] = []
    for word in words:
        lowered = word.lower()
        if lowered.isascii():
            terms.append(lowered)
        else:
            terms.extend(word[i:i + 2] for i in range(max(1, len(word) - 1)))
    # 技能词权重更高，单独提出来参与打分
    terms.extend(skill.lower() for skill in extract_skills(query))
    return list(dict.fromkeys(terms))


def _rerank(terms: list[str], documents: list) -> list[dict]:
    """关键词召回分 + 覆盖率加权的简化 RRF 融合。

    公式与完整的向量/全文双通道 RRF 一致（1/(k+rank) 累加），
    这样后续接入 pgvector 时，只需把两路候选换成真实召回结果，融合逻辑无需改动。
    """
    scored: list[dict] = []
    for index, raw in enumerate(documents):
        if not isinstance(raw, dict):
            continue
        content = str(raw.get("content") or "")
        title = str(raw.get("title") or "")
        haystack = f"{title}\n{content}".lower()
        if not haystack.strip():
            continue
        hits = [term for term in terms if term and term in haystack]
        if not hits:
            continue
        coverage = len(hits) / len(terms) if terms else 0.0
        keyword_score = sum(haystack.count(term) for term in hits)
        rank_score = 1.0 / (RRF_K + index + 1)
        final = keyword_score * (0.5 + coverage) + rank_score * 100
        scored.append({
            "documentId": raw.get("documentId") or raw.get("id"),
            "title": title,
            "content": content,
            "score": round(final, 4),
            "matchedTerms": hits[:8],
            "coverage": round(coverage, 3),
        })
    scored.sort(key=lambda item: item["score"], reverse=True)
    return scored


__all__ = ["handle", "search_documents", "RetrievalProfile"]
