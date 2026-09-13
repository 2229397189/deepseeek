"""Agent*Port：内核定义的依赖接口。业务只依赖接口，不依赖具体传输实现。"""

from __future__ import annotations

from typing import Protocol, runtime_checkable

from ..llm.client import LlmMessage, LlmResult


@runtime_checkable
class LlmPort(Protocol):
    """大模型调用口。app.llm.client.LlmClient 天然满足该协议（结构化子类型）。"""

    async def chat(self, messages: list[LlmMessage]) -> LlmResult:
        ...


@runtime_checkable
class RetrievalPort(Protocol):
    """检索口：search_documents 能力（rag 模块适配实现）。

    返回 {"hits": [...], "degraded": bool, "degradedReason": str|None}；
    后续接入真正的向量召回（pgvector）时替换实现，业务流水线零改动。
    """

    async def search_documents(
        self,
        query: str,
        documents: list,
        top_k: int,
        profile: dict | None = None,
    ) -> dict:
        ...
