# Snapshot 同步

VocabTrim Full 的同步是用户主动触发的完整 snapshot 同步，不是后台自动同步。浏览器中的日常修改首先保存在本地，只有点击“上传同步”时才把当前完整 snapshot 发送到服务器；点击“下载同步”时，则用服务器上的最新 snapshot 替换当前用户的本地状态。

服务器不会尝试对两个设备上的词条逐项合并。为了避免旧设备在不知情的情况下覆盖新版本，上传接口使用 ETag 做乐观并发控制。

## 下载

下载使用 `GET /api/v1/snapshot`，请求要求已经登录。服务器返回当前用户最新的 snapshot，并在响应头中附带 `ETag` 和 `X-VocabTrim-Version`。如果该用户从未上传过 snapshot，接口返回 `404 SNAPSHOT_NOT_FOUND`。

前端成功下载后会通过 `normalizeSnapshot()` 校验并规范化数据，将其替换为当前 Pinia 状态，再写入该用户对应的 IndexedDB 记录。响应中的 ETag 会同时保存到 localStorage，作为下一次普通上传的版本依据。

## 上传

上传使用：

```http
PUT /api/v1/snapshot
Content-Type: application/json
```

请求体是完整 snapshot JSON。上传前，前端会立即持久化一次当前状态，以消除高频输入 debounce 尚未落盘的时间窗口。

首次上传时，客户端本地还没有云端 ETag，因此发送 `If-None-Match: *`。如果服务器此时已经存在 snapshot，说明客户端对云端状态的认识已经过期，服务器会返回冲突。

当客户端已经下载过或成功上传过 snapshot 后，后续普通上传发送 `If-Match: "<etag>"`。服务器只在该 ETag 与当前最新版本一致时接受写入。成功后会生成新的版本号和新的 ETag，并把新的 ETag 返回给客户端。

## 冲突处理

假设设备 A 和设备 B 都曾下载同一个云端版本。A 先完成上传后，服务器已经产生新 ETag；B 随后仍携带旧 ETag 上传时，服务器返回 `409 SYNC_CONFLICT`。前端收到冲突后不会自动覆盖云端数据。用户可以先执行下载，以云端版本替换本地状态；如果用户明确确认要用当前本地内容覆盖云端，则前端发送 `PUT /api/v1/snapshot?force=true`。强制上传会跳过 ETag 前置条件，但仍然要求有效 Session、CSRF token，并继续执行 snapshot 格式和大小校验。它只用于用户已经确认覆盖的场景。

## 服务端写入过程

每次上传都在事务中完成。后端先验证 payload，再锁定当前用户行并读取该用户最新 snapshot。如果不是强制上传，就根据 `If-Match` 或 `If-None-Match` 判断前置条件是否满足；通过后生成下一个版本号和 ETag，写入新 snapshot，并删除更早的历史版本。

服务器每个用户只保留最近两个 snapshot。这个历史保留策略用于避免表中持续积累旧版本，但当前界面并没有提供任意历史版本浏览或恢复功能。

## ETag 规则

ETag 由后端根据 snapshot payload 和版本号生成，并作为服务端并发控制标识。客户端不解析 ETag 内容，只把上一次服务器返回的值原样保存，并在下一次普通上传时通过 `If-Match` 发送回来。

本地 ETag 按用户保存。切换账号后，前端会读取另一个 user id 对应的 ETag，因此同一浏览器中不同账号之间不会共用同步版本。

## 校验与错误

后端在接受 snapshot 前会检查 JSON 格式和 schema。`format` 必须为 `vocabtrim-snapshot`，`schemaVersion` 必须为 1；`lists`、`settings` 和 `savedAt` 必须存在且类型正确。每个词表中的 `records` 与 `statuses` 数量必须一致，状态值只能是空字符串、`keep` 或 `crossed`，游标也必须位于有效范围内。

单个 snapshot 最大为 64 MiB。Nginx 使用 `client_max_body_size 64m`，Java Controller 和 `SnapshotValidator` 也会再次检查请求大小。

上传接口常见响应如下：

| 状态码 | 错误码或含义 | 说明 |
| --- | --- | --- |
| 200 | 成功 | 已保存新版本，并返回新的 ETag |
| 400 | `INVALID_SNAPSHOT` | JSON 或 snapshot 结构不符合要求 |
| 401 | `UNAUTHORIZED` | Session 不存在或已经失效 |
| 403 | `FORBIDDEN` | CSRF 校验或访问控制失败 |
| 409 | `SYNC_CONFLICT` | 客户端 ETag 与当前云端版本不一致 |
| 413 | `SNAPSHOT_TOO_LARGE` | payload 超过 64 MiB |
| 428 | `PRECONDITION_REQUIRED` | 普通上传缺少 `If-Match` 或 `If-None-Match` |

## 本地保存与云端同步的关系

IndexedDB 是浏览器中的本地持久化层，MySQL 中的 snapshot 是用户主动上传后的云端版本。用户在词表界面完成一次“保留”或“去除”后，本地状态会保存，但服务器不会立刻收到任何请求。只有执行上传同步后，这些本地修改才进入云端。

因此，刷新当前浏览器主要依赖 IndexedDB 恢复进度，而在另一台设备上继续使用则需要先下载服务器 snapshot。这也是当前同步模型与实时多端同步之间最明显的区别。
