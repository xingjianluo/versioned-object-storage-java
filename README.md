# 本地版本化对象存储服务（Versioned Object Storage）

一个不依赖任何外部服务、不需要真实凭据的本地对象存储：所有数据、元数据与上传会话都
持久化在本机磁盘目录中，进程重启后状态可复现。支持分片上传与续传、对象版本与条件
更新、基于内容哈希的去重、显式回收与崩溃自恢复。

- 技术栈：Java 21、Spring Boot 3.4、仅本地文件系统 + Jackson（无数据库、无第三方存储）
- 数据默认目录：`data/vostore/`（可用配置覆盖）

---

## 1. 快速开始

```bash
# 构建（含全部测试）
mvn test

# 打包
mvn package

# 启动（默认端口随机，可显式指定）
java -jar target/versioned-object-storage-java-1.0-SNAPSHOT.jar \
     --server.port=8080 \
     --vostore.root=/var/lib/vostore \
     --vostore.session-ttl-millis=86400000
```

启动时会自动执行一次崩溃恢复（见第 6 节），恢复完成前不接收请求。

### 配置项

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `vostore.root` | `data/vostore` | 本地数据根目录 |
| `vostore.session-ttl-millis` | `86400000`（24h） | 分片上传会话空闲多久后视为失效 |
| `server.port` | `0`（随机端口） | HTTP 端口 |

---

## 2. 磁盘布局

```
<vostore.root>/
├── blob/                     # 内容寻址的不可变内容块（SHA-256 为键）
│   └── xx/yy/<完整哈希>       # 按哈希前 4 位两级散列
├── staging/                  # 写入中的暂存文件，崩溃后整体清理
├── meta/                     # 对象元数据（一个对象键一个 JSON 文件，原子写）
│   └── xx/<URL编码的键>.json
└── sessions/                 # 分片上传会话
    ├── <uploadId>.json        # 会话状态（已收分片表 + 提交中间态信息）
    └── <uploadId>/            # 各分片数据 part-<n>，原子替换写
```

---

## 3. 对外 HTTP 接口

所有错误均返回统一 JSON：`{"errorCode": "<可区分错误码>", "message": "<详情>"}`。

### 3.1 对象读写

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `PUT` | `/api/objects/{key}` | 简单上传，**追加一个新版本**。key 可含 `/` |
| `GET` | `/api/objects/{key}?versionId=<可选>` | 读取；不带 versionId 读最新版本 |
| `DELETE` | `/api/objects/{key}` | 逻辑删除（追加删除标记） |
| `GET` | `/api/versions/objects/{key}` | 列出该键全部版本（含删除标记、已回收版本） |
| `DELETE` | `/api/versions/objects/{key}?versionId=<id>` | **显式回收**指定版本的内容块引用 |

请求头：

| 头 | 适用 | 说明 |
| --- | --- | --- |
| `X-Content-SHA256` | PUT 对象 / PUT 分片（分片必填） | 声明内容 SHA-256（小写十六进制），不一致返回 `CHECKSUM_MISMATCH` |
| `If-Match` | PUT / DELETE 对象 | 条件更新：必须等于**当前最新版本号**，否则 `VERSION_CONFLICT`(409) |

成功响应示例（PUT）：

```json
{ "key": "docs/a.txt", "versionId": "uuid", "size": 11,
  "sha256": "afa27b...", "createdMillis": 1790395228364 }
```

GET 成功时额外返回响应头 `X-Version-Id` 与 `X-Content-SHA256`。

