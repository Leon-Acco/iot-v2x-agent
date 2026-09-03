# dst-v2x-agent Linux 部署文档

> 适用环境：Linux 服务器（CentOS 7+ / Ubuntu 20.04+ / 麒麟等），部署根目录 `/data/service`。
> 部署方式：**git 拉取代码、服务器上构建**（不手工上传产物）。
> 部署形态：**无 Nginx**——浏览器直连前端 Nuxt SSR（:3000），由 Nuxt Node 进程内置的 routeRules 代理把 API 请求转发到后端 Spring Boot（:8080，仅本机回环）。
> 外部依赖：Nacos 配置中心、MySQL 控制库、Doris 分析库、Redis、LLM API（智谱 GLM 等）。

---

## 1. 部署架构总览

```
                      ┌────────────────────────────────────┐
 浏览器 ──── :3000 ──▶│  Nuxt 4 SSR（Node 进程，网关职责）  │
                      │  /            → 页面渲染            │
                      │  /agui|/ag-ui|/api|/admin → 转发    │
                      └───────────────────┬────────────────┘
                                          │ 127.0.0.1:8080
                          ┌───────────────▼────────────────┐
                          │ Spring Boot（v2x-agent-backend）│
                          └───────────────┬────────────────┘
                                          │
     ┌──────────────┬──────────────┬──────┴─────┬──────────────┐
     ▼              ▼              ▼            ▼              ▼
 Nacos :8848    MySQL 控制库     Doris :9030   Redis        LLM API
(配置中心/必须)  (会话/元数据/     (真实车联网   (短记忆/       (OpenAI 兼容
                 审计/Flyway)      分析数据)     事件缓冲)      端点，GLM)
```

**三个部署铁律**（理解后再操作）：

1. **后端 jar 内没有 application.yml，Nacos 是唯一配置源**。启动前必须保证服务器能连通 Nacos，且目标 namespace 里已发布 `dst-v2x-agent.properties` 配置（见第 4 节）。
2. **不需要 Nginx**。`frontend/nuxt.config.ts` 已内置 `nitro.routeRules` 代理（dev 与生产 build 均生效，h3 流式转发支持 SSE），前端 Node 进程就是网关；后端 8080 只监听本机，不对外。
3. **服务器零本地配置**。中间件连接、LLM Key 全在 Nacos 配置里（带默认值），`bootstrap.properties` 默认 profile=`local`——起 jar 即用，服务器上不需要任何 env 文件或本地配置；一切变更走 git。

---

## 2. 环境要求与目录规划

| 组件 | 版本要求 | 用途 | 检查命令 |
|---|---|---|---|
| git | ≥ 2.x（需可访问公司 GitLab） | 拉取代码 | `git --version` |
| JDK | **21**（必须，代码按 21 编译） | 构建后端 + 运行 | `java -version` |
| Maven | ≥ 3.8（**服务器构建必装**） | 后端打包 | `mvn -v` |
| Node.js | **≥ 20.9**（建议 20 LTS / 22） | 构建前端 + 运行 SSR | `node -v` |
| npm | 随 Node | 前端依赖安装 | `npm -v` |
| Python | ≥ 3.6（可选） | 冒烟/回归测试脚本 | `python3 -V` |

> Maven 需配置公司私服 `settings.xml`（对应开发机的 `D:\tool\apache-maven-3.9.6\conf\settings-dst.xml`），否则拉不到 AgentScope（`io.agentscope`）等私仓依赖。

### 环境安装（Ubuntu / deb 系，root 身份，内网走国内镜像）

