# versioned-object-storage-java

一套纯本地的版本化对象存储服务：不依赖任何外部服务或真实凭据，所有元数据与内容均持久化在本机磁盘（默认 `./data`，可通过 `storage.data-dir` 配置），重启后状态完整复现。

## 运行与验证

```bash
# 启动服务（默认端口见 application.properties，server.port=0 表示随机端口，可改为固定值）
mvn spring-boot:run

# 运行全部测试（并发写入、条件冲突、去重、中断恢复、非法分片、提交幂等/崩溃自愈、只读容量视图、REST 端到端）
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
| POST | `/uploads/{uploadId}/complete` | 合并分片并产生新版本；请求体可带 `{"expectedVersionId":"..."}` |
| DELETE | `/uploads/{uploadId}` | 中止会话并清理分片 |

### 运维

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/admin/gc` | 回收不被任何版本引用的内容，返回回收列表；可中断、可安全重跑 |
| GET | `/admin/capacity` | **只读**容量视图：统计占用与可释放量，绝不删除任何内容 |

`GET /admin/capacity` 返回字段：

| 字段 | 含义 |
| --- | --- |
| `totalBytes` / `totalBlobCount` | 内容存储当前总占用字节 / 内容文件数（不含临时文件） |
| `referencedBytes` | 仍被至少一个可读（非墓碑、未回收）版本引用的内容字节；同一内容被多个版本引用只计一份 |
| `reclaimableBytes` / `reclaimableBlobs` / `reclaimableCount` | 不被任何版本引用、可安全释放的内容字节 / 哈希列表 / 数量 |

恒等式：`totalBytes = referencedBytes + reclaimableBytes`。
该接口只读取磁盘与元数据，调用任意次数都不会删除、移动任何文件；其「可释放」集合的计算口径与 `POST /admin/gc` 完全一致，因此可以先看视图、再决定是否执行 GC。

### 错误响应

所有业务失败返回统一结构 `{"code": "...", "message": "..."}`，`code` 可区分：

| code | HTTP | 含义 |
| --- | --- | --- |
| `OBJECT_NOT_FOUND` | 404 | 对象不存在、已删除或读取的是墓碑版本 |
| `VERSION_NOT_FOUND` | 404 | 指定版本不存在（含已被显式回收的版本） |
| `VERSION_CONFLICT` | 409 | 条件更新的版本标识与当前最新版本不一致 |
| `SESSION_NOT_FOUND` | 404 | 上传会话不存在 |
| `SESSION_EXPIRED` | 409 | 会话已中止；或对已中止会话调用 complete（已成功提交的会话重试 complete 不报错，见下） |
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

## 分片完成的可重试语义

`POST /uploads/{uploadId}/complete` 是一个**可放心重试的提交步骤**：

- 服务端在**同一次原子元数据落盘**中同时完成「追加对象版本」与「把会话置为 COMPLETED 并记录提交结果」。二者要么同时生效、要么都不生效，因此不存在「版本已可见但会话仍 ACTIVE」的中间态。
- 一旦提交成功：
  - 之后**任意次数**的 complete（客户端当场没收到响应就重试、或服务重启后再重试）都**直接返回同一个版本结果**（相同 `versionId` / `contentHash` / `size`），**不会产生第二个版本**，也**不会**返回 `SESSION_EXPIRED` 或「会话不可用」。
  - 提交成功之后的重试不再重复校验 `expectedVersionId`——提交即承诺，重试只是取回结果。首次提交仍严格做条件更新校验（`X-Expected-Version` / 请求体 `expectedVersionId`），不满足返回 `VERSION_CONFLICT`，此时重试可改用正确的基准版本或改为无条件。
  - 提交前的失败（分片缺失 `MISSING_PARTS`、分片内容损坏 `CHECKSUM_MISMATCH`、条件冲突 `VERSION_CONFLICT`）不会产生版本，客户端补齐后可用**同一 uploadId** 原样重试。
