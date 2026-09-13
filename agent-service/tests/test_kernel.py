"""Agent Kernel 单测：注册表查找、Runtime 事件序列、search_documents 检索能力。"""

from __future__ import annotations

import asyncio

import pytest

from app.agents.programs import build_registry
from app.agents.rag import search_documents
from app.core.errors import AgentError
from app.kernel import AgentProgramRegistry, AgentRuntime, KernelEventBus, RunSpec


def test_registry_按bizType查找_未注册返回None():
    registry = AgentProgramRegistry()

    class Dummy:
        spec = RunSpec(biz_type="DECIDE", spec_version="t1")

        async def run(self, ctx):
            return None

    program = Dummy()
    registry.register(program)
    assert registry.get("DECIDE") is program
    assert registry.get("NOPE") is None
    assert [s.biz_type for s in registry.all_specs()] == ["DECIDE"]


def test_default_registry覆盖全部六类业务():
    registry = build_registry()
    for biz in ("DECIDE", "DECIDE_PREVIEW", "INTERVIEW", "RESUME_PARSE", "RESUME_QUESTION", "RAG_SEARCH"):
        assert registry.get(biz) is not None, f"缺少业务注册：{biz}"


class _Program:
    def __init__(self, biz_type: str, behavior: str) -> None:
        self.spec = RunSpec(biz_type=biz_type, spec_version="t1")
        self._behavior = behavior

    async def run(self, ctx):
        if self._behavior == "ok":
            return "ok"
        if self._behavior == "agent_error":
            raise AgentError("AGENT_STAGE_FAILED", "boom")
        raise ValueError("unexpected")


def test_runtime_成功路径发射STARTED与SUCCEEDED事件():
    bus = KernelEventBus()
    runtime = AgentRuntime(events=bus)
    outcome = asyncio.run(runtime.run(_Program("DECIDE", "ok"), None))
    assert outcome == "ok"
    types = [event.type for event in bus.history]
    assert types == ["RUN_STARTED", "RUN_SUCCEEDED"]
    assert bus.history[0].biz_type == "DECIDE"


def test_runtime_业务异常发射RUN_FAILED并原样上抛():
    bus = KernelEventBus()
    runtime = AgentRuntime(events=bus)
    with pytest.raises(AgentError):
        asyncio.run(runtime.run(_Program("INTERVIEW", "agent_error"), None))
    types = [event.type for event in bus.history]
    assert types == ["RUN_STARTED", "RUN_FAILED"]
    assert bus.history[-1].payload["errorCode"] == "AGENT_STAGE_FAILED"


def test_runtime_未知异常包装为内核失败():
    bus = KernelEventBus()
    runtime = AgentRuntime(events=bus)
    with pytest.raises(AgentError):
        asyncio.run(runtime.run(_Program("RAG_SEARCH", "unexpected"), None))
    assert bus.history[-1].type == "RUN_FAILED"


def test_search_documents_双路融合与空语料降级():
    documents = [
        {"documentId": "d1", "title": "Java 并发", "content": "synchronized 与 ReentrantLock 的区别"},
        {"documentId": "d2", "title": "Redis 缓存", "content": "缓存穿透与布隆过滤器"},
    ]
    result = asyncio.run(search_documents("Java 并发", documents, top_k=2))
    assert result["degraded"] is False
    assert result["hits"], "命中文档不应为空"
    assert result["hits"][0]["documentId"] == "d1"

    empty = asyncio.run(search_documents("Java", [], top_k=2))
    assert empty["hits"] == []
    assert empty["degraded"] is True
    assert empty["degradedReason"]


def test_search_documents_topK截断与profile透传():
    documents = [{"documentId": f"d{i}", "title": f"t{i}", "content": f"内容 {i}"} for i in range(5)]
    profile = {"wFts": 1.0, "wTrgm": 0.8, "wVector": 1.2, "rrfK": 60, "topK": 3}
    result = asyncio.run(search_documents("内容", documents, top_k=3, profile=profile))
    assert len(result["hits"]) <= 3
