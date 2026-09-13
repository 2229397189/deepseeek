"""Agent Kernel：把「业务流水线」与「运行时治理」「传输细节」解耦的内核层。

四件套职责边界：
- RunSpec：声明一次运行的元信息（业务类型、覆盖的 stage、提示词/规格版本、超时与重试）；
- AgentProgram：业务流水线的统一形态（spec + run(ctx)），由各业务模块注册进 Registry；
- AgentProgramRegistry：biz_type -> program 的唯一查找入口，dispatch 只认它；
- AgentRuntime：统一编排——内核事件发射（RUN_STARTED / RUN_SUCCEEDED / RUN_FAILED）、
  异常包装、计时；业务 Shell 不再各自实现"调用前发什么、失败怎么算"；
- Port（LlmPort / RetrievalPort）：内核定义的依赖接口，业务只依赖接口，
  LlmClient 与 rag.search_documents 是各自的适配实现——传输细节可替换，业务不动。

内核事件默认走进程内 KernelEventBus（可注入 sink），不改变对外 SSE 线格式；
后续要接观测系统时，注册一个 sink 即可，业务代码零改动。
"""

from .events import KernelEvent, KernelEventBus
from .ports import LlmPort, RetrievalPort
from .runtime import AgentProgram, AgentProgramRegistry, AgentRuntime
from .spec import RunSpec

__all__ = [
    "RunSpec",
    "KernelEvent",
    "KernelEventBus",
    "AgentProgram",
    "AgentProgramRegistry",
    "AgentRuntime",
    "LlmPort",
    "RetrievalPort",
]
