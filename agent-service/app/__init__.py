"""agent-service：Chiron 平台的 AI 能力层。

对外只暴露两个端点（invoke / stream），上游 BFF 的统一网关负责限流、去重与计费，
本服务只关心"给定 bizType 与 payload，产出结构化结果"。
"""

from .config import get_settings  # noqa: F401
