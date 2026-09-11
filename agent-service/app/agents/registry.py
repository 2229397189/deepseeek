"""Agent 注册表与执行上下文。

一个 bizType 对应一个 handler，handler 内部自主编排流水线步骤。
BFF 侧只认 bizType，不关心内部有几个阶段——这正是把评估逻辑放在 Python 层的价值：
提示词、流水线、评分口径可以独立迭代，不必动 Java 侧。
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable

from ..core.errors import AgentError
from ..llm.client import LlmClient
from ..schemas import AgentInvokeRequest, AgentOutcome, BizType, PipelineStep

EmitFunc = Callable[[dict[str, Any]], Awaitable[None]]


@dataclass
class AgentContext:
    request: AgentInvokeRequest
    llm: LlmClient
    emit: EmitFunc | None = None

    @property
    def payload(self) -> dict[str, Any]:
        return self.request.payload or {}


@dataclass
class StepRecorder:
    """流水线步骤记录器。

    同一个步骤只有一条记录：``start`` 时置为 RUNNING，``done`` 时就地更新为终态。
    这样同步返回的 ``steps`` 是"每步最终状态"，而不是把 RUNNING 与 DONE 各记一遍——
    前端渲染进度条时最怕同一个阶段出现两行。
    """

    emit: EmitFunc | None = None
    steps: list[PipelineStep] = field(default_factory=list)
    _index: dict[str, int] = field(default_factory=dict)

    async def start(self, name: str) -> float:
        step = self._resolve(name)
        step.status = "RUNNING"
        await self._emit(step)
        return time.perf_counter()

    async def done(self, name: str, detail: str = "", started: float | None = None) -> None:
        step = self._resolve(name)
        step.status = "DONE"
        step.detail = detail
        step.elapsedMs = _elapsed(started)
        await self._emit(step)

    async def failed(self, name: str, detail: str, started: float | None = None) -> None:
        step = self._resolve(name)
        step.status = "FAILED"
        step.detail = detail
        step.elapsedMs = _elapsed(started)
        await self._emit(step)

    def _resolve(self, name: str) -> PipelineStep:
        if name in self._index:
            return self.steps[self._index[name]]
        step = PipelineStep(name=name, status="RUNNING")
        self._index[name] = len(self.steps)
        self.steps.append(step)
        return step

    async def _emit(self, step: PipelineStep) -> None:
        if self.emit is not None:
            await self.emit({"type": "step", "step": step.model_dump()})


def _elapsed(started: float | None) -> int:
    return int((time.perf_counter() - started) * 1000) if started else 0


async def dispatch(
    request: AgentInvokeRequest,
    llm: LlmClient,
    emit: EmitFunc | None = None,
) -> AgentOutcome:
    handler = _handlers().get(request.biz_type)
    if handler is None:
        raise AgentError("AGENT_STAGE_FAILED", f"未注册的业务类型：{request.biz_type}")
    return await handler(AgentContext(request=request, llm=llm, emit=emit))


def _handlers() -> dict[BizType, Callable[[AgentContext], Awaitable[AgentOutcome]]]:
    # 延迟导入，避免模块循环依赖
    from .decide import handle as decide_handle
    from .interview import handle as interview_handle
    from .rag import handle as rag_handle
    from .resume import handle as resume_handle

    return {
        BizType.DECIDE: decide_handle,
        BizType.DECIDE_PREVIEW: decide_handle,
        BizType.INTERVIEW: interview_handle,
        BizType.RESUME_PARSE: resume_handle,
        BizType.RESUME_QUESTION: resume_handle,
        BizType.RAG_SEARCH: rag_handle,
    }
