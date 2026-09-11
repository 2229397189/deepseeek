# agent-service

Chiron 平台（AI 求职评估与模拟面试）的 AI 能力层。上游 BFF 通过统一网关（去重 / 重试 / 计量 / 审计）
调用本服务；本服务只负责"给定 bizType 与 payload，产出结构化结果"。

## 契约

| 端点 | 用途 |
| --- | --- |
| `GET /health` | 健康检查，返回当前模型实现与是否 mock |
| `POST /v1/agent/invoke` | 同步调用，返回结构化结果 |
| `POST /v1/agent/stream` | SSE 流式，逐步推送流水线阶段（前端做分步展示） |

请求体（对应 BFF `AgentInvokeCommand`）：

```json
{
  "runId": "uuid",
  "bizType": "DECIDE",
  "bizId": 1,
  "userId": 1,
  "stage": null,
  "specHash": "sha256",
  "payload": { "jdText": "...", "profile": { "skills": ["Java"] } }
}
```

响应体（对应 BFF `AgentInvokeResult`）：

```json
{
  "runId": "uuid",
  "status": "SUCCEEDED",
  "output": { "score": 88, "conclusion": "APPLY", "steps": [], "meta": {} },
  "errorCode": null,
  "errorMsg": null,
  "promptTokens": 512,
  "outputTokens": 128,
  "costCredit": null,
  "latencyMs": 1200
}
```

**失败语义**：业务失败一律返回 HTTP 200 + `status=FAILED` + 稳定 `errorCode`。
BFF 侧只做传输层错误归一化（TIMEOUT / RATE_LIMIT / UPSTREAM_5XX…），
业务错误码原样透传给重试策略，避免"业务失败被当成 HTTP 异常"。

### 业务类型

| bizType | 模式（payload.mode / stage） | 说明 |
| --- | --- | --- |
| `DECIDE` | — | 岗位评估完整流水线：`parse_jd → load_profile → match_score → generate_advice` |
| `DECIDE_PREVIEW` | — | 首页秒级预览，不调用模型，仅给分数与缺口 |
| `INTERVIEW` | `START` / `ANSWER` / `FINISH` | 简历驱动出题、薄弱点刻意追问、整场评分 |
| `RESUME_PARSE` | — | 简历结构化解析与薄弱点推断，文本来源二选一：`resumeText` 或 `fileBase64`（pdf / docx / txt / md） |
| `RESUME_QUESTION` | `POLISH` / `TAILOR` / `GENERATE` | 选中文本润色、按岗位定制 |
| `RAG_SEARCH` | — | 知识库候选片段重排与基于片段的回答生成 |

## 打分口径（DECIDE）

```
score = 100 × (0.60 × 岗位要求覆盖度 + 0.25 × 经历相关性 + 0.15 × 平台内面试表现) − 8 × 薄弱点命中数
```

- 结论阈值：≥75 建议推进（APPLY）／60~74 补齐再投（HOLD）／<60 暂不建议（REJECT）；
- 经历相关性来自经历与项目文本对岗位技能的命中率；面试表现缺失时按 60 分中位基线估算，
  并在风险点中显式标注"评分置信度下降"，而不是悄悄给个默认分。

**为什么结构化判定不用模型**：分数必须可复现、可解释、可回归测试。技能识别、打分、
薄弱点判定由确定性逻辑完成；模型只负责叙述、追问与评语。否则同一份简历两次评估得分不同，
用户无法追问"为什么扣这 8 分"，面试记录之间也不可比。

## 简历文件抽取

`RESUME_PARSE` 的文本来源按优先级解析：

1. `payload.resumeText` —— 前端已抽好的纯文本（Markdown 编辑器场景）；
2. `payload.fileBase64` + `payload.fileName` —— 原始文件，服务端解码后按扩展名抽取：
   `.pdf` 用 `pypdf`、`.docx` 用 `python-docx`、`.txt` / `.md` 按 UTF-8 / GBK 逐级兜底解码。

- 两条来源产出**同一套画像字段**，输出里用 `sourceKind`（`TEXT` / `FILE`）说明实际来源，
  避免"上传解析"和"粘贴解析"各写一套口径导致分数不可比；
- 抽取失败一律返回 HTTP 200 + `status=FAILED` + 稳定 `errorCode`（`EMPTY_TEXT` 扫描件无可复制文本 /
  `PDF_PARSE_FAILED` 加密或损坏 / `UNSUPPORTED_FILE_TYPE` / `LEGACY_DOC_UNSUPPORTED` .doc 旧格式 /
  `BAD_BASE64`），由 BFF 落成资产 `FAILED` 并给出重试入口，而不是静默产出空画像；
- 文件体积上限由 BFF 侧拦截（默认 20MB），本服务不重复做限制。

## 运行

```bash
cd agent-service
python -m venv .venv
.venv\Scripts\python -m pip install -r requirements.txt
copy .env.example .env          # 不填 LLM_API_KEY 也能启动，自动落到确定性实现
.venv\Scripts\python -m app.main
```

- 未配置 `AGENT_LLM_API_KEY` 时自动使用确定性实现（`deterministic-mock`），
  结果可复现，适合联调、演示与压测；配置后走真实模型，代码路径完全相同。
- 启动后 `GET /health` 的 `mock` 字段可直接看出当前处于哪种模式。

## 测试

```bash
.venv\Scripts\python -m pytest tests -q
```

用例覆盖的不只是"能跑通"，而是把产品策略钉在测试里：

- 同输入必须同分数（否则面试记录不可比）；
- 空洞回答必须触发追问并写入 `weakPointsUpdate`（薄弱点刻意追问策略不许退化）；
- `DECIDE_PREVIEW` 不得消耗模型额度（`promptTokens == 0`）；
- 检索无命中时返回 `recallGap=true` 而不是编造答案；
- 文件上传（pdf / docx / txt 的 base64）与 `resumeText` 直传必须产出同一套画像字段，
  `sourceKind` 如实标注来源，两条口径不许漂移；
- 文件抽取失败必须带稳定 `errorCode`（扫描件、加密 PDF、.doc 旧格式、非法 base64 都不许静默返回空文本）；
- 失败必须带稳定 `errorCode`。
