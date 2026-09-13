"""业务 Program 注册：把各业务 handler 适配成内核 AgentProgram。

handler 仍是流水线的唯一实现（decide/interview/rag/resume 的 handle 函数），
这里只做一层薄适配：声明 RunSpec + 组合 run，让内核 Runtime 统一编排。
新增业务 = 新写 handler + 在 build_registry 注册一行，内核与其余业务零改动。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Awaitable, Callable

from ..kernel import AgentProgram, AgentProgramRegistry, RunSpec
from ..schemas import BizType
from .registry import AgentContext

Handler = Callable[[AgentContext], Awaitable[object]]


@dataclass
class HandlerProgram(AgentProgram):
    """把「handle(ctx) 函数」包装成内核 Program 的轻适配器。"""

    _spec: RunSpec
    _handler: Handler

    @property
    def spec(self) -> RunSpec:
        return self._spec

    async def run(self, ctx: AgentContext) -> object:
        return await self._handler(ctx)


def build_registry() -> AgentProgramRegistry:
    """构建默认注册表。延迟导入避免模块循环依赖。"""
    from .decide import handle as decide_handle
    from .interview import handle as interview_handle
    from .rag import handle as rag_handle
    from .resume import handle as resume_handle

    registry = AgentProgramRegistry()
    registry.register(HandlerProgram(
        RunSpec(biz_type=BizType.DECIDE.value, stages=("parse_jd", "load_profile", "match_score", "generate_advice"),
                spec_version="jd-match-v1", timeout_s=120, max_retries=1),
        decide_handle))
    registry.register(HandlerProgram(
        RunSpec(biz_type=BizType.DECIDE_PREVIEW.value, stages=("parse_jd", "load_profile", "match_score", "generate_advice"),
                spec_version="jd-preview-v1", timeout_s=120, max_retries=1),
        decide_handle))
    registry.register(HandlerProgram(
        RunSpec(biz_type=BizType.INTERVIEW.value, stages=("plan_questions", "ask_question", "evaluate_answer", "compose_report"),
                spec_version="interview-v1", timeout_s=120, max_retries=1),
        interview_handle))
    registry.register(HandlerProgram(
        RunSpec(biz_type=BizType.RESUME_PARSE.value, stages=("extract_text", "build_profile"),
                spec_version="resume-parse-v1", timeout_s=90, max_retries=1),
        resume_handle))
    registry.register(HandlerProgram(
        RunSpec(biz_type=BizType.RESUME_QUESTION.value, stages=("answer_question",),
                spec_version="resume-question-v1", timeout_s=60, max_retries=1),
        resume_handle))
    registry.register(HandlerProgram(
        RunSpec(biz_type=BizType.RAG_SEARCH.value, stages=("prepare_query", "hybrid_rerank", "compose_answer"),
                spec_version="kb-search-v1", timeout_s=30, max_retries=0),
        rag_handle))
    return registry
