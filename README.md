# VocabTrim Full

VocabTrim Full 是一个用于快速筛选词表的 Web 应用。用户可以导入本地词表，在浏览器中逐词查看并标记保留或去除，最后导出筛选后的新词表。Full 版在原有本地使用方式上增加了账号系统和手动云端同步，因此同一套部署可以供多个彼此独立的用户使用。

词表处理仍然采用 local-first 方式。导入、浏览、标记、排序、打乱、批量处理和导出都在浏览器中完成，日常操作不依赖后端请求。服务器主要负责用户认证，以及在用户主动执行“上传同步”或“下载同步”时保存和读取完整 snapshot。

## 功能

- 注册、登入、登出，隔离用户间数据，隔离本地与云端数据。
- 导入 JSON 词表，支持数组、单对象、常见 wrapper 结构、NDJSON。
- 查看单词及其音义详情，保留当前游标位置。
- 标记单词的保留或去除。
- 词表内的词的排序、打乱、批量标记。
- 词表的复制、重命名、删除。
- 根据“保留”标记生成新的词表，并按原词表结构导出。
- 使用 IndexedDB 保存当前用户的本地 snapshot，刷新页面后可以继续之前的进度。
- 手动上传或下载完整 snapshot，并通过 ETag 检测多设备之间的版本冲突。

## 技术栈

后端使用 Java 21、Spring Boot 4.1、Spring MVC、Spring Security、Spring Session、Redis、MyBatis、MySQL 8.4 和 Flyway。Redis 使用官方镜像 `redis:8.2.9-alpine`，属于 Redis Open Source 8.2 Extended release，仅保存 HTTP Session。前端使用 Vue 3、TypeScript、Vue Router、Pinia 和 Vite，本地数据保存在 IndexedDB 和 localStorage 中。生产部署由 Nginx 提供静态文件并反向代理 `/api`，仓库同时提供 Docker Compose、JUnit、Vitest 和 GitHub Actions 配置。

## 快速启动

准备 Docker 和 Docker Compose 后，复制环境变量示例，将数据库和 Redis 的示例密码换成自己的密码，然后启动全部服务（不要提交 `.env`）：

```bash
cp .env.example .env
docker compose up --build
```

默认可以通过 `http://localhost` 访问应用。首次使用时先注册账号，再登录进入词表界面。

本地 HTTP 环境应保持 `SESSION_COOKIE_SECURE=false`。如果应用部署在 HTTPS 环境中，应将其改为 `true`。

Redis 不发布宿主机端口，后端会等待 MySQL 和 Redis 健康后启动。Session 是临时状态：Redis 明确关闭 RDB/AOF，Compose 不配置 Redis 数据卷；Redis 重启或重建后需重新登录，MySQL 和 IndexedDB 中的业务数据不受影响。

## 本地开发

为保持 Redis 仅在 Docker 内网可访问，本地开发也在容器中运行后端；先按上文准备 `.env`，再从仓库根目录运行：

```bash
docker compose build backend
docker compose run --rm -p 8080:8080 backend
```

这会启动所需的 MySQL/Redis，并将开发后端映射到 `http://localhost:8080`。修改 Java 代码后需重新构建、运行。数据库表由 Flyway 在应用启动时自动创建和迁移。连接配置使用 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`REDIS_HOST`、`REDIS_PASSWORD`；其中 Redis 密码必须提供。

前端要求 Node.js 24：

```bash
cd frontend
npm install
npm run typecheck
npm run test:run
npm run dev
```

Vite 默认监听 `http://localhost:5173`，开发环境中的 `/api` 请求会代理到 `http://localhost:8080`。

## 数据与同步

登录后，Pinia 保存当前页面使用的运行时状态，IndexedDB 保存当前用户的本地完整 snapshot，localStorage 保存该用户上一次看到的云端 ETag。服务器端 MySQL 只保存用户主动上传的 snapshot，因此“本地已保存”和“已同步到云端”是两个不同状态。

普通上传需要携带客户端最后一次看到的云端版本。首次上传使用 `If-None-Match: *`，之后使用 `If-Match: <etag>`。如果另一台设备已经更新了云端数据，服务器返回 `409 SYNC_CONFLICT`，前端不会自动覆盖；只有用户明确确认后，才会发起强制上传。每个用户的服务器端 snapshot 只保留最近两个版本，单个 snapshot 最大为 64 MiB。

更完整的实现说明见 [架构文档](docs/ARCHITECTURE.md) 和 [同步文档](docs/SYNC.md)。部署相关配置见 [部署文档](docs/DEPLOYMENT.md)。

## 测试与 CI

后端的 `mvn test` 包括原有单元测试和真实 HTTP/MySQL/Redis 的 Session 集成测试，需要可连接的 MySQL 和带密码的 Redis。请按 [部署文档中的隔离测试步骤](docs/DEPLOYMENT.md#本地后端集成测试) 在 Docker 内网运行，使用独立测试项目和测试数据库。

集成测试保留基本流程：准备测试账号、获取 CSRF token、真实 HTTP 登录，再从 Redis 读回 Session，验证用户身份和密码哈希已被擦除。测试结束后只删除自己创建的账号和会话。

前端在提交前可以依次执行类型检查、测试和构建：

```bash
cd frontend
npm run typecheck
npm run test:run
npm run build
```

GitHub Actions 配置位于 `.github/workflows/ci.yml`。后端 job 和 MySQL/Redis service 都运行在容器内，通过服务名连接，不发布 Redis 宿主机端口，不使用 Testcontainers。
