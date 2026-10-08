# versioned-object-storage-java

一套纯本地的版本化对象存储服务：不依赖任何外部服务或真实凭据，所有元数据与内容均持久化在本机磁盘（默认 `./data`，可通过 `storage.data-dir` 配置），重启后状态完整复现。

## 运行与验证

```bash
# 启动服务（默认端口见 application.properties，server.port=0 表示随机端口，可改为固定值）
mvn spring-boot:run

# 运行全部测试（并发写入、条件冲突、去重、中断恢复、非法分片、REST 端到端）
mvn test
```

数据目录结构：

```
data/
  blobs/          # 内容寻址存储：文件名为内容的 SHA-256，相同内容只存一份
  uploads/        # 分片上传临时区：uploads/<uploadId>/part-<n>
  metadata.json   # 对象版本与上传会话元数据（原子落盘）
```

## 对外接口

### 对象读写

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| PUT | `/objects/{key}` | 写入对象并产生新版本；可选请求头 `X-Expected-Version` 做条件更新 |
| GET | `/objects/{key}?versionId=` | 读取对象；不带 `versionId` 读最新版本，带则读指定历史版本 |
| DELETE | `/objects/{key}` | 删除对象（写入墓碑版本）；可选 `X-Expected-Version` 条件删除 |
| GET | `/objects/{key}/versions` | 列出全部版本（含墓碑） |
| POST | `/objects/{key}/reclaim` | 显式回收历史版本，请求体 `{"versionIds":[...]}`，最新版本不允许回收 |

### 分片上传

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/uploads` | 创建上传会话，请求体 `{"key":"..."}`，返回 `uploadId` |
| PUT | `/uploads/{uploadId}/parts/{n}` | 上传分片 `n`（从 1 开始），请求头 `X-Checksum-Sha256` 必填 |
| POST | `/uploads/{uploadId}/complete` | 合并分片并产生新版本；请求体可带 `{"expectedVersionId":"..."}`；可安全重试（见下文「提交重试语义」） |
| DELETE | `/uploads/{uploadId}` | 中止会话并清理分片 |

### 运维

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/admin/gc` | 回收不被任何版本引用的内容，返回回收列表 |
| GET | `/admin/stats` | 只读容量视图：总占用、仍被引用、可安全释放；不产生任何删除 |

### 错误响应

所有业务失败返回统一结构 `{"code": "...", "message": "..."}`，`code` 可区分：

| code | HTTP | 含义 |
| --- | --- | --- |
| `OBJECT_NOT_FOUND` | 404 | 对象不存在、已删除或读取的是墓碑版本 |
| `VERSION_NOT_FOUND` | 404 | 指定版本不存在（含已被显式回收的版本） |
| `VERSION_CONFLICT` | 409 | 条件更新的版本标识与当前最新版本不一致 |
| `SESSION_NOT_FOUND` | 404 | 上传会话不存在 |
| `SESSION_EXPIRED` | 409 | 会话已完成或已中止 |
| `CHECKSUM_MISMATCH` | 400/409 | 分片校验和不匹配，或同分片号以不同校验和重复提交 |
| `MISSING_PARTS` | 400 | 完成上传时分片缺失（分片号必须从 1 连续） |
| `INVALID_PART` | 400 | 非法分片号（< 1） |

## 版本与删除语义

- 每次成功写入（直接写入或分片完成）都追加一个全局唯一 `versionId` 的新版本，历史版本可查询、可读取。
- 删除是追加一个 `deleted=true` 的墓碑版本，**不覆盖、不抹除历史版本**；删除后重新写入继续追加新版本。
- 读取最新版本若落在墓碑上，按对象不存在（`OBJECT_NOT_FOUND`）处理；显式指定历史版本仍可读取。
- 条件更新：请求携带 `X-Expected-Version`（或 complete 请求体中的 `expectedVersionId`）时，必须与当前最新版本一致，否则拒绝并返回 `VERSION_CONFLICT`，绝不静默覆盖。并发下基于同一版本的多个条件更新只有一个能成功。

## 去重与回收规则

- 内容按 SHA-256 寻址存储：相同内容无论写多少次、写到多少个 key，磁盘上只保留一份副本。
- 垃圾回收（`POST /admin/gc`）只清除**不被任何非删除版本引用**的内容。被删除对象的旧版本仍引用其内容，因此删除不会立即释放空间。
- 只有显式回收历史版本（`POST /objects/{key}/reclaim`）后，其引用的内容才可能被 GC 清除；最新版本不允许回收，保证对象始终可用。

## 提交重试语义（complete 幂等）

完成分片上传（`POST /uploads/{uploadId}/complete`）采用两段式落盘，可以放心重试：

1. 分片合并、内容写入内容存储后，先把「提交意图」落盘（会话进入 `COMMITTING`，预定版本的 `versionId` 从此固定）；
2. 追加版本元数据落盘，版本对外可见；
3. 会话标记 `COMPLETED` 落盘，清理分片目录。

由此保证：

- 客户端在拿到响应之前断线、或进程在收尾任意时刻退出，**当场重试或重启后重试**同一个 `uploadId`，拿到的都是首次提交的那同一个版本（同一个 `versionId`），不会冒出新版本，也不会被告知会话失效。
- 同一个上传会话永远只产生一个对象版本；并发打到 complete 的多个请求被串行化，只有第一个真正提交，其余拿到同一个结果。
- 重试时即使携带了过期的 `expectedVersionId`，只要首次提交已生效，仍返回首次的结果（条件检查只在首次提交时执行一次）。

## 一致性与崩溃恢复

