"""错误定义。

错误码与 BFF 侧的错误归一化保持同一套词汇（TIMEOUT / RATE_LIMIT / UPSTREAM_5XX…），
业务级失败统一用 AGENT_STAGE_FAILED，避免下游需要认识"中文报错"。
"""

from __future__ import annotations


class AgentError(Exception):
    """可预期失败：会以 status=FAILED + errorCode 返回给 BFF。"""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


class LlmUnavailable(AgentError):
    """模型侧不可用，属于可重试错误。"""

    def __init__(self, message: str, code: str = "UPSTREAM_5XX") -> None:
        super().__init__(code, message)


class PayloadInvalid(AgentError):
    """入参不满足该 agent 的前置条件（不可重试）。

    code 可细化到具体原因（如 UNSUPPORTED_FILE_TYPE / EMPTY_TEXT），默认仍为
    AGENT_STAGE_FAILED，保证既有调用方与 BFF 的错误归一化口径不变。
    """

    def __init__(self, message: str, code: str = "AGENT_STAGE_FAILED") -> None:
        super().__init__(code, message)
