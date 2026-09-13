"""AgentRuntime + AgentProgramRegistry：内核执行编排。"""

from __future__ import annotations

import time
from typing import Protocol

from ..core.errors import AgentError
from .events import EVENT_RUN_FAILED, EVENT_RUN_STARTED, EVENT_RUN_SUCCEEDED, KernelEvent, KernelEventBus
from .spec import RunSpec


class AgentProgram(Protocol):
    """业务流水线的统一形态：声明 spec，实现 run。"""

    @property
    def spec(self) -> RunSpec:
        ...

    async def run(self, ctx) -> object:
        ...


class AgentProgramRegistry:
    """biz_type -> program 唯一查找入口。

    同一 biz_type 允许被多个 program 覆盖（如 DECIDE 与 DECIDE_PREVIEW 共用
    decide 流水线），注册时按 spec_version 区分；dispatch 只取最新注册的那个。
    """

    def __init__(self) -> None:
        self._programs: dict[str, AgentProgram] = {}

    def register(self, program: AgentProgram) -> None:
        self._programs[program.spec.biz_type] = program

    def get(self, biz_type: str) -> AgentProgram | None:
        return self._programs.get(biz_type)

    def all_specs(self) -> list[RunSpec]:
        return [p.spec for p in self._programs.values()]


class AgentRuntime:
    """统一编排：事件发射 + 异常包装 + 计时。

    业务 handler 只写"流水线本身"，不再各自实现"运行前发什么、失败算什么"。
    """

    def __init__(self, events: KernelEventBus | None = None) -> None:
        self.events = events or KernelEventBus()

    async def run(self, program: AgentProgram, ctx) -> object:
        spec = program.spec
        run_id = getattr(getattr(ctx, "request", None), "run_id", None)
        started = time.perf_counter()
        self.events.publish(KernelEvent(
            type=EVENT_RUN_STARTED, biz_type=spec.biz_type, spec_version=spec.spec_version,
            run_id=run_id, payload={"stages": list(spec.stages)},
        ))
        try:
            outcome = await program.run(ctx)
        except AgentError as error:
            self.events.publish(KernelEvent(
                type=EVENT_RUN_FAILED, biz_type=spec.biz_type, spec_version=spec.spec_version,
                run_id=run_id, payload={
                    "errorCode": error.code,
                    "message": str(error),
                    "elapsedMs": int((time.perf_counter() - started) * 1000),
                },
            ))
            raise
        except Exception as error:  # noqa: BLE001 - 统一包装为内核级失败
            self.events.publish(KernelEvent(
                type=EVENT_RUN_FAILED, biz_type=spec.biz_type, spec_version=spec.spec_version,
                run_id=run_id, payload={
                    "errorCode": "AGENT_STAGE_FAILED",
                    "message": str(error),
                    "elapsedMs": int((time.perf_counter() - started) * 1000),
                },
            ))
            raise AgentError("AGENT_STAGE_FAILED", f"流水线执行失败：{error}") from error
        self.events.publish(KernelEvent(
            type=EVENT_RUN_SUCCEEDED, biz_type=spec.biz_type, spec_version=spec.spec_version,
            run_id=run_id, payload={
                "elapsedMs": int((time.perf_counter() - started) * 1000),
                "model": getattr(outcome, "model", None),
            },
        ))
        return outcome
