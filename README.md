# VocabTrim Full

VocabTrim Full 是一个用于快速筛选词表的 Web 应用。用户可以导入本地词表，在浏览器中逐词查看并标记“保留”或“去除”，最后导出筛选后的新词表。Full 版在原有本地使用方式上增加了账号系统和手动云端同步，因此同一套部署可以供多个彼此独立的用户使用。

词表处理仍然采用 local-first 方式。导入、浏览、标记、排序、打乱、批量处理和导出都在浏览器中完成，日常操作不依赖后端请求。服务器主要负责用户认证，以及在用户主动执行“上传同步”或“下载同步”时保存和读取完整 snapshot。

## 功能

- 注册、登录和退出账号，并隔离不同用户的本地数据与云端数据。
- 导入 JSON 词表，支持数组、单对象、常见 wrapper 结构和 NDJSON。
- 查看单词及其音标、词性、中文释义和英文释义。
- 对词条进行保留、去除、回退和前进操作，并保留当前游标位置。
- 对词表进行复制、重命名、删除、排序、打乱和批量标记。
- 根据“保留”标记生成新的词表，并按原词表结构导出。
- 使用 IndexedDB 保存当前用户的本地 snapshot，刷新页面后可以继续之前的进度。
- 手动上传或下载完整 snapshot，并通过 ETag 检测多设备之间的版本冲突。

## 技术栈

后端使用 Java 21、Spring Boot 4.1、Spring MVC、Spring Security、MyBatis、MySQL 8.4 和 Flyway。前端使用 Vue 3、TypeScript、Vue Router、Pinia 和 Vite，本地数据保存在 IndexedDB 和 localStorage 中。生产部署由 Nginx 提供静态文件并反向代理 `/api`，仓库同时提供 Docker Compose、JUnit 5、Vitest 和 GitHub Actions 配置。

## 快速启动

准备 Docker 和 Docker Compose 后，复制环境变量示例、修改数据库密码，然后启动全部服务：

```bash
cp .env.example .env
docker compose up --build
```

默认可以通过 `http://localhost` 访问应用。首次使用时先注册账号，再登录进入词表界面。

本地 HTTP 环境应保持 `SESSION_COOKIE_SECURE=false`。如果应用部署在 HTTPS 环境中，应将其改为 `true`。

## 本地开发

后端需要 Java 21、Maven 3.6.3+ 和 MySQL 8.4。默认数据库名、用户名和密码都是 `vocabtrim`，也可以通过 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 和 `SESSION_COOKIE_SECURE` 覆盖配置。数据库表由 Flyway 在应用启动时自动创建和迁移。

```bash
cd backend
mvn clean test
mvn spring-boot:run
```

后端默认监听 `http://localhost:8080`。

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

后端测试可以通过以下命令运行：

```bash
cd backend
mvn test
```

前端在提交前可以依次执行类型检查、测试和构建：

```bash
cd frontend
npm run typecheck
npm run test:run
npm run build
```

GitHub Actions 配置位于 `.github/workflows/ci.yml`。
