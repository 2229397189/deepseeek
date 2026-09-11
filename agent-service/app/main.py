"""HTTP 入口。

两个端点：
- ``POST /v1/agent/invoke``：同步调用，返回结构化结果，供 BFF 的统一网关使用；
- ``POST /v1/agent/stream``：SSE 流式，逐步推送流水线阶段，供前端做"分步展示、可观测"。

失败语义统一为 HTTP 200 + status=FAILED + errorCode：
把"业务失败"和"传输失败"分开——BFF 侧的 4xx/5xx 归一化只负责传输层，
业务失败带着稳定错误码回到网关，由重试策略按错误码决定是否重试，
避免"业务失败被当成 HTTP 异常"导致重试逻辑失效。
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.responses import StreamingResponse

from .agents.registry import dispatch
from .config import get_settings
from .core.errors import AgentError
from .llm.client import build_llm
from .schemas import AgentInvokeRequest, AgentInvokeResponse

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
logger = logging.getLogger("agent.api")

settings = get_settings()


@asynccontextmanager
async def lifespan(app: FastAPI):
    llm = build_llm(settings)
    logger.info("agent-service 启动，模型实现=%s（mock=%s）", llm.name, settings.use_mock)
    app.state.llm = llm
    try:
        yield
    finally:
        closer = getattr(llm, "aclose", None)
        if closer is not None:
            await closer()


app = FastAPI(title=settings.app_name, version="0.1.0", lifespan=lifespan)


@app.get("/health")
async def health() -> dict:
    return {"status": "UP", "app": settings.app_name, "model": app.state.llm.name,
            "mock": settings.use_mock}


@app.post("/v1/agent/invoke", response_model=AgentInvokeResponse)
async def invoke(request: AgentInvokeRequest) -> AgentInvokeResponse:
    started = time.perf_counter()
    try:
        outcome = await dispatch(request, app.state.llm)
        return AgentInvokeResponse(
            runId=request.run_id,
            status="SUCCEEDED",
            output=_with_meta(outcome.output, outcome),
            promptTokens=outcome.usage.prompt_tokens,
            outputTokens=outcome.usage.output_tokens,
            latencyMs=_elapsed_ms(started),
        )
    except AgentError as exc:
        logger.warning("业务失败 runId=%s code=%s msg=%s", request.run_id, exc.code, exc.message)
        return AgentInvokeResponse(runId=request.run_id, status="FAILED", errorCode=exc.code,
                                   errorMsg=exc.message, latencyMs=_elapsed_ms(started))
    except Exception as exc:  # noqa: BLE001 —— 兜底，避免把栈信息泄露给上游
        logger.exception("未预期异常 runId=%s", request.run_id)
        return AgentInvokeResponse(runId=request.run_id, status="FAILED",
                                   errorCode="AGENT_STAGE_FAILED",
                                   errorMsg=f"agent 内部异常：{type(exc).__name__}",
                                   latencyMs=_elapsed_ms(started))


@app.post("/v1/agent/stream")
async def stream(request: AgentInvokeRequest) -> StreamingResponse:
    async def event_source():
        queue: asyncio.Queue = asyncio.Queue()

        async def emit(event: dict) -> None:
            await queue.put(event)

        async def run() -> None:
            started = time.perf_counter()
            try:
                outcome = await dispatch(request, app.state.llm, emit=emit)
                await queue.put({
                    "type": "result",
                    "status": "SUCCEEDED",
                    "output": _with_meta(outcome.output, outcome),
                    "promptTokens": outcome.usage.prompt_tokens,
                    "outputTokens": outcome.usage.output_tokens,
                    "latencyMs": _elapsed_ms(started),
                })
            except AgentError as exc:
                await queue.put({"type": "result", "status": "FAILED",
                                 "errorCode": exc.code, "errorMsg": exc.message})
            except Exception as exc:  # noqa: BLE001
                logger.exception("流式执行异常 runId=%s", request.run_id)
                await queue.put({"type": "result", "status": "FAILED",
                                 "errorCode": "AGENT_STAGE_FAILED",
                                 "errorMsg": f"agent 内部异常：{type(exc).__name__}"})
            finally:
                await queue.put(None)

        task = asyncio.create_task(run())
        try:
            while True:
                item = await queue.get()
                if item is None:
                    break
                yield f"data: {json.dumps(item, ensure_ascii=False)}\n\n"
        finally:
            if not task.done():
                task.cancel()

    return StreamingResponse(event_source(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


def _with_meta(output: dict, outcome) -> dict:
    """附上可观测元信息：模型、提示词版本、是否 mock。

    提示词版本会参与 BFF 的 specHash 计算，因此必须随结果一起回传，
    否则历史回放无法判断"这条结果是用哪版提示词产生的"。
    """
    enriched = dict(output)
    enriched.setdefault("meta", {})
    enriched["meta"].update({
        "model": outcome.model,
        "promptVersion": outcome.prompt_version,
        "mock": outcome.mock,
    })
    return enriched


def _elapsed_ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)


def main() -> None:
    import uvicorn

    uvicorn.run("app.main:app", host=settings.host, port=settings.port, reload=False)


if __name__ == "__main__":
    main()
