# Chiron Agent —— AI 求职评估与模拟面试平台

复刻小红书项目《我花一个月，独立开发了一个Agent项目》的全部页面布局与功能，并按企业级标准补齐分层架构、并发治理与计费体系。

- 产品与页面规格（视频还原权威清单）：[docs/00-产品与页面规格.md](docs/00-产品与页面规格.md)

## 技术栈

| 模块 | 技术 |
| --- | --- |
| web | React 18 + TypeScript + Vite |
| bff | Java 17 + Spring Boot 3.2 + MyBatis-Plus + Sa-Token + Flyway + Redis |
| agent-service | Python 3.11 + FastAPI |
| 存储 | PostgreSQL 16 + pgvector、Redis 7 |

## 目录结构

```
lq-deepseek/
├── bff/                # Java BFF：认证、权限、计费、聚合、SSE 透传
├── web/                # React 前端
├── agent-service/      # Python Agent：评估 / 画像 / 报告 / 面试状态权威
├── docs/               # 设计文档
└── docker-compose.yml  # 本地依赖（PostgreSQL + pgvector、Redis）
```

## 本地启动

### 1. 基础设施

```bash
docker compose up -d
```

数据库：`127.0.0.1:5432/lq_deepseek`（lq / lq_deepseek_pwd），Redis：`127.0.0.1:6379`。
Flyway 会在 BFF 启动时自动执行 `bff/src/main/resources/db/migration` 下的迁移脚本。

### 2. BFF

```bash
cd bff
mvn spring-boot:run
```

服务地址 `http://127.0.0.1:8080/api`，接口文档 `http://127.0.0.1:8080/api/swagger-ui.html`。

### 3. 前端

```bash
cd web
npm install
npm run dev
```

### 4. Agent 服务

```bash
cd agent-service
python -m venv .venv
.venv\Scripts\python -m pip install -r requirements.txt
copy .env.example .env          # 可留空，未配 Key 时自动使用确定性实现
.venv\Scripts\python -m app.main
```

服务地址 `http://127.0.0.1:8000`，健康检查 `GET /health`（`mock=true` 表示当前为可复现的确定性模式）。
BFF 通过 `lq.gateway.agent.base-url` 指向本服务。

## 已实现能力（BFF）

- [x] 注册 / 登录 / 登出 / 当前用户（Sa-Token，密码 BCrypt，账号枚举防护）
- [x] 账号钱包、额度流水、充值；幂等键 + 条件更新防超发
- [x] 预授权冻结 / 结算 / 释放（AI 调用计费模型）
- [x] 邀请码核销与双向奖励
- [x] 统一响应体、错误码、全局异常收敛
- [x] Flyway：用户、文件资产、面试会话、Decision 会话、agent_runs、计费、邀请码、上下文切片、知识库
- [x] AI Invocation Gateway：Single-flight 编排（Redis Lua + 进程内降级）、结果回放、重试退避、审计落 agent_runs、计费冻结 / 结算 / 释放串联
- [x] 简历中心：上传 pdf / docx / md / txt → SHA-256 去重（同一份简历不重复扣费）→ 结构化画像解析；列表 / 详情；选中文本 AI 润色与按岗位定制

## 已实现能力（agent-service）

- [x] DECIDE：投递决策打分与结论、风险项拆解
- [x] INTERVIEW：简历驱动出题与多轮追问
- [x] RESUME_PARSE：支持 `resumeText` 直传或 `fileBase64` 文件（pdf / docx / txt / md），输出 sourceKind 与结构化画像
- [x] RESUME_QUESTION：选中文本润色 / 按岗位定制，输出 polished / reasons / matchedSkills

## 规划中（按功能逐个提交）

- [ ] Agent Kernel 与断点恢复
- [ ] 简历中心进阶：Markdown 编辑、版本回退、PDF 导出
- [ ] JD 分析：parse_jd → load_profile → match_score 流水线
- [ ] AI 面试：简历驱动出题、薄弱点加权追问、语音转写
- [ ] 知识图谱可视化与用户画像、长期记忆确认
- [ ] 知识库混合检索（pgvector + FTS + RRF + rerank）
- [ ] 后台管理：模型管理、知识库管理

## 提交规范

每完成一个功能即提交并推送：

```bash
git add -A
git commit -m "feat(<module>): <功能描述>"
git push origin main
```