### 3.2 分片上传

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/multipart/{key}?partSize=<字节>` | 初始化会话，返回 `uploadId`；partSize 为除最后一片外每片应有的大小 |
| `PUT` | `/api/multipart/{uploadId}/parts/{partNumber}` | 提交分片（1..10000），必须带 `X-Content-SHA256` |
| `POST` | `/api/multipart/{uploadId}/complete` | 合并。请求体 `{"parts":[1,2,3]}`，可省略（用全部已收分片） |
| `DELETE` | `/api/multipart/{uploadId}` | 中止会话并清理分片（幂等） |
| `GET` | `/api/multipart/{uploadId}` | 查询会话状态 |

分片提交响应：`{"uploadId","partNumber","etag"(=分片SHA-256),"size","duplicate"}`。

规则：

- **去重**：同一会话内，相同 `partNumber` + 相同 SHA-256 重复提交直接幂等成功
  （`duplicate=true`），不会再写一份分片文件；
- **冲突**：相同 `partNumber` 提交不同内容返回 `PART_NUMBER_CONFLICT`(409)，原分片保留；
- **乱序**：分片可任意顺序提交，合并时严格按 `partNumber` 升序拼接；
- **连续性**：合并序列必须是从 1 开始的连续整数，且每个分片都已收到，否则 `MISSING_PART`；
- **分片大小**：除最后一片外，每片大小必须等于初始化时的 `partSize`，否则 `PART_SIZE_MISMATCH`；
- **续传**：会话状态与分片都持久化，进程重启后未过期会话可继续上传/重放已传分片；
- **合并提交**：合并采用「暂存 → 会话置 COMMITTING（记录最终版本号/摘要/大小）→ 发布内容块
  → 追加元数据 → COMPLETED → 清理会话」的顺序，任意时刻崩溃都可在启动时重放且不会重复出版本。

### 3.3 管理接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/admin/gc` | 标记清扫：删除不被任何存活版本引用的内容块，返回 `{"sweptBlobs":n}` |
| `GET` | `/api/admin/stats` | 对象键数、版本数、存活版本数、删除标记数、内容块文件数、开放会话数 |

---

## 4. 版本与删除语义

1. **版本只追加，永不覆盖**：每次 PUT（简单上传或分片合并）和每次 DELETE 都在该键的
   版本序列尾部追加一条记录，得到全局唯一 `versionId`（UUID）。删除后重新写入也是新版本，
   历史版本原封不动。
2. **读取**：不带 versionId 返回最新版本；带 versionId 可读取任意历史版本。
3. **删除是删除标记（delete marker）**：不删除任何历史版本与内容块；删除后默认读取返回
   `410 OBJECT_DELETED`，但按 versionId 仍可读到历史内容。
4. **条件更新**：`If-Match` 与当前最新版本号比对。并发场景下，基于同一旧版本的两个提交
   只有一个成功，另一个得到 `409 VERSION_CONFLICT`，不会静默覆盖。
5. **未显式回收的历史版本始终可读**，包括重启之后。

---

## 5. 内容去重与回收规则

- 内容块以 **SHA-256 内容寻址**：不同对象、不同版本、简单上传与分片合并，只要内容相同，
  磁盘上就只有一份物理副本（发布时已存在则直接复用）。
- 引用关系完全由元数据决定。存活集合 = 所有「非删除标记、未被回收」版本引用的摘要，
  加上「正在提交（COMMITTING）」会话记录的最终摘要。
- **显式回收**（`DELETE /api/versions/objects/{key}?versionId=...`）：
  - 只是在该版本上打 `blobReclaimed` 标记，随后立即执行一次标记清扫；
  - 版本记录仍在历史列表中可查询，但再读取内容返回 `410 VERSION_RECLAIMED`；
  - 仅当该内容块的**所有**引用都被回收后，物理文件才会被删除；
  - 删除标记不可回收；重复回收同一版本是幂等的。
- **垃圾清扫**（启动时自动一次，也可 `POST /api/admin/gc` 手动触发）：
  删除不在存活集合中的内容块与遗留临时文件。回收/GC 与写入提交通过全局读写锁互斥，
  绝不可能把正在提交或仍被引用的内容块删掉。

---

## 6. 崩溃恢复与一致性保证

进程启动（Bean 初始化、HTTP 端口对外服务之前）执行：

1. 清空 `staging/` 中所有写入中途的暂存文件；
2. 清空各会话目录中的 `*.writing.tmp` 临时分片；
3. 对残留会话按状态处理：
   - `COMMITTING`：依据记录的最终版本号/摘要/大小**重放**发布与元数据追加
     （内容寻址保证幂等、去重；版本已存在则不重复追加），完成后清理；
   - `COMPLETED` / `ABORTED`：直接清理残留文件；
   - `OPEN` 且未过期：保留，支持继续续传；已过期：清理；
4. 标记清扫孤儿内容块。

因此始终满足：

