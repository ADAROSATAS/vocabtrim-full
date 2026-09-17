# 部署

仓库提供 Docker Compose 配置，可以同时启动 MySQL、Redis、Spring Boot 后端和 Nginx 前端入口。默认部署拓扑只有 Nginx 暴露宿主机端口，浏览器不会直接连接后端容器、MySQL 或 Redis。

```text
Browser
   │
   ▼
Nginx :80
├── /       Vue 静态文件
└── /api    Spring Boot :8080
                 ├── MySQL :3306（账号与 snapshot）
                 └── Redis :6379（HTTP Session）
```

## Docker Compose

先运行 `cp .env.example .env` 创建本地环境变量文件。`.env` 中包含数据库名、应用数据库账号、root 密码、Redis 密码以及 Session Cookie 的 Secure 开关。将三个示例密码替换为自己的随机密码，实际密码只保存在已被 Git 忽略的环境变量文件中。`REDIS_PASSWORD` 为空或缺失时 Compose 会报错。

```dotenv
MYSQL_DATABASE=vocabtrim
MYSQL_USER=vocabtrim
MYSQL_PASSWORD=change-me-app-password
MYSQL_ROOT_PASSWORD=change-me-root-password
REDIS_PASSWORD=change-me-redis-password
SESSION_COOKIE_SECURE=false
```

完成配置后先运行 `docker compose config --quiet` 验证配置，再运行 `docker compose up --build -d` 启动服务。不要将包含实际密码的完整 `docker compose config` 输出分享出去。

可以使用下面的命令查看运行状态和日志：

```bash
docker compose ps
docker compose logs -f
```

停止服务时执行 `docker compose down`。MySQL 数据保存在名为 `mysql_data` 的 Docker volume 中。普通 `docker compose down` 不会删除该 volume；如果执行带 `-v` 的删除命令，则数据库数据也会被移除。

## 服务之间的连接

