# 部署

仓库提供 Docker Compose 配置，可以同时启动 MySQL、Spring Boot 后端和 Nginx 前端入口。默认部署拓扑只有 Nginx 暴露宿主机端口，浏览器不会直接连接后端容器或 MySQL。

```text
Browser
   │
   ▼
Nginx :80
├── /       Vue 静态文件
└── /api    Spring Boot :8080
                 │
                 ▼
              MySQL :3306
```

## Docker Compose

先运行 `cp .env.example .env` 创建本地环境变量文件。`.env` 中包含数据库名、应用数据库账号、root 密码以及 Session Cookie 的 Secure 开关。用于实际部署时应至少修改两个默认密码。

```dotenv
MYSQL_DATABASE=vocabtrim
MYSQL_USER=vocabtrim
MYSQL_PASSWORD=change-me-app-password
MYSQL_ROOT_PASSWORD=change-me-root-password
SESSION_COOKIE_SECURE=false
```

完成配置后运行 `docker compose up --build -d` 启动服务。

可以使用下面的命令查看运行状态和日志：

```bash
docker compose ps
docker compose logs -f
```

停止服务时执行 `docker compose down`。MySQL 数据保存在名为 `mysql_data` 的 Docker volume 中。普通 `docker compose down` 不会删除该 volume；如果执行带 `-v` 的删除命令，则数据库数据也会被移除。

## 服务之间的连接

`mysql` 服务使用 MySQL 8.4，并通过 healthcheck 判断数据库是否已经可以接受连接。`backend` 会等待数据库健康后启动，并使用内部服务名 `mysql` 建立 JDBC 连接。`web` 容器运行 Nginx，提供已经构建好的 Vue 静态文件，并把 `/api/` 转发到 `backend:8080`。

Compose 没有把 MySQL 的 3306 端口映射到宿主机，因此数据库默认只在 Docker 网络中可访问。后端也没有单独映射 8080 端口，外部请求统一经过 Nginx。

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

获取新代码并确认环境变量没有变化后，可以运行 `docker compose up --build -d` 重新构建并启动容器。Flyway 会在新的后端容器启动时检查并执行尚未应用的 migration。更新完成后可以通过 `docker compose ps` 和 `docker compose logs` 确认三个服务都正常运行。
