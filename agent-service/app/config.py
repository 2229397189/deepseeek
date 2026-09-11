"""配置项。

设计要点：**没有配 LLM key 也要能跑通全链路**。
因此模型能力被抽象成可替换的 LlmClient，缺 key 时自动落到确定性实现（deterministic），
让单测、联调、演示不依赖外部大模型可用性；一旦配置 key，同一套代码走真实推理。
"""

from __future__ import annotations

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """运行配置，支持 AGENT_ 前缀的环境变量或 .env 文件覆盖。"""

    model_config = SettingsConfigDict(env_file=".env", env_prefix="AGENT_", extra="ignore")

    app_name: str = "chiron-agent-service"
    host: str = "127.0.0.1"
    port: int = 8000

    # OpenAI 兼容接口
    llm_base_url: str = "https://api.deepseek.com/v1"
    llm_api_key: str | None = None
    llm_model: str = "deepseek-chat"
    llm_timeout_s: float = 60.0
    llm_max_retries: int = 1

    # 强制走确定性实现（演示 / 压测 / 断网联调）
    force_mock: bool = False

    @property
    def use_mock(self) -> bool:
        return self.force_mock or not self.llm_api_key


@lru_cache
def get_settings() -> Settings:
    return Settings()