`mysql` 服务使用 MySQL 8.4，`redis` 使用官方镜像 `redis:8.2.9-alpine`（Redis Open Source 8.2 Extended release，见 [官方版本政策](https://redis.io/docs/latest/operate/oss_and_stack/install/version-mgmt/)）。两者均提供 healthcheck；`backend.depends_on` 对两者都使用 `condition: service_healthy`，等待健康后才启动。后端通过内部服务名 `mysql`、`redis` 连接。`web` 容器运行 Nginx，提供已经构建好的 Vue 静态文件，并把 `/api/` 转发到 `backend:8080`。

Compose 不发布 MySQL 的 3306 或 Redis 的 6379 到宿主机，两者只通过 Docker 网络访问。后端也没有单独映射 8080 端口，外部请求统一经过 Nginx。Redis 的访问密码由 `REDIS_PASSWORD` 环境变量传入，启动命令启用密码认证；健康检查使用 `REDISCLI_AUTH` 传递密码。

## Redis Session 的生命周期

Redis 只保存 HTTP Session，包含认证所需的用户标识和权限；认证成功后密码哈希已被 Spring Security 擦除。它不保存或缓存 users、snapshot、词表等业务数据。访问密码用于限制会话数据的读写，内部网络不替代密码认证。

启动参数 `--save '' --appendonly no` 明确关闭 RDB 和 AOF，Compose 不配置 Redis 数据 volume。Session 可丢失，Redis 重启或重建后用户重新登录即可，MySQL 和 IndexedDB 的业务数据不受影响。仅重启 Java 后端，在 Redis 会话仍有效、序列化兼容时可以继续登录。Redis 故障期间，需要会话的请求可能失败；应用不回退到内存会话。

唯一的会话闲置超时是 `server.servlet.session.timeout: 30d`，不要额外配置 `spring.session.timeout`。Cookie 沿用 `VOCABTRIM_SESSION`，30 天是服务端闲置期限，并非浏览器 Cookie 的持久保存期限。

首次从内存 Session 切换到 Redis 后，已有用户需要重新登录。后续应用/框架升级若不兼容旧的 Java Serialization 会话，应在部署前将 `spring.session.data.redis.namespace`（当前为 `vocabtrim:session:v1`）换成新的前缀，例如 `vocabtrim:session:v2`，并让所有后端实例使用同一前缀。旧 Cookie 随后的请求会按未登录处理，旧 key 等待 TTL 到期即可；无需清空 Redis，也不要为此修改业务数据库。显式 `serialVersionUID` 不保证任意升级兼容。

## 本地后端集成测试

测试需要真实 MySQL 和 Redis。以下 Bash 命令从仓库根目录执行；使用独立的 `vocabtrim-test` Compose 项目，不复用实际部署的数据卷。先复制 `.env.example` 为 `.env.test.local`，替换三个密码，并将 `MYSQL_DATABASE` 改为 `vocabtrim_test`。该文件匹配 Git 忽略规则 `*.local`。

```bash
docker compose --env-file .env.test.local -p vocabtrim-test up -d --wait mysql redis
docker run --rm --network vocabtrim-test_default --env-file .env.test.local \
  -v "$PWD/backend:/app" -w /app maven:3.9-eclipse-temurin-21 \
  sh -c 'export DB_URL="jdbc:mysql://mysql:3306/${MYSQL_DATABASE}?allowPublicKeyRetrieval=true&useSSL=false" DB_USERNAME="$MYSQL_USER" DB_PASSWORD="$MYSQL_PASSWORD" SESSION_COOKIE_SECURE=false; exec mvn -B test'
```

Maven 在容器内执行全部后端测试，Redis 不发布宿主机端口。只运行 Session 集成测试时，可把最后的 `mvn -B test` 改成 `mvn -B -Dtest=RedisSessionIntegrationTest test`。该测试仅演示一条基本流程：准备账号、获取 CSRF token、通过随机端口真实登录，再从 Redis 读回 Session，验证用户身份及密码哈希擦除，最后清理测试数据。

完成后，只清理上述测试项目（`--volumes` 会删除这个测试项目的 MySQL 数据）：

```bash
docker compose --env-file .env.test.local -p vocabtrim-test down --volumes
```

CI 使用同一种真实服务测试方式：Java job 容器连接 MySQL/Redis service，不发布 Redis 端口，不引入 Testcontainers。GitHub Actions service 不能直接覆盖镜像启动命令，因此 CI 在执行测试前安装 `redis-tools`，用 `CONFIG SET` 关闭 RDB/AOF 并设置测试专用密码；这些固定 CI 凭据仅用于临时测试服务，不是部署密码。

## 上传大小和超时

Nginx 设置了 `client_max_body_size 64m`，与 Java 应用的 snapshot 大小上限一致。反向代理的连接超时为 15 秒，读写超时为 120 秒。超过 64 MiB 的同步数据会在入口或应用层被拒绝，而不是继续无限接收。

MySQL 容器同时设置 `--max-allowed-packet=128M`，为最大 64 MiB 的 snapshot 和协议开销留出余量。

## HTTPS 与 Session Cookie

仓库中的 Nginx 配置默认只监听 80 端口，并不负责申请或终止 TLS。公开部署时可以在它前面使用支持 HTTPS 的反向代理、负载均衡器或 CDN，也可以自行扩展 Nginx 配置完成 TLS 终止。

本地 HTTP 调试时保持 `SESSION_COOKIE_SECURE=false`；当用户实际通过 HTTPS 访问站点时，应改为 `SESSION_COOKIE_SECURE=true`。这样 `VOCABTRIM_SESSION` Cookie 只会通过安全连接发送。生产环境还应限制对外开放的端口，并为数据库使用随机且足够强的密码。

## 数据库迁移与备份

Spring Boot 启动时由 Flyway 自动执行数据库 migration，初始脚本位于 `backend/src/main/resources/db/migration/`。部署新版本时，如果数据库结构发生变化，应随应用一起发布新的 migration 文件。

数据库的持久数据位于 `mysql_data` volume。需要长期保存用户数据的部署环境应定期备份该 volume 或使用数据库自身的备份方案。应用只保留每个用户最近两个 snapshot 版本，因此数据库备份仍然是防止误删、磁盘损坏或部署事故的数据保护手段。

## 更新应用

获取新代码并确认环境变量没有变化后，可以运行 `docker compose up --build -d` 重新构建并启动容器。Flyway 会在新的后端容器启动时检查并执行尚未应用的 migration。更新完成后可以通过 `docker compose ps` 和 `docker compose logs` 确认四个服务都正常运行。