```bash
# ===== 1. 基础工具 =====
apt update && apt install -y git curl wget

# ===== 2. JDK 21（Ubuntu 22.04 源里有）=====
apt install -y openjdk-21-jdk
java -version          # 预期 openjdk version "21.x"

# ===== 3. Node 22 LTS（npmmirror 国内镜像，官方二进制包）=====
# 注意：必须 >= 22.12.0！Nuxt 4.2 依赖链（oxc-parser/vite7/rolldown）engines 均要求
#       ^20.19.0 || >=22.12.0。低版本 Node 下 npm 会【静默跳过】原生绑定 optional 依赖，
#       npm ci 成功但构建报 Cannot find native binding @oxc-parser/binding-linux-x64-gnu
cd /tmp
wget https://npmmirror.com/mirrors/node/v22.21.1/node-v22.21.1-linux-x64.tar.xz
tar -xf node-v22.21.1-linux-x64.tar.xz -C /usr/local/
ln -sfn /usr/local/node-v22.21.1-linux-x64 /usr/local/node
for b in node npm npx; do ln -sf /usr/local/node/bin/$b /usr/bin/$b; done
node -v && npm -v

# ===== 4. Maven 3.9.6（华为云镜像，与开发机同版本）=====
cd /tmp
wget https://mirrors.huaweicloud.com/apache/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.tar.gz
tar -xf apache-maven-3.9.6-bin.tar.gz -C /usr/local/
ln -sf /usr/local/apache-maven-3.9.6/bin/mvn /usr/bin/mvn
mvn -v                 # 预期 Apache Maven 3.9.6 + Java 21

# ===== 5. npm 换国内源（否则 npm ci 拉包超时）=====
npm config set registry https://registry.npmmirror.com

# ===== 6. Maven 私服配置（开发机上执行，传到服务器）=====
# scp D:\tool\apache-maven-3.9.6\conf\settings-dst.xml root@<SERVER_IP>:/root/.m2/settings.xml
# （服务器上先 mkdir -p /root/.m2）
```

服务器最低配置建议：4C / 8G / 50G（构建 + 运行同机；后端 JVM 默认 1G 堆）。

创建运行用户与目录：

```bash
# 创建专用运行用户（避免 root 跑服务）
sudo useradd -r -m -d /data/service -s /bin/bash app 2>/dev/null || true

# /data/service 下由 app 用户持有
sudo chown -R app:app /data/service
```

部署后的目录形态：

```
/data/service/
├── dst-v2x-agent/                     # git 仓库（git clone 得到）
│   ├── backend/
│   │   ├── target/
│   │   │   └── v2x-agent-backend-0.1.0.jar  # mvn package 产物（名含 pom 版本号；pom 升版本后同步改 unit 的 ExecStart）
│   │   └── logs/                      # 运行日志（自动生成 backend.log，已 gitignore）
│   ├── frontend/
│   │   └── .output/                   # npm run build 产物（自包含，已 gitignore）
│   └── ...（源码、文档）
```

---

## 3. 首次部署

以下命令均在服务器上以 `app` 用户执行（`sudo -iu app` 或直接登录）。

### 3.1 克隆代码

```bash
cd /data/service
git clone git@gitlab.dstcar.com:FlyBees/vds/ai-coding/dst-v2x-agent.git
cd dst-v2x-agent
```

> 前置：`app` 用户需有 GitLab SSH Key（`ssh-keygen -t ed25519` 后把公钥加到 GitLab 账号），并用 `ssh -T git@gitlab.dstcar.com` 验证连通。

### 3.2 配置 Maven 私服

```bash
# 把公司 settings-dst.xml 放到 app 用户的默认位置（后续 mvn 命令不再需要 -s 参数）
mkdir -p ~/.m2
cp <你的 settings-dst.xml> ~/.m2/settings.xml
chmod 600 ~/.m2/settings.xml
```

### 3.3 构建后端

```bash
cd /data/service/dst-v2x-agent/backend
mvn clean package -DskipTests
# 产物：target/v2x-agent-backend-0.1.0.jar（名含 pom 版本号；
# pom 升版本后，第 5 节 unit 的 ExecStart 中 jar 名需同步修改）
```

### 3.4 构建前端

```bash
cd /data/service/dst-v2x-agent/frontend
npm ci            # 按 package-lock.json 精确安装
npm run build     # 产物：.output/（自包含 Node 服务，运行期不需要 node_modules）
```

---

## 4. Nacos 配置确认

