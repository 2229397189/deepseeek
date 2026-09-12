"""测试环境约定：单测必须跑在确定性模型上。

背景：``.env`` 里配了真实的 DeepSeek key 且 ``AGENT_FORCE_MOCK=false``，一旦 key 欠费或
网络不通，``/agents/*`` 会返回 UPSTREAM_4XX，测试就红成一片——但**代码并没有坏**，
坏的是外部依赖。这批断言（``model == "deterministic-mock"``、``meta.mock is True``、
逐题评分可复现）本来就只在确定性实现下才成立，所以在这里把模式钉死，让测试结果只反映
代码本身，不反映账号余额。

注意：必须在 ``app.config`` 被导入之前设置，pydantic-settings 才会优先读到环境变量
（环境变量优先级高于 .env 文件）。
"""

from __future__ import annotations

import os

os.environ.setdefault("AGENT_FORCE_MOCK", "true")
