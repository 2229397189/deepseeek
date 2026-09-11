"""模型能力层：OpenAI 兼容实现 + 确定性兜底实现。"""

from .client import (  # noqa: F401
    DeterministicLlm,
    LlmClient,
    LlmMessage,
    LlmResult,
    OpenAiCompatibleLlm,
    build_llm,
    task_marker,
)
