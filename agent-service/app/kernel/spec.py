"""RunSpec：一次 Agent 运行的声明式元信息。"""

from __future__ import annotations

from dataclasses import dataclass

@dataclass(frozen=True)
class RunSpec:
    """业务流水线的"身份证"。

    biz_type 是注册主键（与 BFF 约定一致）；stages 描述该程序覆盖的阶段清单，
    供审计与前端进度条对照；spec_version 随提示词/评分口径演进，纳入 BFF 侧
    input_digest，保证"同一版本口径的结果"才会被 single-flight 回放。
    """

    biz_type: str
    stages: tuple[str, ...] = ()
    spec_version: str = "v1"
    timeout_s: float | None = None
    max_retries: int = 0
