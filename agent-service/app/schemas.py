"""数据结构（与 BFF 的 AgentInvokeResult 字段一一对应）。"""

from __future__ import annotations

from enum import Enum
from typing import Any

from pydantic import BaseModel, Field


class BizType(str, Enum):
    """业务类型，取值必须与 BFF 侧 agent_runs.biz_type 约束一致。"""

    RESUME_PARSE = "RESUME_PARSE"
    RESUME_QUESTION = "RESUME_QUESTION"
    DECIDE = "DECIDE"
    DECIDE_PREVIEW = "DECIDE_PREVIEW"
    INTERVIEW = "INTERVIEW"
    RAG_SEARCH = "RAG_SEARCH"


class AgentInvokeRequest(BaseModel):
    run_id: str = Field(alias="runId")
    biz_type: BizType = Field(alias="bizType")
    biz_id: int | None = Field(default=None, alias="bizId")
    user_id: int | None = Field(default=None, alias="userId")
    stage: str | None = None
    spec_hash: str | None = Field(default=None, alias="specHash")
    payload: dict[str, Any] = Field(default_factory=dict)

    model_config = {"populate_by_name": True}


class PipelineStep(BaseModel):
    """评测流水线的单步状态，前端据此做"分步展示、可观测"。"""

    name: str
    status: str
    detail: str = ""
    elapsedMs: int = 0


class AgentInvokeResponse(BaseModel):
    runId: str
    status: str
    output: dict[str, Any] | None = None
    errorCode: str | None = None
    errorMsg: str | None = None
    promptTokens: int = 0
    outputTokens: int = 0
    costCredit: int | None = None
    latencyMs: int = 0


class Usage(BaseModel):
    prompt_tokens: int = 0
    output_tokens: int = 0

    def merge(self, other: "Usage") -> None:
        self.prompt_tokens += other.prompt_tokens
        self.output_tokens += other.output_tokens


class AgentOutcome(BaseModel):
    """单个 agent 的执行结果。"""

    output: dict[str, Any]
    steps: list[PipelineStep] = Field(default_factory=list)
    usage: Usage = Field(default_factory=Usage)
    model: str = ""
    prompt_version: str = ""
    mock: bool = False