- 并发同时 complete 同一个会话：服务方法串行化，只有第一个请求真正提交，其余请求拿到同一个结果，最终只有一个版本。
- 内容仍按「先写 blob（临时文件 + 原子改名），后提交元数据」的顺序持久化。提交前崩溃时已就位但尚不可达的内容是无害残留，会被恢复清理或由 GC 回收，不影响重试。

## 一致性、崩溃自愈与回收规则

- 写入顺序恒为「先内容、后元数据」：内容先写临时文件再原子改名就位，版本元数据落盘（同样是临时文件 + 原子改名）后版本才可见。因此任何可见版本必然指向已存在的内容。
- 进程在写入、上传收尾或回收的任意时刻退出，重启后 `recover()` 会自动收敛到一致状态（该流程本身幂等，可反复执行）：
  1. 清理内容写入中断遗留的 `blobs/tmp-*` 临时文件；
  2. 清理元数据落盘中断遗留的 `metadata.json.tmp`（不触碰正式元数据）；
  3. 清理已完成/已中止会话的分片目录（兜底「提交成功但目录没来得及删」的情况）；
  4. 活跃会话：保留已就位的 `part-N` 分片供续传，仅清理中断残留的 `part-N.tmp`、`merged.tmp`；
  5. 清理元数据中无会话记录的孤儿分片目录；
  6. 防御性校验所有可见版本指向的内容确实存在，不一致则拒绝启动。
- 自愈结果：
  - **已对外可见的版本**重启后立即可读，其内容不会被当作垃圾删掉；
  - **未真正生效的写入**（元数据尚未提交）不留下任何版本，客户端可原样重试补完；其提前就位但不可达的内容计入容量视图的「可释放」，可被 GC 干净回收；
  - 不会出现指向不存在内容的版本，也不会留下越堆越多、收不回来的残留；反复在崩溃边缘重启，临时文件每次都被清掉，磁盘占用不随重启次数无界增长。
- 分片上传的分片级幂等保持不变：同一会话内重复提交「同分片号 + 同校验和」直接成功且不产生重复数据；同分片号不同校验和拒绝（`CHECKSUM_MISMATCH`）；乱序提交在完成时按分片号顺序合并。
- 垃圾回收（`POST /admin/gc`）**可中断、可安全重跑**：每一轮都先按当前元数据重新计算引用集合，只删除引用集合之外的内容。执行到一半崩溃后重跑结果一致——已删的不再出现、该删的继续删、仍被任何可读版本引用的内容绝不删除。
- 显式回收历史版本（`POST /objects/{key}/reclaim`）是单次原子元数据落盘，重跑同一请求是幂等空操作；它只移除版本元数据引用，内容需等下一次 GC 才真正释放。最新版本始终不允许回收。

## 测试覆盖

- `ObjectStorageServiceTests`：并发条件写入（仅一个成功，其余 `VERSION_CONFLICT`）、并发无条件写入（版本全部可见）、条件更新冲突且状态不变、重复内容去重与 GC、删除后重写保留历史、乱序/重复分片、非法分片各类失败原因、上传中断后重启续传、孤儿临时数据清理、重启后版本与内容一致。
- `CrashRecoveryTests`（本次新增）：
  - 跨重启重试 complete 只得一个版本、返回同一结果；
  - 8 线程并发 complete 只有一个版本真正提交、其余请求拿到同一结果；
  - complete「元数据提交前」崩溃后重启原样重试补完，且反复崩溃边缘重启无残留增长；
  - complete「提交后、目录清理前」崩溃后重启版本立即可读、重试返回同一版本、目录被兜底清理；
  - GC 执行到一半中断后重跑收敛，仍被引用内容绝不删除、二次 GC 为空；
  - 只读容量视图数值自洽、调用前后磁盘 blob 集合与字节数逐字节不变、预测集合与 GC 实际删除一致；
  - reclaim 重跑幂等、metadata 临时文件清理、连续 5 轮崩溃重启磁盘文件数稳定；
  - 直接写入崩溃点（已提交可读 / 未提交无悬挂版本且可回收）与活跃半传会话续传完成。