后端所有应用配置（数据源、Redis、LLM、Agent 参数）都在 Nacos 的 **`dst-v2x-agent.properties`**（group=`DEFAULT_GROUP`，类型=properties，UTF-8 无 BOM）。**默认 profile=`local`，配置里中间件地址/密码/LLM Key 均有默认值，服务器启动即用、零本地配置**——本节一般只需做连通性确认。

profile 与 Nacos 的对照（源码 `backend/src/main/resources/config/bootstrap-{profile}.yml`）：

| profile | Nacos 地址 | namespace | 账号 |
|---|---|---|---|
| `local` | 172.16.8.237:8848 | 240b52d5-ae87-443c-86ae-708fe2929423 | nacos |
| `dev` | 172.16.8.165:8848 | d9eba963-51aa-4d38-bd81-1206c419578e | dev |
| `test` | 172.16.8.237:8848 | 588bb147-c6af-4805-be17-705fd5253ce2 | nacos |

部署前检查清单：

```bash
# 1. 服务器到 Nacos 的连通性
curl -s "http://172.16.8.237:8848/nacos/v1/console/health/readiness" && echo OK

# 2. 确认目标 namespace 中已发布配置（替换对应账号密码与 tenant）
curl -s "http://172.16.8.237:8848/nacos/v1/cs/configs?dataId=dst-v2x-agent.properties&group=DEFAULT_GROUP&tenant=588bb147-c6af-4805-be17-705fd5253ce2&username=nacos&password=<密码>" | head -20
```

- 若新建环境：把代码仓里 `nacos/dst-v2x-agent.properties`（本地查看副本，UTF-8 带 BOM 版）内容**去掉 BOM** 后发布到目标 namespace，dataId 固定 `dst-v2x-agent.properties`。
- 控制库表结构由 Flyway 自动迁移（`classpath:db/migration`，V1~V14），首次启动自动建库建表，无需手工执行 SQL。
- **重要**：修改 Nacos 中 LLM 相关配置后必须重启后端（`refresh-enabled` 不会重建 LLM 客户端）。

---

## 5. systemd 服务

### 5.1 创建 unit 文件（root 全程部署版：两条 base64 命令，直接粘贴执行）

> 为什么用 base64：heredoc 写文件时若粘贴内容每行带缩进，结尾的 `EOF` 不顶格会导致 heredoc 永不结束、文件写坏；base64 单行命令对粘贴免疫，怎么粘都不会错。
> 以下命令写入的内容 = 5.2 参考内容**去掉 `User=app`/`Group=app` 两行**的版本（root 全程构建时产物属主为 root，不能用 app 用户运行服务）。

