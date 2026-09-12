"""上下文快照切片单测。

覆盖四类必须成立的行为：
1. 预算充足 → 原样返回，不降级；
2. 预算不足 → 保住头部（system）+ 最近若干轮，degraded 为 True；
3. dropped 如实反映被丢弃条数（不是恒 0 的空实现）；
4. 空输入 / 非正预算 → 安全退化，不抛异常。
"""

from __future__ import annotations

from app.core.context_snapshot import (ContextSnapshot, estimate_tokens,
                                       slice_messages, slice_recent_texts)
from app.llm.client import LlmMessage


def _msg(role: str, text: str) -> LlmMessage:
    return LlmMessage(role=role, content=text)


def _turn(i: int, chars: int = 40) -> LlmMessage:
    """构造一轮固定长度的问答消息：chars 个字符 ≈ chars//2 token。"""
    return _msg("user" if i % 2 else "assistant", f"{i}" * chars)


def test_estimate_tokens_matches_llm_client_heuristic():
    assert estimate_tokens("") == 0
    assert estimate_tokens("ab") == 1          # max(1, 2//2)
    assert estimate_tokens("a" * 101) == 50    # 折中口径：长度 // 2


def test_budget_enough_keeps_everything():
    messages = [_msg("system", "S" * 20)] + [_turn(i) for i in range(1, 5)]
    snap = slice_messages(messages, token_budget=10_000)

    assert len(snap.messages) == len(messages)
    assert snap.degraded is False
    assert snap.dropped == 0
    assert snap.total_messages == len(messages)
    assert snap.token_used > 0


def test_over_budget_keeps_head_and_recent_turns():
    """预算只够 system + 最近两轮时，头部的 system 必须还在，且留下的是最新的两轮。"""
    system = _msg("system", "S" * 20)          # 10 token
    turns = [_turn(i, chars=100) for i in range(1, 6)]  # 每轮 50 token
    messages = [system] + turns

    # 10(system) + 2*50 = 110 刚好放得下最近 2 轮，第三轮会超预算
    snap = slice_messages(messages, token_budget=110, keep_head=1)

    assert snap.messages[0] is system, "system 是无条件保留的任务契约"
    assert snap.degraded is True
    assert snap.dropped == 3, "5 条历史里丢了 3 条中间轮次"
    assert snap.total_messages == 6
    # 保留的是最后两轮（turns[-2], turns[-1]），顺序仍按时间正序
    assert snap.messages[1:] == turns[-2:]
    assert snap.token_used <= 110


def test_dropped_is_not_a_noop():
    """回归用：dropped 曾写成恒返回 0 的空实现，这里钉死它必须随切片变化。"""
    messages = [_msg("system", "S" * 20)] + [_turn(i, chars=200) for i in range(1, 4)]
    wide = slice_messages(messages, token_budget=100_000)
    narrow = slice_messages(messages, token_budget=10)

    assert wide.dropped == 0
    assert narrow.dropped > 0, "预算收紧后必须有消息被丢弃"
    assert narrow.dropped == len(messages) - len(narrow.messages)


def test_non_positive_budget_degrades_to_head_only():
    messages = [_msg("system", "S" * 20)] + [_turn(i) for i in range(1, 4)]
    snap = slice_messages(messages, token_budget=0)

    assert snap.messages == [messages[0]]
    assert snap.degraded is True
    assert snap.dropped == len(messages) - 1
    assert snap.token_budget == 0


def test_empty_input_is_safe():
    snap = slice_messages([], token_budget=100)
    assert snap.messages == []
    assert snap.token_used == 0
    assert snap.degraded is False
    assert snap.dropped == 0


def test_keep_head_clamped_to_length():
    """keep_head 大于消息总数时不能越界。"""
    snap = slice_messages([_msg("system", "S")], token_budget=100, keep_head=99)
    assert len(snap.messages) == 1
    assert snap.degraded is False


def test_snapshot_defaults_total_messages_to_len():
    """调用方未显式给 total_messages 时，退化为「没丢东西」而不是负数。"""
    snap = ContextSnapshot(messages=[_msg("user", "hi")])
    assert snap.total_messages == 1
    assert snap.dropped == 0


def test_slice_recent_texts_keeps_order_and_fits_budget():
    texts = [f"{i}" * 100 for i in range(6)]     # 每条 50 token

    assert slice_recent_texts(texts, 10_000) == texts, "预算充足应全量保留且保持原序"
    assert slice_recent_texts([], 100) == []
    assert slice_recent_texts(texts, 0) == []

    kept = slice_recent_texts(texts, 120)        # 只放得下最近 2 条
    assert kept == texts[-2:]
    assert sum(estimate_tokens(t) for t in kept) <= 120
