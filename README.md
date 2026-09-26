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
| POST | `/uploads/{uploadId}/complete` | 合并分片并产生新版本；请求体可带 `{"expectedVersionId":"..."}` |
| DELETE | `/uploads/{uploadId}` | 中止会话并清理分片 |

### 运维

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/admin/gc` | 回收不被任何版本引用的内容，返回回收列表 |

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

## 一致性与崩溃恢复

- 写入顺序恒为「先内容、后元数据」：内容先写临时文件再原子改名就位，版本元数据落盘（同样是临时文件 + 原子改名）后版本才可见。因此任何可见版本必然指向已存在的内容。
- 进程在写入或回收中途退出后重启，启动恢复流程会：
  1. 清理内容写入中断遗留的临时文件；
  2. 清理已完成/已中止会话的分片目录；
  3. 清理元数据中无会话记录的孤儿分片目录；
  4. 保留活跃会话及其已上传分片，客户端可继续续传；
  5. 防御性校验所有可见版本指向的内容确实存在，不一致则拒绝启动。
- 分片上传幂等：同一会话内重复提交「同分片号 + 同校验和」的分片直接成功且不产生重复数据；同分片号不同校验和会被拒绝（`CHECKSUM_MISMATCH`）。乱序提交的分片在完成时按分片号顺序合并。

## 测试覆盖

- `ObjectStorageServiceTests`：并发条件写入（仅一个成功，其余 `VERSION_CONFLICT`）、并发无条件写入（版本全部可见）、条件更新冲突且状态不变、重复内容去重与 GC、删除后重写保留历史、乱序/重复分片、非法分片各类失败原因、上传中断后重启续传、孤儿临时数据清理、重启后版本与内容一致。
- `StorageControllerTests`：REST 端到端，验证各错误码经 HTTP 响应可区分。
- `ApplicationTests`：Spring 上下文启动。

测试日志会打印关键请求参数（key、版本标识、校验和、分片号等）与判定依据（成功/冲突计数、错误码、哈希比对结果），便于复核。