```bash
echo 'W1VuaXRdCkRlc2NyaXB0aW9uPWRzdC12MngtYWdlbnQgYmFja2VuZCAoU3ByaW5nIEJvb3QgKyBBZ2VudFNjb3BlKQpBZnRlcj1uZXR3b3JrLW9ubGluZS50YXJnZXQKV2FudHM9bmV0d29yay1vbmxpbmUudGFyZ2V0CgpbU2VydmljZV0KVHlwZT1zaW1wbGUKV29ya2luZ0RpcmVjdG9yeT0vZGF0YS9zZXJ2aWNlL2RzdC12MngtYWdlbnQvYmFja2VuZApFeGVjU3RhcnQ9L3Vzci9iaW4vamF2YSAtWG1zNTEybSAtWG14MTAyNG0gLURmaWxlLmVuY29kaW5nPVVURi04IC1qYXIgdGFyZ2V0L3YyeC1hZ2VudC1iYWNrZW5kLTAuMS4wLmphcgpTdWNjZXNzRXhpdFN0YXR1cz0xNDMKUmVzdGFydD1vbi1mYWlsdXJlClJlc3RhcnRTZWM9MTAKU3RhbmRhcmRPdXRwdXQ9am91cm5hbApTdGFuZGFyZEVycm9yPWpvdXJuYWwKTGltaXROT0ZJTEU9NjU1MzYKCltJbnN0YWxsXQpXYW50ZWRCeT1tdWx0aS11c2VyLnRhcmdldAo=' | base64 -d > /etc/systemd/system/dst-v2x-agent-backend.service

echo 'W1VuaXRdCkRlc2NyaXB0aW9uPWRzdC12MngtYWdlbnQgZnJvbnRlbmQgKE51eHQgNCBTU1IgKyBBUEkgcHJveHkpCkFmdGVyPW5ldHdvcmstb25saW5lLnRhcmdldCBkc3QtdjJ4LWFnZW50LWJhY2tlbmQuc2VydmljZQpXYW50cz1uZXR3b3JrLW9ubGluZS50YXJnZXQKUmVxdWlyZXM9ZHN0LXYyeC1hZ2VudC1iYWNrZW5kLnNlcnZpY2UKCltTZXJ2aWNlXQpUeXBlPXNpbXBsZQpXb3JraW5nRGlyZWN0b3J5PS9kYXRhL3NlcnZpY2UvZHN0LXYyeC1hZ2VudC9mcm9udGVuZApFbnZpcm9ubWVudD1OT0RFX0VOVj1wcm9kdWN0aW9uCkVudmlyb25tZW50PUhPU1Q9MC4wLjAuMApFbnZpcm9ubWVudD1QT1JUPTMwMDAKRXhlY1N0YXJ0PS91c3IvYmluL25vZGUgLm91dHB1dC9zZXJ2ZXIvaW5kZXgubWpzClJlc3RhcnQ9b24tZmFpbHVyZQpSZXN0YXJ0U2VjPTUKU3RhbmRhcmRPdXRwdXQ9am91cm5hbApTdGFuZGFyZEVycm9yPWpvdXJuYWwKCltJbnN0YWxsXQpXYW50ZWRCeT1tdWx0aS11c2VyLnRhcmdldAo=' | base64 -d > /etc/systemd/system/dst-v2x-agent-frontend.service

# 验证写入结果（应看到完整 [Unit]/[Service] 段落）
cat /etc/systemd/system/dst-v2x-agent-backend.service
cat /etc/systemd/system/dst-v2x-agent-frontend.service
systemctl daemon-reload
```

### 5.2 unit 内容参考（app 用户部署版：手工创建或审查时看这里，root 部署直接用 5.1 命令）

#### 后端 `/etc/systemd/system/dst-v2x-agent-backend.service`

```ini
[Unit]
Description=dst-v2x-agent backend (Spring Boot + AgentScope)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=app
Group=app
WorkingDirectory=/data/service/dst-v2x-agent/backend
# profile 默认即 local（bootstrap.properties），直连 Nacos local namespace 的现成配置，无需设置；
# 仅当要切 test/dev namespace 时取消下一行注释
# Environment=SPRING_PROFILES_ACTIVE=test
ExecStart=/usr/bin/java -Xms512m -Xmx1024m -Dfile.encoding=UTF-8 -jar target/v2x-agent-backend-0.1.0.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=10
# 日志：应用自身写 backend/logs/backend.log；stdout/stderr 兜底入 journald
StandardOutput=journal
StandardError=journal
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
```

#### 前端 `/etc/systemd/system/dst-v2x-agent-frontend.service`

```ini
[Unit]
Description=dst-v2x-agent frontend (Nuxt 4 SSR, 含 API 网关代理)
After=network-online.target dst-v2x-agent-backend.service
Wants=network-online.target
Requires=dst-v2x-agent-backend.service

[Service]
Type=simple
User=app
Group=app
WorkingDirectory=/data/service/dst-v2x-agent/frontend
Environment=NODE_ENV=production
Environment=HOST=0.0.0.0
Environment=PORT=3000
ExecStart=/usr/bin/node .output/server/index.mjs
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

### 启动

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now dst-v2x-agent-backend
sudo systemctl enable --now dst-v2x-agent-frontend
sudo systemctl status dst-v2x-agent-backend --no-pager
sudo systemctl status dst-v2x-agent-frontend --no-pager
```

