# 车联网平台 Agent（dst-v2x-agent）

> 一句话定位：业务用户用一句话完成「异常分析 / 数据问答 / 图表生成」的车联网数据智能体。
> 核心红线：**LLM 永不产出 SQL**——模型只产出 `capability_id` + 结构化参数（或多 Agent 工具调用），Java 侧模板 SQL + PreparedStatement 执行确定性查询。

当前状态：**V2 迭代中**——双 Agent 链路（capability 链路 + fleet_copilot 多 Agent 编排）跑通真实 Doris 数据（114 表），前端 Nuxt 4 全量重写，配置全面接入 Nacos 配置中心。

## 功能总览

| 能力 | 说明 |
|---|---|
| 对话式数据问答 | 自然语言 → 意图抽取 → capability → 真实 Doris 查询 → 表格/图表/结论（AG-UI 协议 SSE 流式） |
| 多 Agent 编排（fleet_copilot） | Supervisor 结构化路由 → 车辆/告警/故障/里程/跨域 5 个专家 Agent（AgentScope ReAct）→ 11 个工具；异常分析走确定性工作流（三路取证 + 报告） |
| 可视化体系 | 后端产出 ECharts option / Mermaid / G6 / 地图规格，`VIS_SPEC` 帧驱动前端渲染，每图带 caption 说明 |
| 设备孪生地图 | 省级 choropleth 钻取到市、在线/停车/离线三层散点、24h 告警趋势与明细、品牌/区域 TOP 榜 |
| 任务卡 | 异常分析结论沉淀为任务卡，支持 PDF 导出（内置 CJK 字体） |
| 车队 ACL | 权限 = AllowedScope ∩ RequestedScope；op1 仅见 F001、op2 仅见 F002，admin 全量 |
| 管理后台 | capability 注册/dry-run、A2A 审批、记忆审核、效果看板（`/console/*`） |
| 双层记忆 | Redis 短记忆（多轮继承）+ MySQL 记忆网关（FULLTEXT 检索） |

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Spring Boot 3.3（虚拟线程）/ JDK 21 / AgentScope Java 2.0.2（AG-UI 协议 starter）/ 单 Maven 模块按包隔离 |
| 前端 | Nuxt 4（SSR）/ Vue 3 / Three.js / ECharts / Mermaid / AntV G6 |
| 配置中心 | Nacos（唯一应用配置源，本地无 application.yml） |
| 存储 | MySQL 控制库（会话/元数据/审计，Flyway V1~V14 自动迁移）· Doris 分析库（真实车联网数据，MySQL 协议只读）· Redis（短记忆/事件缓冲/结果缓存，ACL 需 username=default） |
| LLM | OpenAI 兼容端点可配置切换：智谱 GLM / DeepSeek / Kimi / mock（无 Key 可演示） |

## 架构

```
 Nuxt 4 前端（:3000，内置 routeRules 网关代理）
   │  AG-UI 协议（SSE 事件流）
   ▼
 Spring Boot :8080 ──── Nacos 配置中心（dst-v2x-agent.properties）
   │
   ├─ capability 链路：NL → 意图抽取 → capability yaml → 参数管线(ACL注入) → 模板 SQL
   │
   └─ fleet_copilot 链路：Supervisor 路由
        ├─ VEHICLE / ALARM / FAULT / MILEAGE / CROSS 专家（ReAct + Toolkit 白名单）
        ├─ anomaly → 确定性工作流（三路取证 → 流式报告）
        └─ 工具 → RealQueries（真实 Doris，ACL org 展开）/ 可视化生成
   │
   ▼
 MySQL 控制库 · Doris 分析库 · Redis · LLM API
```

## 快速开始（本地开发）

前置：JDK 21、Node ≥ 20.9、Maven（公司私服 settings）、可访问 Nacos/MySQL/Doris/Redis（地址见 `backend/src/main/resources/config/bootstrap-local.yml`）。

```bash
# 1. 后端（profile=local，配置从 Nacos 拉取）
cd backend
mvn -s <你的 settings-dst.xml> spring-boot:run

# 2. 前端（dev 模式自带代理 :3000 → :8080）
cd frontend
npm install
npm run dev
```

访问 `http://localhost:3000/`，演示账号：

| 账号 | 密码 | 数据范围 |
|---|---|---|
| admin | admin123 | 全量 |
| op1 | op123456 | 仅车队 F001 |
| op2 | op123456 | 仅车队 F002 |

> Nacos 中需已发布 `dst-v2x-agent.properties`（dataId，group=DEFAULT_GROUP，类型 properties）；本地查看副本在 `nacos/` 目录（含密钥，已 gitignore）。

## 生产部署（Linux）

完整步骤见 **[DEPLOY.md](DEPLOY.md)**——部署目录 `/data/service`，**git 拉代码、服务器构建、无 Nginx**（前端 Node 进程经 `nitro.routeRules` 内置网关代理，浏览器统一走 :3000），systemd 托管双服务。要点：

```bash
cd /data/service && git clone git@gitlab.dstcar.com:FlyBees/vds/ai-coding/dst-v2x-agent.git
cd dst-v2x-agent/backend && mvn clean package -DskipTests   # → target/v2x-agent-backend.jar（产物名固定）
cd ../frontend && npm ci && npm run build                   # → .output/（自包含 Node 服务）
```

