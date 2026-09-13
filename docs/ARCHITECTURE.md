# 架构

VocabTrim Full 由 Vue 单页应用、Spring Boot 后端和 MySQL 数据库组成。它保留了 VocabTrim 原有的本地词表处理方式，并在外围增加账号认证和手动 snapshot 同步。整个后端作为一个 Spring Boot 应用部署，内部按认证、用户、同步和通用基础设施划分代码，不需要额外的服务发现或跨服务通信。

## 总体结构

```text
Browser
├── Vue 3 + Pinia
│   └── 当前页面使用的运行时状态
├── IndexedDB
│   └── 当前用户的本地 snapshot
└── localStorage
    └── 当前用户的云端 ETag
         │
         │ /api
         ▼
      Nginx
         │
         ▼
   Spring Boot
   ├── Spring Security
   ├── Controller
   ├── Service
   └── MyBatis Mapper
         │
         ▼
       MySQL
```

Nginx 同时提供前端静态文件，并把 `/api` 请求转发给后端，因此浏览器只访问同一个站点。开发环境由 Vite 完成相同的 `/api` 代理，前端代码不需要区分本地和生产环境的后端地址。

## 前端状态与本地持久化

Pinia 管理正在使用的词表、当前游标、词条标记、显示设置等运行时状态。由于 Pinia 中的数据会进入 Vue 的响应式系统，其中的对象可能是 Proxy，而 VocabTrim snapshot 本身定义为纯 JSON 数据，所以写入 IndexedDB 前会先经过 JSON 序列化。读取时则从 IndexedDB 取得 JSON，解析后再通过 `normalizeSnapshot()` 转换为当前应用支持的 snapshot 结构。

同一浏览器可能先后登录不同账号，因此 IndexedDB 不是使用单一固定 key，而是按用户保存为 `user:<userId>`。云端 ETag 也使用 user id 作为 localStorage key 的一部分，这样本地词表和同步版本不会在账号之间串用。

大多数离散操作，例如导入、重命名、删除、排序、打乱和批量标记，会在完成后直接保存本地 snapshot。拖动词表进度和调节动画时长属于高频输入，当前实现通过 140 ms debounce 合并连续写入，以免每个 `input` 事件都重新序列化整份词表。执行上传同步前会强制完成一次立即持久化，确保即将上传的数据与界面当前状态一致。

## 词表业务边界

词表的日常处理全部发生在前端。导入文件后，浏览、保留、去除、移动游标、排序、打乱、生成新词表等操作都只修改 Pinia 和 IndexedDB，不会为单个词条调用后端接口。

这一边界也决定了服务器端的数据模型。后端不维护独立的词表、词条和词条状态表，而是把浏览器上传的完整 snapshot 作为同步对象。服务器只需要验证 snapshot 是否符合当前 schema，并负责用户隔离、版本控制和持久化。

## 认证与会话

认证由 Spring Security 处理，使用服务端 Session 和 Cookie。登录成功后，浏览器持有名为 `VOCABTRIM_SESSION` 的 HttpOnly Cookie，后续同源请求会自动携带该 Cookie。密码在注册时使用 BCrypt 处理，数据库只保存密码哈希。

修改状态的请求还需要通过 CSRF 校验。后端使用 `CookieCsrfTokenRepository`，前端通过 `GET /api/v1/auth/me` 取得 CSRF token，并在注册、登录、退出和 snapshot 上传等请求中发送 `X-XSRF-TOKEN` 请求头。

认证相关接口如下：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/auth/me` | 返回当前登录状态、用户信息和 CSRF token |
| POST | `/api/v1/auth/register` | 创建账号 |
| POST | `/api/v1/auth/login` | 登录并建立 Session |
| POST | `/api/v1/auth/logout` | 注销当前 Session |

注册用户名必须由 3–32 位 ASCII 字母、数字或下划线组成，密码长度为 8–72 个字符。

## 服务端分层

HTTP 请求进入 Spring Boot 后会先经过 Spring Security filter chain。认证和 CSRF 校验通过后，请求才会进入 Controller。Controller 负责读取请求参数、Header 和 Body，并把业务处理交给 Service；Service 负责 snapshot 校验、同步前置条件、版本生成和事务边界；Mapper 使用 MyBatis 执行实际 SQL。

用户身份不会由前端通过 `userId` 参数指定。Snapshot Controller 从当前认证主体 `VocabTrimPrincipal` 取得用户 id，后续数据库查询都以该 id 为条件，因此云端数据访问始终绑定到当前 Session。

## 数据库

数据库目前只有 `users` 和 `snapshots` 两张业务表，两者是一对多关系。`users` 保存账号、BCrypt 密码哈希、启用状态和时间戳；`snapshots` 保存用户上传的完整 JSON、版本号、ETag、schema version、字节数和创建时间。

数据库结构由 Flyway migration 管理，初始结构位于 `backend/src/main/resources/db/migration/V1__create_users_and_snapshots.sql`。后续如果数据库结构发生变化，应继续增加新的 migration，而不是直接修改已经执行过的版本。

同一用户上传 snapshot 时，Service 会先锁定该用户行，再读取最新版本并执行版本检查。这样两个并发上传不会同时基于同一个版本号完成写入。每次成功上传都会生成新的 snapshot 版本，并删除更早的历史记录，只保留最近两个版本。

## Snapshot 数据结构

当前同步格式为 `vocabtrim-snapshot` schema version 1。顶层结构包括词表列表、全局设置和保存时间：

```json
{
  "format": "vocabtrim-snapshot",
  "schemaVersion": 1,
  "lists": [],
  "settings": {
    "defaultDetails": false,
    "keepDuration": 60,
    "crossDuration": 205
  },
  "savedAt": 0
}
```

后端会检查顶层格式、schema version、设置项、词表结构、`records` 与 `statuses` 数量、状态取值以及游标范围。单个 snapshot 的最大大小为 64 MiB，Nginx 和应用层都设置了相同的上限。

同步版本与冲突处理的具体规则见 [SYNC.md](SYNC.md)。