> 若 java/node 不在 `/usr/bin`，先用 `which java` / `which node` 确认实际路径并修改 `ExecStart`。
> root 部署已由 5.1 的 base64 命令处理（unit 无 `User=app`）；仅当改用 app 用户部署时，才在两个 unit 的 `[Service]` 段补 `User=app` / `Group=app` 两行，并确保构建产物归 app 用户所有。

---

## 6. 防火墙与端口

| 端口 | 用途 | 开放范围 |
|---|---|---|
| 3000 | 前端统一入口（页面 + API 代理） | **对使用方开放** |
| 8080 | 后端（仅本机回环被前端调用） | 不对外（运维调试可临时开） |

```bash
# firewalld 示例
sudo firewall-cmd --permanent --add-port=3000/tcp && sudo firewall-cmd --reload
```

出方向需能访问：Nacos(8848)、MySQL(30308)、Doris(9030)、Redis(30689)、LLM API 域名(open.bigmodel.cn 等)、GitLab(gitlab.dstcar.com:22)。

---

## 7. 启动验证

```bash
# 1. 后端健康检查（约等 30~60s 完成启动与 Flyway 迁移）
curl http://127.0.0.1:8080/api/health

# 2. 启动日志确认（关键行）
grep -E "Located property source|Started .*Application|Flyway" \
  /data/service/dst-v2x-agent/backend/logs/backend.log | tail -5
# 预期看到 bootstrapProperties-dst-v2x-agent.properties —— 说明 Nacos 配置已加载

# 3. 前端页面 + 代理链路（经 3000 应能拿到后端健康响应）
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:3000/            # 预期 200/302
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:3000/api/health  # 预期 200
```

浏览器访问 `http://<SERVER_IP>:3000/`，使用演示账号登录：

| 账号 | 密码 | 数据范围 |
|---|---|---|
| admin | admin123 | 全量（不过滤） |
| op1 | op123456 | 仅车队 F001 |
| op2 | op123456 | 仅车队 F002 |

验证对话链路：登录后进入对话页，问一个示例问题（如「F001 车队近 7 天里程趋势」），应看到工具调用 → 表格/图表 → 结论的完整事件流。

可选：跑端到端回归（在服务器上）：

```bash
cd /data/service/dst-v2x-agent/scripts
python3 smoke_test.py                 # 8 场景冒烟（默认打 http://localhost:8080）
python3 security_test.py              # 越权/拒答安全用例
python3 eval_regression.py            # 30 条样例回归
# 经 3000 端口跑：V2X_BASE=http://127.0.0.1:3000 python3 smoke_test.py
```

---

## 8. 日志、升级与运维

### 日志位置

| 日志 | 路径 | 说明 |
|---|---|---|
| 后端应用日志 | `/data/service/dst-v2x-agent/backend/logs/backend.log` | 滚动文件 |
| 后端启动/崩溃输出 | `journalctl -u dst-v2x-agent-backend -f` | systemd 兜底 |
| 前端 SSR 日志 | `journalctl -u dst-v2x-agent-frontend -f` | |
| 前端访问后端异常 | 同上（代理 502 时看后端是否存活） | |

### logrotate（可选）

`/etc/logrotate.d/dst-v2x-agent`：

```
/data/service/dst-v2x-agent/backend/logs/*.log {
    daily
    rotate 14
    compress
    missingok
    notifempty
    copytruncate
}
```

### 升级流程（一切走 git）

```bash
# 以 app 用户执行
cd /data/service/dst-v2x-agent
git pull --ff-only

# 后端：重新打包 + 重启
cd backend && mvn clean package -DskipTests && cd ..
sudo systemctl restart dst-v2x-agent-backend

# 前端：重新构建 + 重启
cd frontend && npm ci && npm run build && cd ..
sudo systemctl restart dst-v2x-agent-frontend

# 验证
sleep 30
curl -fsS http://127.0.0.1:3000/api/health && echo " UPGRADE OK"
```

可把上述步骤固化为 `/data/service/dst-v2x-agent/update.sh`（仓库外或加入仓库均可），并为 app 用户配置 systemctl 免密：

