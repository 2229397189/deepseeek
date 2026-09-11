"""知识库检索 agent：RAG_SEARCH。

真正的向量检索与全文检索在 BFF 侧完成（pgvector + PostgreSQL FTS + RRF 融合），
agent 侧负责"融合后的重排与回答生成"这两件事：
- 候选片段由 BFF 传入（payload.documents），本 agent 做兜底重排，避免"检索结果顺序即结论"；
- 若 BFF 尚未传入候选片段，明确返回缺口而不是编造答案，防止前端把空检索当成有效结论。
"""

from __future__ import annotations

from ..core.errors import PayloadInvalid
from ..core.prompts import PROMPT_VERSION, RAG_ANSWER_PROMPT, SYSTEM_BASE
from ..llm.client import LlmMessage, task_marker
from ..schemas import AgentOutcome, Usage
from .registry import AgentContext, StepRecorder
from .skills import extract_skills

RRF_K = 60


async def handle(ctx: AgentContext) -> AgentOutcome:
    payload = ctx.payload
    query = str(payload.get("query") or "").strip()
    if not query:
        raise PayloadInvalid("RAG_SEARCH 需要 payload.query")
    documents = payload.get("documents") or []
    top_k = int(payload.get("topK") or 5)

    recorder = StepRecorder(ctx.emit)
    started = await recorder.start("prepare_query")
    query_terms = _terms(query)
    await recorder.done("prepare_query", f"查询词 {len(query_terms)} 个，候选文档 {len(documents)} 篇", started)

    started = await recorder.start("hybrid_rerank")
    ranked = _rerank(query_terms, documents)[:top_k]
    await recorder.done("hybrid_rerank", f"融合排序后取前 {len(ranked)} 篇", started)

    started = await recorder.start("compose_answer")
    if ranked:
        messages = [
            LlmMessage(role="system", content=RAG_ANSWER_PROMPT.format(system=SYSTEM_BASE)),
            LlmMessage(role="user", content=(
                f"{task_marker('RAG_ANSWER')}\n问题：{query}\n\n"
                + "\n\n".join(f"[{item['documentId']}] {item['title']}\n{item['content'][:1200]}"
                              for item in ranked)
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


__all__ = ["handle"]
