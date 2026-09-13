"""KernelEvent：内核级运行事件（进程内总线，不改对外线格式）。"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any, Callable

EVENT_RUN_STARTED = "RUN_STARTED"
EVENT_RUN_SUCCEEDED = "RUN_SUCCEEDED"
EVENT_RUN_FAILED = "RUN_FAILED"

Sink = Callable[["KernelEvent"], None]


@dataclass(frozen=True)
class KernelEvent:
    """一次运行的生命周期事件。payload 里放耗时 / 错误码等上下文。"""

    type: str
    biz_type: str
    spec_version: str
    run_id: str | None = None
    payload: dict[str, Any] = field(default_factory=dict)
    ts: float = field(default_factory=lambda: time.time())


class KernelEventBus:
    """进程内事件总线：sink 列表可注入（观测系统接入点），默认静默收集。

    事件发布永不抛错——内核事件的消费方有问题时，不能影响业务主流程。
    """

    def __init__(self, sinks: list[Sink] | None = None) -> None:
        self._sinks: list[Sink] = list(sinks or [])
        self._history: list[KernelEvent] = []

    def subscribe(self, sink: Sink) -> None:
        self._sinks.append(sink)

    def publish(self, event: KernelEvent) -> None:
        self._history.append(event)
        for sink in self._sinks:
            try:
                sink(event)
            except Exception:  # noqa: BLE001 - 观测失败不阻断业务
                pass

    @property
    def history(self) -> list[KernelEvent]:
        return list(self._history)
