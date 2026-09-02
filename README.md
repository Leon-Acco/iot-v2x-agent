# 车联网平台 Agent（dst-v2x-agent）

> 一句话定位：业务用户用一句话完成「异常分析 / 数据问答 / 图表生成」的车联网智能体。
> 核心红线：**LLM 永不产出 SQL**——模型只产出 `capability_id` + 结构化参数，Java 侧模板 + PreparedStatement 执行确定性查询。

当前状态：**P0 打样完成**（AG-UI 全链路 + 8 个只读 capability + 工作台三区联动 + Capability 管理后台 + 反馈/审计入库）。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Spring Boot 3.3（虚拟线程）/ JDK 21 / 单 Maven 模块按包隔离 |
| 前端 | 无构建静态页（HTML/CSS/JS，布局令牌承自 Office_Agent）+ Vega-Lite 图表 |
| 存储 | MySQL（控制库：元数据/会话/审计）· Doris 或 MySQL 模拟数仓（分析库）· Redis（短记忆/事件缓冲/结果缓存） |
| LLM | OpenAI 兼容端点可配置切换：DeepSeek / 智谱 GLM / Kimi / mock（规则抽取，无 Key 可演示） |

## 快速开始

```bash
# 1. 灌模拟数仓种子数据（3 车队 × 10 车 × 30 天）
python scripts/seed_mock_dws.py

# 2. 启动后端（含前端静态资源，单端口 8080）
cd backend
mvn -s D:\tool\apache-maven-3.9.6\conf\settings-dst.xml spring-boot:run
```

访问：
- 登录页：http://localhost:8080/ （admin/admin123 · op1/op123456 · op2/op123456）
- 工作台：http://localhost:8080/workbench.html
- 管理后台：http://localhost:8080/admin.html （admin 账号）
- 健康检查：http://localhost:8080/api/health

## 连接真实环境

| 配置 | 环境变量 | 默认 |
|---|---|---|
| 控制库 MySQL | `MYSQL_HOST/PORT/DB/USER/PASSWORD` | 172.16.8.225:30316 / v2x_agent |
| 分析库 Doris | `DORIS_HOST/PORT/DB/USER/PASSWORD` | 未配置时用 MySQL 模拟库 v2x_dws_mock |
| Redis | `REDIS_HOST/PORT/PASSWORD/DB` | 172.16.8.225:30689 / db2 |
| LLM | `LLM_ACTIVE` = deepseek/glm/kimi/mock + 对应 `*_API_KEY` | mock |

接入真实 Doris 后：改 `backend/src/main/resources/capability/*.yaml` 的 `sql_template` 表名映射即可。

## 架构红线（Code Review 必查）

1. LLM 输出 Schema 没有 `sql` 字段；SQL 只在 `capability/*.yaml` 模板，走 PreparedStatement
2. 权限注入在参数管线第 7 步（`ParamResolver`），`acl_*` 参数模型无法传入或覆盖
3. `TOOL_CALL_RESULT`（表格）必须先于 `TEXT_MESSAGE_CONTENT`（结论）推送
4. Session 用 MySQL（`agent_session*`），不用本地文件
5. 结果截断必须 `truncated=true` 可见，绝不静默截断

## 目录

```
backend/   Spring Boot 后端（包结构对应设计文档模块边界，便于后续拆 Maven 多模块）
  src/main/resources/capability/    8 个 capability 元数据 + SQL 模板（Code Review 重点）
  src/main/resources/static/        前端静态资源（登录页/工作台/管理后台）
scripts/   种子数据、冒烟、安全用例、30 条回归评测脚本
```

## 测试

```bash
cd backend && mvn -s D:\tool\apache-maven-3.9.6\conf\settings-dst.xml test  # 单测
python scripts/smoke_test.py        # 8 场景端到端冒烟
python scripts/security_test.py     # 越权/拒答安全用例
python scripts/admin_test.py        # 管理后台 API
python scripts/eval_regression.py   # 30 条样例回归（当前 87%，口径修正后 97%）
```

## P0 之后（见 PRD 路线图）

- P1：异常解释编排模板、任务卡/PDF、双层记忆（MemoryGateway + ACL 预过滤）、效果看板
- P2：A2A 控制面（注册/审批/scope/credential/service task）、全屏分析页
- P0 遗留：WebGL 冰晶车魂登录页（当前为 CSS 极光简化版）、地图底图（当前散点图）、真实模型联调