- 写入顺序恒为「先内容、后元数据」：内容先写临时文件再原子改名就位，版本元数据落盘（同样是临时文件 + 原子改名）后版本才可见。因此任何可见版本必然指向已存在的内容。
- 进程在写入、上传收尾或回收中途退出后重启，启动恢复流程会：
  1. 清理内容写入中断遗留的临时文件；
  2. 收敛 `COMMITTING` 会话：预定版本已生效的补标 `COMPLETED`；版本未生效但内容已就位的补写版本元数据完成提交（提交意图落盘前内容必然已写入，因此补完是安全的）；内容缺失的（防御性分支）回滚为 `ACTIVE`，客户端可重新提交；
  3. 清理已完成/已中止会话的分片目录；
  4. 清理元数据中无会话记录的孤儿分片目录；
  5. 保留活跃会话及其已上传分片，客户端可继续续传；
  6. 防御性校验所有可见版本指向的内容确实存在，不一致则拒绝启动。
- 反复在崩溃边缘重启，磁盘占用不会无界增长：未生效的写入要么被补完（内容被版本引用），要么成为无引用内容被 GC 回收；分片目录随会话结束或恢复被清理。
- 分片上传幂等：同一会话内重复提交「同分片号 + 同校验和」的分片直接成功且不产生重复数据；同分片号不同校验和会被拒绝（`CHECKSUM_MISMATCH`）。乱序提交的分片在完成时按分片号顺序合并。

## 回收与容量视图

- 垃圾回收（`POST /admin/gc`）可以扛住中途退出并放心重跑：引用集每次从元数据重新计算，删除操作幂等，回收执行到一半进程退出后，重启再跑一遍结果与一次性跑完一致；绝不会删除仍被任何非删除版本引用的内容。
- 容量视图（`GET /admin/stats`）是**只读**接口，返回示例：

  ```json
  {"totalBlobs": 3, "totalBytes": 1024,
   "referencedBlobs": 2, "referencedBytes": 768,
   "reclaimableBlobs": 1, "reclaimableBytes": 256}
  ```

  - `totalBlobs` / `totalBytes`：内容存储当前占用的份数与字节数；
  - `referencedBlobs` / `referencedBytes`：仍被至少一个非删除版本引用的部分；
  - `reclaimableBlobs` / `reclaimableBytes`：不再被任何版本引用、可被 GC 安全释放的部分。

  该接口只查看不删除，调用前后元数据与内容不发生任何变化；想真正释放空间需调用 `POST /admin/gc`。

## 兼容性

- 数据目录结构不变（`blobs/`、`uploads/`、`metadata.json`），旧版本数据目录可直接打开：旧元数据中没有 `COMMITTING` 状态与 `committedVersion` 字段，加载时按缺省值处理。
- 既有接口的写入、按版本读取、删除、去重、条件更新语义完全不变。
- 唯一的行为变化：`complete` 作用于**已完成**会话时，旧版本返回 `409 SESSION_EXPIRED`，现在幂等返回首次提交的版本（`200`）。例外：旧数据目录里由旧版本代码完成的会话没有记录提交结果，对其重试 complete 仍返回 `409 SESSION_EXPIRED`（错误响应中注明 `result not recorded`）；新产生的完成记录均可幂等重试。

## 本地验证

```bash
# 全部测试（含崩溃恢复与重试语义）
mvn test

# 只跑崩溃恢复场景
mvn test -Dtest='CrashRecoveryTests'
```

手动验证崩溃自愈（两个终端）：

```bash
# 1. 启动服务（固定端口便于操作）
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080

# 2. 创建会话并传分片
curl -s -X POST localhost:8080/uploads -H 'Content-Type: application/json' -d '{"key":"demo"}'
#    -> {"uploadId":"<id>","key":"demo"}
printf 'hello-' | curl -s -X PUT "localhost:8080/uploads/<id>/parts/1" \
  -H "X-Checksum-Sha256: $(printf 'hello-' | sha256sum | cut -d' ' -f1)" --data-binary @-
printf 'world'  | curl -s -X PUT "localhost:8080/uploads/<id>/parts/2" \
  -H "X-Checksum-Sha256: $(printf 'world' | sha256sum | cut -d' ' -f1)" --data-binary @-

# 3. 完成上传后立刻 kill -9 服务进程，再重启，重试 complete：
#    两次返回的 versionId 相同，且 /objects/demo/versions 里只有一个版本
curl -s -X POST localhost:8080/uploads/<id>/complete -H 'Content-Type: application/json' -d '{}'

# 4. 查看容量视图（只读），确认可回收量；需要释放时再触发 GC
curl -s localhost:8080/admin/stats
curl -s -X POST localhost:8080/admin/gc
```

## 测试覆盖

- `ObjectStorageServiceTests`：并发条件写入（仅一个成功，其余 `VERSION_CONFLICT`）、并发无条件写入（版本全部可见）、条件更新冲突且状态不变、重复内容去重与 GC、删除后重写保留历史、乱序/重复分片、非法分片各类失败原因、上传中断后重启续传、孤儿临时数据清理、重启后版本与内容一致。
- `CrashRecoveryTests`：跨重启重试 complete 只得一个版本且结果相同、并发重试 complete 只发一个版本、提交意图落盘前/后、版本元数据落盘前等收尾各阶段崩溃后重启自愈（补完提交）、反复崩溃重启磁盘占用稳定、GC 执行到一半崩溃后重跑结果一致且被引用内容完好、容量视图只读且数值准确。
- `StorageControllerTests`：REST 端到端，验证各错误码经 HTTP 响应可区分、complete 幂等重试返回同一版本、容量视图接口只读。
- `ApplicationTests`：Spring 上下文启动。

测试日志会打印关键请求参数（key、版本标识、校验和、分片号等）与判定依据（成功/冲突计数、错误码、哈希比对结果），便于复核。
