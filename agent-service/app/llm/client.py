"""模型能力抽象层。

两种实现共用同一接口：
- ``OpenAiCompatibleLlm``：走 /chat/completions，适配 DeepSeek 等 OpenAI 兼容服务；
- ``DeterministicLlm``：无 key / 强制 mock 时使用，输出**确定且可解释**的文本，
  保证同一输入永远得到同一结果，这样单测与压测才有稳定基线。

注意：把结构化信息（技能抽取、打分）交给确定性逻辑，把叙述性内容（建议、评语、出题）
交给模型，是刻意的分工——分数必须可复现、可解释，不能被模型的随机性带偏。
"""

from __future__ import annotations

import hashlib
import logging
import re
from dataclasses import dataclass, field
from typing import Any, Protocol

import httpx

from ..config import Settings
from ..core.errors import LlmUnavailable

logger = logging.getLogger(__name__)


@dataclass
class LlmMessage:
    role: str
    content: str


@dataclass
class LlmResult:
    text: str
    prompt_tokens: int = 0
    output_tokens: int = 0
    model: str = ""
    raw: dict[str, Any] = field(default_factory=dict)


class LlmClient(Protocol):
    name: str

    async def chat(
        self,
        messages: list[LlmMessage],
        *,
        temperature: float = 0.2,
        json_mode: bool = False,
    ) -> LlmResult: ...


def _rough_tokens(text: str) -> int:
    """粗略 token 估算：中文按 1 字≈1 token、英文按 4 字符≈1 token 折中。"""
    if not text:
        return 0
    return max(1, len(text) // 2)


class DeterministicLlm:
    """确定性模型：把"提示词特征 + 任务标签"哈希成稳定输出。"""

    name = "deterministic-mock"

    async def chat(
        self,
        messages: list[LlmMessage],
        *,
        temperature: float = 0.2,
        json_mode: bool = False,
    ) -> LlmResult:
        task = _extract_task(messages)
        prompt_text = "\n".join(m.content for m in messages)
        text = _render_mock(task, prompt_text)
        return LlmResult(
            text=text,
            prompt_tokens=_rough_tokens(prompt_text),
            output_tokens=_rough_tokens(text),
            model=self.name,
            raw={"task": task},
        )


class OpenAiCompatibleLlm:
    """OpenAI 兼容实现，适配 DeepSeek / 通义 / 自建网关。"""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self.name = settings.llm_model
        self._client = httpx.AsyncClient(
            base_url=settings.llm_base_url.rstrip("/"),
            timeout=settings.llm_timeout_s,
            headers={"Authorization": f"Bearer {settings.llm_api_key}"},
        )

    async def aclose(self) -> None:
        await self._client.aclose()

    async def chat(
        self,
        messages: list[LlmMessage],
        *,
        temperature: float = 0.2,
        json_mode: bool = False,
    ) -> LlmResult:
        body: dict[str, Any] = {
            "model": self._settings.llm_model,
            "messages": [{"role": m.role, "content": m.content} for m in messages],
            "temperature": temperature,
        }
        if json_mode:
            body["response_format"] = {"type": "json_object"}
        try:
            resp = await self._client.post("/chat/completions", json=body)
        except httpx.TimeoutException as exc:  # 交给 BFF 侧按 TIMEOUT 重试
            raise LlmUnavailable("模型调用超时", code="TIMEOUT") from exc
        except httpx.RequestError as exc:
            raise LlmUnavailable(f"模型连接失败：{exc}", code="CONNECT_ERROR") from exc

        if resp.status_code == 429:
            raise LlmUnavailable("模型侧限流", code="RATE_LIMIT")
        if resp.status_code >= 500:
            raise LlmUnavailable(f"模型侧 {resp.status_code}", code="UPSTREAM_5XX")
        if resp.status_code >= 400:
            raise LlmUnavailable(f"模型侧 {resp.status_code}：{resp.text[:200]}", code="UPSTREAM_4XX")

        data = resp.json()
        choices = data.get("choices") or []
        text = (choices[0].get("message", {}).get("content") or "") if choices else ""
        usage = data.get("usage") or {}
        return LlmResult(
            text=text,
            prompt_tokens=int(usage.get("prompt_tokens") or 0),
            output_tokens=int(usage.get("completion_tokens") or 0),
            model=data.get("model") or self._settings.llm_model,
            raw=data,
        )


def build_llm(settings: Settings) -> LlmClient:
    if settings.use_mock:
        logger.warning("未配置 LLM key 或已强制 mock，使用确定性模型（结果可复现，仅供联调）")
        return DeterministicLlm()
    return OpenAiCompatibleLlm(settings)


# ---------------------------------------------------------------------------
# 确定性输出
# ---------------------------------------------------------------------------

_TASK_KEY = "TASK:"


def task_marker(task: str) -> str:
    """在 system prompt 里标记任务类型，供确定性模型路由到对应文案。"""
    return f"{_TASK_KEY}{task}"


def _extract_task(messages: list[LlmMessage]) -> str:
    for message in messages:
        idx = message.content.find(_TASK_KEY)
        if idx >= 0:
            rest = message.content[idx + len(_TASK_KEY):]
            return rest.split("\n", 1)[0].strip()
    return "GENERIC"


def _render_mock(task: str, prompt_text: str = "") -> str:
    """渲染确定性输出。

    DECIDE_ADVICE 这类"叙述必须服从结论"的任务，要读回提示词里的结论再说话：
    如果 mock 无论什么结论都回"建议推进申请"，就会出现"结论 REJECT、建议 APPLY"
    的自相矛盾输出——这种不一致一旦进了演示或截图，比没有建议更伤信任。
    """
    if task == "DECIDE_ADVICE":
        return _render_decide_advice(prompt_text)

    digest = hashlib.sha1(task.encode("utf-8")).hexdigest()[:6]
    bank = {
        "INTERVIEW_QUESTION": "请结合你最近的一个项目，说明你在其中承担的核心职责，"
                              "以及当时遇到的最棘手的技术问题是如何定位并解决的？",
        "INTERVIEW_FOLLOWUP": "你提到的方案在高并发场景下会有什么隐患？如果流量再翻十倍，"
                              "你会优先改动哪一层，为什么？",
        "INTERVIEW_REPORT": "整体表达清晰、技术细节可追溯，但在系统设计层面缺少量化的取舍依据；"
                            "建议补充容量评估与压测结论。",
        "RESUME_POLISH": "建议改为结果导向表述：补充规模、增量与耗时的量化数据，"
                         "并明确你在其中独立负责的边界。",
        "RAG_ANSWER": "根据检索到的资料，该问题的关键结论已按相关度排序给出，可展开对应片段查看出处。",
    }
    return bank.get(task, f"[deterministic:{task}:{digest}] 已按输入生成稳定输出。")


_ADVICE_BANK = {
    "APPLY": "建议推进申请：核心技能覆盖良好，但需在面试前补齐岗位明确要求的技术栈，"
             "并准备一段与该岗位职责强相关的项目复盘。",
    "HOLD": "可争取，但先补齐关键技能：岗位要求中仍有未覆盖的能力，"
            "建议先把补强清单做完再投递，避免在筛选环节被追问时失分。",
    "REJECT": "暂不建议直接投递：岗位要求的核心技能覆盖不足，盲目投递会消耗机会，"
              "建议先按补强清单准备 2~3 周，再重新评估匹配度。",
}


def _render_decide_advice(prompt_text: str) -> str:
    match = re.search(r"结论[：:]\s*(APPLY|HOLD|REJECT)", prompt_text)
    conclusion = match.group(1) if match else "HOLD"
    return _ADVICE_BANK[conclusion]
