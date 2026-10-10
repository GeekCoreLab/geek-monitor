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

首次安装状态保存在数据库中。重新部署不会重置管理员或项目；重置操作需按备份与恢复流程直接管理数据库。

## 当前管理端能力

- 首次安装、管理员登录、会话恢复和退出。
- 创建项目并维护基本项目说明。
- 在项目内创建、查看和软删除监控资源。
- 总览显示资源状态计数和项目列表。

主动检查调度、检查项配置、事件处理、通知、Agent 与指标页面仍按规划阶段继续实现。
