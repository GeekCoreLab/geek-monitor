# Geek Monitor 部署、备份与恢复

## 首次部署

需要 Podman Compose 或 Docker Compose、Java 21、Flutter stable，以及 PostgreSQL 17 客户端工具（`pg_dump`、`pg_restore`、`psql`）。先准备私有环境文件并生成应用加密密钥：

```sh
cp .env.example .env
openssl rand -base64 32
chmod 600 .env
```

将随机生成的 32 字节 Base64 值写入 `.env` 的 `GEEK_MONITOR_ENCRYPTION_KEY`，同时替换 PostgreSQL 密码。`.env` 已由 Git 忽略，不要提交或放进数据库备份。容器部署的 SMTP 配置也写在这个项目根目录的 `.env` 文件中；变量名见 `.env.example`。默认 `.invalid` 主机不会发送邮件。

构建 Flutter Web 与 Quarkus 应用，再启动 PostgreSQL 和应用：

```sh
./gradlew build
podman compose up -d --build
```

容器将应用绑定到 `127.0.0.1:8080`，PostgreSQL 只发布到本机 `127.0.0.1:55432`；对公网提供服务时，在前面配置带 TLS 的反向代理并限制数据库端口访问。应用启动时执行 Flyway 迁移。首次打开站点后使用安装页创建管理员和默认项目；生产构建不会预填开发测试账号。

## SMTP 配置

SMTP 是服务端环境配置，不在管理页面中设置。若尚未创建 `.env`，从模板创建并限制文件权限：

```sh
if [ ! -f .env ]; then cp .env.example .env; fi
chmod 600 .env
```

编辑 `.env` 中的 `SMTP_HOST`、`SMTP_PORT`、`SMTP_USERNAME`、`SMTP_PASSWORD`、`SMTP_FROM` 和 `SMTP_START_TLS`。通常使用端口 `587` 和 `REQUIRED`；请以 SMTP 服务商给出的主机名、认证方式和发件人地址为准。Compose 会把这些值传给应用并启用真实邮件发送；修改后执行 `podman compose up -d --no-deps app`（或 `docker compose up -d --no-deps app`）让应用容器载入新配置。

直接运行 `./gradlew quarkusDev` 时，Quarkus 不会自动加载 `.env`，而且开发配置默认启用邮件模拟器。要在本地开发模式投递真实邮件，可在项目根目录执行：

```sh
set -a
. ./.env
set +a
unset GEEK_MONITOR_ENCRYPTION_KEY
DB_URL=jdbc:postgresql://localhost:55433/geek_monitor \
QUARKUS_HTTP_PORT=18082 QUARKUS_MAILER_MOCK=false ./gradlew quarkusDev
```

修改 SMTP 参数后需要重启正在运行的开发服务；不要把 `.env` 内容或 SMTP 密码提交到 Git。

平台存活和就绪探针分别为 `/q/health/live` 与 `/q/health/ready`。就绪探针包含 PostgreSQL 数据源状态。反向代理可以将就绪探针用于摘除暂不可用的应用实例。

发布新版本前先备份。完成 `./gradlew build` 后，只重建并更新应用服务：

```sh
podman compose build app
podman compose up -d --no-deps app
```

PostgreSQL 数据保存在 `geek_monitor_postgres` 卷中；更新应用时不要删除该卷。Flyway 负责向前迁移；回滚应用版本前先确认旧版本兼容新 schema，数据库恢复应使用独立目标库和下方恢复脚本。

## 备份

备份使用 PostgreSQL custom 格式，并生成 SHA-256 校验文件。准备一个权限为 `0600` 的 `.pgpass` 文件，内容使用实际数据库主机、端口、数据库名、用户和密码，然后设置连接环境：

```sh
export PGHOST=127.0.0.1 PGPORT=55432 PGUSER=geek_monitor PGDATABASE=geek_monitor
export PGPASSFILE="$HOME/.pgpass"
BACKUP_DIR=/srv/backups/geek-monitor ./scripts/backup-postgres.sh
```

脚本将备份目录限制为 `0700`、备份文件限制为 `0600`，并用 `pg_restore --list` 验证归档结构。应用已运行 V22 迁移后，脚本还会在数据库中记录最新归档的文件名、大小、SHA-256 和完成时间，供系统设置页显示 24 小时新鲜度；这只证明本机脚本完成归档和校验，不证明异机副本存在或恢复已成功。将备份复制到异机或加密存储，并按组织策略限制保留期。备份包含数据库中的加密凭据，但不包含 `GEEK_MONITOR_ENCRYPTION_KEY`；必须在独立密钥管理器中保留该密钥，否则恢复后已有数据库凭据无法解密。

## 恢复演练

先创建一个空的目标数据库，再让 `PGDATABASE` 指向它。脚本校验校验和、归档格式、目标库为空，并要求确认值与目标库名称完全相同；它不会清空或覆盖已有表：

```sh
createdb geek_monitor_restore
PGDATABASE=geek_monitor_restore \
RESTORE_CONFIRM_DATABASE=geek_monitor_restore \
./scripts/restore-postgres.sh /srv/backups/geek-monitor/geek-monitor-YYYYMMDDTHHMMSSZ.dump
```

恢复后，将应用连到目标库，提供与备份匹配的 `GEEK_MONITOR_ENCRYPTION_KEY`，确认 Flyway 状态、`/q/health/ready`、管理员登录、资源/监控历史和加密通知渠道。恢复验证通过后再安排生产切换。初始恢复目标为 RPO 24 小时、RTO 2 小时；达到该目标仍需持续备份并定期计时演练。

## Agent

Agent 使用独立 systemd 服务和受限服务账户安装，断网队列文件及凭据目录应保持 `0700`，状态文件为 `0600`。注册、升级、卸载与重注册方式见[Agent 指南](../agent/README.md)。
