"""上下文快照切片（P2-27）。

解决的问题：多轮面试 / 决策会把完整对话历史带进提示词，历史越长越容易撞上模型上下文
窗口，表现为调用失败或前情被静默截断。这里的策略是**显式切片**——在调用模型之前就把
上下文压到预算内，并如实报告「是否降级（degraded）」，而不是让服务端悄悄截断。

切片原则：
1. **头部不动**：system / 指令类消息是任务契约，任何情况下优先保留；
2. **保最近**：预算内从最近一条往前尽量多留，因为最近上下文对当前决策最相关；
3. **可观测**：返回 token 用量与 degraded 标记，便于落库与埋点。

token 估算与 ``llm/client.py`` 的启发式保持一致（中文约 1 字 ≈ 0.5 token 的折中口径），
确保「预算判断」和「实际计费口径」不会南辕北辙。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Sequence

from ..llm.client import LlmMessage

__all__ = [
    "ContextSnapshot",
    "estimate_tokens",
    "slice_messages",
    "slice_recent_texts",
]


def estimate_tokens(text: str) -> int:
    """粗略 token 估算：与 llm/client 同口径，空文本记 0。"""
    if not text:
        return 0
    return max(1, len(text) // 2)


@dataclass
class ContextSnapshot:
    """一次切片的结果，便于落库与埋点（对应 agent_context_snapshots 表字段）。"""

    messages: list[LlmMessage] = field(default_factory=list)
    token_budget: int = 0
    token_used: int = 0
    degraded: bool = False
    total_messages: int = 0

    def __post_init__(self) -> None:
        # 调用方未显式给出原始条数时，退化为「没有被丢弃」
        if self.total_messages <= 0:
            self.total_messages = len(self.messages)

    @property
    def dropped(self) -> int:
        """被切片丢弃的消息条数（原始条数 - 保留条数）。"""
        return max(0, self.total_messages - len(self.messages))


def slice_messages(
    messages: Sequence[LlmMessage],
    token_budget: int,
    *,
    keep_head: int = 1,
) -> ContextSnapshot:
    """把消息列表压进 token 预算。

    :param messages: 待切片的消息（按时间正序，首条通常为 system）
    :param token_budget: token 预算，<=0 时退化为「只保留头部」
    :param keep_head: 无条件保留的头部消息条数（默认 1 条 system）
    :return: 切片结果，degraded 为 True 表示有消息被丢弃
    """
    if not messages:
        return ContextSnapshot(messages=[], token_budget=max(0, token_budget),
                               token_used=0, degraded=False, total_messages=0)

    costs = [estimate_tokens(m.content) for m in messages]
    head_count = min(keep_head, len(messages))

    # 头部是任务契约，先无条件计入
    head_cost = sum(costs[:head_count])
    kept_tail: list[int] = []
    used = head_cost

    # 从最近一条往前尽量多留
    for idx in range(len(messages) - 1, head_count - 1, -1):
        cost = costs[idx]
        if used + cost > token_budget:
            break
        used += cost
        kept_tail.append(idx)

    kept_tail.reverse()
    selected = list(range(head_count)) + kept_tail
    selected.sort()

    sliced = [messages[i] for i in selected]
    return ContextSnapshot(
        messages=sliced,
        token_budget=max(0, token_budget),
        token_used=used,
        degraded=len(sliced) < len(messages),
        total_messages=len(messages),
    )


def slice_recent_texts(texts: Sequence[Any], max_tokens: int) -> list[Any]:
    """从末尾（最近）开始保留，累计 token 不超过预算，返回**原顺序**的子集。

    与 :func:`slice_messages` 的区别：这里面向「拼装进单条消息的历史片段」，
    只做取舍不做拼接，因此保留原索引语义（调用方可先编号再切片）。
    """
    if not texts or max_tokens <= 0:
        return []
    kept: list[Any] = []
    used = 0
    for item in reversed(list(texts)):
        cost = estimate_tokens(str(item))
        if used + cost > max_tokens:
            break
        used += cost
        kept.append(item)
    kept.reverse()
    return kept