注意：LLM 配置变更必须重启后端（Nacos 热刷新不重建 LLM 客户端）；前后端分机部署时需改 `nuxt.config.ts` 的 proxy 目标后重新构建。

## 配置说明

应用配置全部在 Nacos（本地无 application.yml；默认 profile=`local`，配置自带默认值，部署零本地配置）。以下环境变量仅在需要覆盖 Nacos 默认值时使用：

| 配置 | 环境变量 | 说明 |
|---|---|---|
| 运行环境 | `SPRING_PROFILES_ACTIVE` | local / dev / test → 决定 Nacos 地址与 namespace |
| 控制库 MySQL | `MYSQL_HOST/PORT/DB/USER/PASSWORD` | 会话/元数据/审计 |
| 分析库 Doris | `DORIS_HOST/PORT/DB/USER/PASSWORD` | 真实车联网数据 |
| Redis | `REDIS_HOST/PORT/USERNAME/PASSWORD/DB` | `REDIS_USERNAME` 必须 `default` |
| LLM 供应商 | `LLM_ACTIVE` = glm/deepseek/kimi/mock | 默认 glm |
| LLM 密钥 | `GLM_API_KEY` / `DEEPSEEK_API_KEY` / `KIMI_API_KEY` | |
| copilot 数据源 | `copilot.datasource` = real/sim | 默认 real（真实 Doris） |

## 架构红线（Code Review 必查）

1. LLM 输出 Schema 没有 `sql` 字段；SQL 只存在于 `capability/*.yaml` 模板与 `RealQueries`/`SimQueries`，一律 PreparedStatement 参数化
2. 权限注入在参数管线（`ParamResolver`）与 copilot ACL（`FleetMappingService` org 展开），`acl_*` 参数模型无法传入或覆盖
3. `TOOL_CALL_RESULT`（表格）必须先于 `TEXT_MESSAGE_CONTENT`（结论）推送
4. Session 用 MySQL（`agent_session*`），不用本地文件
5. 结果截断必须 `truncated=true` 可见，绝不静默截断
6. Agent 不直接访问 Doris/MySQL/Redis，只能经过 Capability/Memory/Workflow
7. 前端只允许在 `frontend/` 目录开发（后端 static/ 已删除）
8. 管理后台页面路由一律 `/console/*`（`/admin` 前缀与后端 API 冲突）

## 目录结构

```
backend/                             Spring Boot 3.3 + AgentScope Java
  src/main/java/com/dst/v2xagent/
    run/                             capability 链路（编排/参数管线/记忆/审计）
    copilot/                         fleet_copilot 多 Agent（Supervisor/专家/工具/查询）
  src/main/resources/
    capability/                      capability 元数据 + SQL 模板（Code Review 重点）
    agent-profiles/                  Agent prompt 配置
    orchestration/                   异常解释编排模板
    semantic/                        语义层（V2 演进中）
    db/migration/                    Flyway V1~V14
    config/bootstrap-{local,dev,test}.yml   Nacos 接入（profile 三环境）
frontend/                            Nuxt 4（地图/对话/任务卡/管理后台 8+ 页）
  components/{landing,map,chat,console}/    页面级组件
  webgl/                             登录页 WebGL 视觉模块
nacos/                               Nacos 配置本地查看副本（含密钥，已 gitignore）
scripts/                             冒烟/安全/回归/管理台测试 + 模拟库种子（sim/）
doc/                                 PRD、前后端设计文档、copilot 架构文档
DEPLOY.md                            Linux 生产部署文档
```

## 测试

```bash
cd backend && mvn -s <settings-dst.xml> test   # 单元测试
python scripts/smoke_test.py                    # 8 场景端到端冒烟
python scripts/security_test.py                 # 越权/拒答安全用例
python scripts/admin_test.py                    # 管理后台 API
python scripts/eval_regression.py               # 30 条样例回归（约 97%）
# 均默认打 http://localhost:8080，可用 V2X_BASE 重定向
```

## 路线图（V2 架构优化）

按 Phase 渐进，每 Phase 编译可过 + 测试报告（详见架构计划）：

- P1 基础治理：Capability Registry 2.0（版本/契约）、统一 Firewall/QueryExecutor/QueryBudget/Trace
- P2 语义层：Entity/Metric/Dimension + Semantic Query Planner
- P3 记忆：统一 MemoryManager（工作记忆 Redis / 情景 MySQL / 语义向量）、结果存储与 Extractor
- P4 分析：工作流引擎化、证据链、基线对比与相关性
- P5 地理：省市区多级聚合下钻
- P6 可视化规划：ChartSpec 标准化
- P7 Agent 体验：Agent Trace / 任务卡 / 分析卡

## 文档索引

- [DEPLOY.md](DEPLOY.md) — Linux 生产部署
- `doc/车联网平台 Agent 产品需求文档（PRD）.md`
- `doc/后端设计文档（车联网平台 Agent · Spring Boot + AgentScope Java）.md`
- `doc/前端设计文档（车联网平台 Agent · Aurora 设计体系）.md`
- `doc/copilot-agentscope.md` — fleet_copilot 多 Agent 架构