- `StorageControllerTests`：REST 端到端，验证各错误码经 HTTP 响应可区分、complete 幂等重试返回同一 `versionId`、只读容量端点不产生删除。
- `ApplicationTests`：Spring 上下文启动。

测试日志会打印关键请求参数（key、uploadId、版本标识、校验和、分片号、字节数、磁盘文件数等）与判定依据（成功/冲突计数、错误码、哈希比对、磁盘集合前后比对结果），便于复核。

## 与旧数据目录、既有接口的兼容

- **数据目录布局不变**：仍是 `blobs/`、`uploads/`、`metadata.json`，`storage.data-dir` 配置不变；旧版本写出的数据目录可直接被新版本加载。
- `UploadSessionState` 仅**新增**了一个可空字段 `completedVersion`（记录已提交版本结果）。旧 `metadata.json` 没有该字段时按 `null` 反序列化，不影响加载；旧数据里「已完成」的历史会话重启后按原规则清理分片目录。
- 既有接口的路径、请求/响应字段、写入/按版本读取/删除/去重/条件更新/显式回收语义全部保持不变。
- 唯一的**可观测行为变化**：对已经成功提交的会话再次调用 `POST /uploads/{uploadId}/complete`，由原来的 `409 SESSION_EXPIRED` 改为 `200` 并返回首次提交的同一版本（提交结果幂等）。中止（ABORTED）的会话仍返回 `SESSION_EXPIRED`，不存在的会话仍返回 `SESSION_NOT_FOUND`。
- `GET /admin/capacity` 为新增只读接口，不影响任何既有调用。

## 本地一步步验证

```bash
# 1. 跑全部单测，日志中可看到每个用例的参数与判定依据
mvn test

# 2. 启动服务（可把 application.properties 的 server.port 改为固定端口）
mvn spring-boot:run

# 3. 创建分片上传会话
curl -s -XPOST localhost:8080/uploads -H 'Content-Type: application/json' -d '{"key":"demo"}'
# -> {"uploadId":"<UID>","key":"demo"}

# 4. 上传两个分片（校验和为分片内容的 sha256 十六进制）
printf 'hello-' > p1; printf 'world' > p2
curl -s -XPUT localhost:8080/uploads/<UID>/parts/1 --data-binary @p1 -H "X-Checksum-Sha256:$(sha256sum p1 | cut -d' ' -f1)"
curl -s -XPUT localhost:8080/uploads/<UID>/parts/2 --data-binary @p2 -H "X-Checksum-Sha256:$(sha256sum p2 | cut -d' ' -f1)"

# 5. 完成；紧接着用同一条命令再调一次（模拟没收到响应后重试）——两次 versionId 完全相同
curl -s -XPOST localhost:8080/uploads/<UID>/complete -H 'Content-Type: application/json' -d '{}'
curl -s -XPOST localhost:8080/uploads/<UID>/complete -H 'Content-Type: application/json' -d '{}'

# 6. 只读查看容量（不删任何东西）；多调几次 totalBytes 不变
curl -s localhost:8080/admin/capacity

# 7. 删除对象并回收其历史版本后，再看容量视图出现 reclaimableBytes，最后执行 GC 真正释放
curl -s -XDELETE localhost:8080/objects/demo
curl -s -XPOST localhost:8080/objects/demo/reclaim -H 'Content-Type: application/json' -d '{"versionIds":["<VID>"]}'
curl -s localhost:8080/admin/capacity
curl -s -XPOST localhost:8080/admin/gc
```

模拟崩溃恢复：在第 4~5 步任意时刻 `kill -9` 服务进程，重新 `mvn spring-boot:run`，之后要么继续传完分片再 complete，要么直接重发 complete；已完成的会话重发 complete 始终返回同一版本，`data/` 下不会残留 `tmp-*`、`*.tmp` 或孤儿分片目录。

