# Geek Monitor

面向自托管运维的监控平台，中心服务使用 Kotlin、Quarkus 和 PostgreSQL，管理端使用 Flutter Web。

产品范围和首版验收要求见[产品与技术规划](docs/产品与技术规划.md)。

## 本地开发

需要 Java 21、Flutter stable 和 PostgreSQL。先创建开发数据库（默认连接参数为 `geek_monitor/geek_monitor`），然后启动 API：

```sh
createdb geek_monitor
./gradlew quarkusDev
```

Quarkus 会在启动时执行数据库迁移。首次打开 `http://localhost:8080` 会进入安装向导，创建管理员和默认项目。Flutter 调试模式的安装页和登录页预填测试账号：

- 用户名：`admin@biliwind.com`
- 密码：`admin@biliwind.com`

生产构建不会包含这组预填值，首次安装时由管理员自行设置。管理员密码至少 12 位；数据库只保存 PBKDF2 哈希。

要单独调试 Flutter 页面，可在 API 已运行时执行：

```sh
cd admin-flutter
flutter run -d web-server --web-hostname 0.0.0.0 --web-port 8081
```

Flutter 开发服务器的地址已加入开发 CORS 白名单。执行 `./gradlew build` 会构建并将 Flutter Web 静态文件打入 Quarkus 应用。

## 数据库连接配置

可通过环境变量覆盖本地默认值：

| 变量 | 默认值 |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/geek_monitor` |
| `DB_USERNAME` | `geek_monitor` |
| `DB_PASSWORD` | `geek_monitor` |

## 通知配置

通知渠道按项目管理。Webhooks 只允许 HTTPS；开发模式为了本地验收可访问已放行的内网目标。生产环境默认拒绝私网目标，如确需访问自管内网接收端，可通过 `MONITOR_ALLOWED_CIDRS` 显式列出网段。

生产环境创建通知渠道前必须配置独立的 AES-256 密钥。生成 32 字节密钥并以 Base64 保存到部署密钥管理器：

```sh
openssl rand -base64 32
```

将结果注入 `GEEK_MONITOR_ENCRYPTION_KEY`。密钥不应写入仓库或数据库备份；丢失密钥后，已加密的 Webhook 配置无法解密。开发模式使用仅供本地的固定密钥。

邮件使用 Quarkus Mailer 和 SMTP，配置入口是服务端环境变量，不在管理页面中。容器部署时，复制 `.env.example` 为 `.env` 并填写 `SMTP_HOST`、`SMTP_PORT`、`SMTP_USERNAME`、`SMTP_PASSWORD`、`SMTP_FROM` 和 `SMTP_START_TLS`；本地 Gradle 开发时需在启动 `./gradlew quarkusDev` 的终端导出这些变量，Quarkus 不会自动读取 `.env`。开发模式默认使用邮件模拟器；要在本地实际投递，还需设置 `QUARKUS_MAILER_MOCK=false` 并重启服务。生产默认要求 STARTTLS，未配置主机时会指向保留的 `.invalid` 域名，避免静默发送。具体步骤见[部署手册](docs/deployment.md#smtp-配置)。

首次安装状态保存在数据库中。重新部署不会重置管理员或项目；重置操作需按备份与恢复流程直接管理数据库。

完整的容器部署、升级、备份与恢复步骤见[部署手册](docs/deployment.md)。开发环境也可使用 `podman compose` 或 `docker compose` 启动 PostgreSQL 与应用。

## 当前管理端能力

- 首次安装、管理员登录、会话恢复和退出。
- 创建项目并维护基本项目说明。
- 管理项目成员并调整账号角色；项目授权边界由服务端校验。
- 在项目内创建、查看和软删除监控资源。
- 配置 HTTP GET/HEAD 与显式确认、无请求正文的安全 POST，支持 AES-GCM 加密的自定义请求头和限制到项目及精确源站的共享 HTTP 凭据；HTTP 可用正文包含及 JSON Pointer 断言。另支持 TCP、DNS（A/AAAA/CNAME/MX/TXT/NS/SRV/PTR）和 TLS 证书检查，TLS 证书支持 30/14/7 天分级提醒，并提供指定 DNS 解析器、记录断言、立即试运行、历史记录及失败/恢复阈值。
- 在故障中心确认故障、填写处理备注并查看事件时间线。
- 按项目配置邮件和 Webhook 渠道；故障及恢复消息通过持久化 Outbox 发送，记录每次投递并退避重试。同类事件在 15 秒内合并，可设置每个渠道的每分钟发送上限。
- 总览显示资源状态、监控项和活跃故障。
- 检查历史弹窗显示最近执行记录，以及 24 小时、7 天、30 天和 180 天的可用率、数据覆盖率、UP/DOWN/UNKNOWN 时长和维护/暂停剔除统计。
- 系统设置页展示数据库与迁移状态、数据保留策略、最近本机备份状态及脱敏审计日志；备份与空库恢复脚本见部署手册。
- HOST 资源支持 Linux Agent 一次性注册、GitHub Release 安装命令、节点在线/离线状态、凭据吊销和 CPU/内存/磁盘等可信指标曲线；Agent 离线队列按顺序补传。安装方式见 [Agent 指南](agent/README.md)。
- DATABASE 资源支持 PostgreSQL、MySQL 和 Redis 只读可用性检查、连接数/上限/利用率曲线，以及指标阈值告警；统计权限不足时不会误判数据库不可用。最小账号权限见[数据库监控权限说明](docs/database-monitoring-permissions.md)。
- 心跳任务和 HOST/DATABASE 指标阈值可在控制台配置。

可用率统计、数据库备份与空库恢复已实现。真实 SMTP 投递、异机备份恢复、目标硬件与规模压测、至少 7 天连续试运行仍待验收，详见[产品与技术规划](docs/产品与技术规划.md)和[部署手册](docs/deployment.md)。