- **任何可见版本必然指向存在的内容块**（元数据只在内容块发布成功后才更新）；
- 不会留下无法按既有规则回收的临时数据（暂存/临时分片/孤儿内容块都会被启动恢复或 GC 清掉）；
- 重启后重复运行恢复结果稳定，不产生重复版本或重复副本。

---

## 7. 失败原因（errorCode）速查

| errorCode | HTTP | 触发场景 |
| --- | --- | --- |
| `BAD_REQUEST` | 400 | 空 key、缺少 `X-Content-SHA256`、非法 partSize、回收删除标记等 |
| `INVALID_PART_NUMBER` | 400 | 分片序号不在 1..10000 |
| `PART_SIZE_MISMATCH` | 400 | 非最后一片大小不等于约定 partSize |
| `CHECKSUM_MISMATCH` | 400 | 对象/分片实际 SHA-256 与声明不一致 |
| `MISSING_PART` | 400 | 合并时分片序列不连续、有缺失或没有任何分片 |
| `VERSION_CONFLICT` | 409 | If-Match 与当前最新版本不一致（并发条件更新冲突） |
| `SESSION_NOT_OPEN` | 409 | 会话处于 COMPLETED/ABORTED/COMMITTING 等非开放状态 |
| `PART_NUMBER_CONFLICT` | 409 | 同一分片号已存在且内容不同 |
| `SESSION_NOT_FOUND` | 404 | 会话不存在（含已中止、已被超时清理） |
| `OBJECT_NOT_FOUND` | 404 | 对象键不存在 |
| `VERSION_NOT_FOUND` | 404 | 指定 versionId 不存在 |
| `SESSION_EXPIRED` | 409 | 会话超过 `vostore.session-ttl-millis` 未活动 |
| `OBJECT_DELETED` | 410 | 最新/指定版本是删除标记 |
| `VERSION_RECLAIMED` | 410 | 该版本内容已被显式回收 |

---

## 8. 验证方法

```bash
# 全部单元测试 + Spring 集成测试（共 20 个用例）
mvn test

# 只跑某一类
mvn test -Dtest=BasicObjectTest         # 版本/删除/条件更新/并发/去重
mvn test -Dtest=MultipartUploadTest     # 乱序/重复/非法分片/会话失效/重启续传
mvn test -Dtest=RecoveryCrashTest       # 写入/合并/回收中途崩溃后的恢复
mvn test -Dtest=ConcurrencyTest         # 并发写、并发分片提交
mvn test -Dtest=WebApiTest                # 完整 HTTP 链路与错误码状态码
```

测试覆盖场景：

- **并发写入**：16 线程无条件写同一键，全部成功且版本数恰等于写入次数，版本号互不重复；
- **条件更新冲突**：并发下基于同一旧版本仅 1 个成功，其余得到 `VERSION_CONFLICT`；
- **重复内容**：相同内容多键/多版本/不同上传路径写入，物理内容块始终只有 1 份；
- **上传中断恢复**：分片写入、合并提交（COMMITTING 前后两个崩溃点）、回收中途杀掉进程后
  重启，断言元数据与内容块一致、无悬空版本、无残留临时数据、结果不随重启次数变化；
- **非法分片**：校验和不符、序号越界、大小不符、缺失分片、缺校验头、同号异内容、
  会话不存在/已中止/已超时，各自返回可区分 errorCode。

测试中的关键请求参数（key、uploadId、partNumber、声明/实际摘要、If-Match、partSize 等）
与判定依据都会通过 SLF4J 打印，并记录在 `target/surefire-reports/TEST-*.xml` 中。

### curl 快速体验

```bash
JAR=target/versioned-object-storage-java-1.0-SNAPSHOT.jar
java -jar $JAR --server.port=8080 --vostore.root=/tmp/vos-demo &

B=http://localhost:8080/api
SHA=$(printf 'hello-world' | sha256sum | cut -d' ' -f1)
curl -s -X PUT $B/objects/demo.txt -H "X-Content-SHA256: $SHA" --data-binary 'hello-world'
curl -s $B/objects/demo.txt
curl -s -X POST "$B/multipart/demo.bin?partSize=5"
# 用返回的 uploadId 上传 part-1/part-2（带 X-Content-SHA256），再 complete...
curl -s $B/admin/stats
```