```
# /etc/sudoers.d/dst-v2x-agent（visudo -f 编辑）
app ALL=(root) NOPASSWD: /bin/systemctl restart dst-v2x-agent-backend, /bin/systemctl restart dst-v2x-agent-frontend
```

### 常用运维命令

```bash
sudo systemctl restart dst-v2x-agent-backend     # 重启后端（改 Nacos LLM 配置后必须）
sudo systemctl restart dst-v2x-agent-frontend
journalctl -u dst-v2x-agent-backend --since "10 min ago" --no-pager
```

---

## 9. 常见问题（FAQ）

| 现象 | 原因 | 处置 |
|---|---|---|
| 启动日志无 `Located property source` | 服务器连不上 Nacos，或 namespace/dataId 不存在 | 按第 4 节检查连通性与配置发布；`fail-fast=false` 会导致带病启动，务必看日志 |
| 对话全拒答 / 报 LLM 401 | Nacos 中 `llm.active` 或 API Key 配置有误/失效 | 检查 Nacos 中 `llm.active` 与 `llm.providers.*.api-key`；**改完必须重启后端** |
| 页面能开但对话/图表请求 404 | 前端跑了旧构建（无 routeRules 代理的版本） | `cd frontend && npm run build` 后重启前端服务 |
| 经 3000 访问 API 返回 502 | 后端进程未起或正在重启 | `systemctl status dst-v2x-agent-backend`；看 backend.log |
| 对话事件流卡住 | 后端处理慢或中间链路缓冲 | Nitro 代理为流式直转，一般无缓冲问题；排查后端日志与 `agent.run.hard-deadline-ms` |
| Redis 报 AUTH 错误 | ACL 服务只发密码被拒 | Nacos 配置已含 `username=default`；若换自建 Redis，注意 ACL 服务必须显式 default |
| 连接控制库失败启动阻塞 | MySQL 地址/密码错误或网络不通 | `nc -vz <host> <port>` 验证；控制库历史上有闪断，观察是否自愈 |
| `UnsupportedClassVersionError` | JDK 版本低于 21 | 安装 JDK 21 并确认 `java -version` |
| mvn 拉不到 io.agentscope 依赖 | 未配置公司私服 settings | 按第 3.2 节配置 `~/.m2/settings.xml` |
| git pull 报权限/连不上 | app 用户无 GitLab SSH Key | `ssh -T git@gitlab.dstcar.com` 验证，公钥加到 GitLab |
| 前后端要分机部署 | routeRules 默认代理 `localhost:8080` | 改 `frontend/nuxt.config.ts` 中 proxy 目标为后端机地址，重新 build；同时后端 8080 需对前端机开放 |
| Doris 查询慢/超时 | 大表（千万行）无过滤条件 | 检查提问是否带车队/时间范围；Nacos 中 `agent.run.hard-deadline-ms` 可调 |
| `npm run build` 报 Cannot find native binding（`@oxc-parser/binding-linux-x64-gnu`） | **Node 版本 < 22.12**（如 22.11.0）：原生绑定是 optional 依赖且 engines 要求 `^20.19.0 \|\| >=22.12.0`，npm 对 engines 不满足的 optional 依赖会**静默跳过安装**——`npm ci` 也照跳，删 node_modules 重装无效 | 按第 2 节升级 Node ≥ 22.12（如 22.21.1），再 `cd frontend && rm -rf node_modules && npm ci && npm run build`；`ls node_modules/@oxc-parser/` 出现 `binding-linux-x64-gnu` 即已修复 |

---

## 10. 可选：模拟数据模式

生产默认连真实 Doris（`copilot.datasource=real`）。若目标环境无 Doris 真实库，可切换模拟模式：

```bash
# 1. 在 Doris 建模拟库（幂等，seed=42，9 表约 8.6 万行）
python3 scripts/sim/seed_sim_doris.py   # 需能连 Doris，参数见脚本头部

# 2. Nacos 配置中设置
# copilot.datasource=sim
# 3. 重启后端生效
```

故事线数据（粤BD96880 电池故障、F002 疲劳告警等）以模拟库 `MAX(dt)` 为基准日，不随真实日期漂移。
