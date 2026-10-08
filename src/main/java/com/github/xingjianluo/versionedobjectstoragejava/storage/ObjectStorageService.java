package com.github.xingjianluo.versionedobjectstoragejava.storage;

import com.github.xingjianluo.versionedobjectstoragejava.api.ApiException;
import com.github.xingjianluo.versionedobjectstoragejava.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 对象存储核心业务：版本化读写、条件更新、分片上传、去重与回收。
 * 一致性约定：先持久化内容，再追加版本元数据并落盘，
 * 因此任何可见版本必然指向已存在的内容。
 */
public class ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageService.class);

    private final BlobStore blobStore;
    private final MetadataStore metadataStore;
    private final Path uploadDir;

    public ObjectStorageService(BlobStore blobStore, MetadataStore metadataStore, Path uploadDir) {
        this.blobStore = blobStore;
        this.metadataStore = metadataStore;
        this.uploadDir = uploadDir;
    }

    /**
     * 启动恢复：
     * 1. 清理内容写入中断遗留的临时文件；
     * 2. 收敛 COMMITTING 会话：预定版本已生效的补完为 COMPLETED；
     *    版本未生效但内容已就位（先内容后元数据的顺序保证）的，补写版本元数据后标记 COMPLETED；
     *    内容缺失的（防御性分支，正常不会发生）回滚为 ACTIVE，客户端可重新提交；
     * 3. 已结束（完成/中止）会话的分片目录直接清理；
     * 4. 活跃会话保留分片，可继续续传；
     * 5. 元数据中没有对应会话记录的孤儿分片目录一律清理；
     * 6. 校验所有可见版本指向的内容确实存在（防御性一致性检查）。
     */
    public synchronized void recover() throws IOException {
        int tmpRemoved = blobStore.cleanTempFiles();
        Files.createDirectories(uploadDir);
        Map<String, UploadSessionState> sessions = metadataStore.allSessions();
        int commitFinished = 0;
        int commitRolledBack = 0;
        for (UploadSessionState session : sessions.values()) {
            if (session.getStatus() != UploadSessionState.Status.COMMITTING) {
                continue;
            }
            StoredVersion committed = session.getCommittedVersion();
            if (committed != null && blobStore.exists(committed.contentHash())) {
                // 内容在提交意图落盘前已写入内容存储，补完是安全的：
                // 此刻元数据与崩溃瞬间一致，当时的条件更新检查结论仍然成立。
                if (metadataStore.findVersion(session.getObjectKey(), committed.versionId()).isEmpty()) {
                    metadataStore.appendVersion(session.getObjectKey(), committed);
                    log.info("recover: 补完中断的提交 uploadId={} key={} versionId={}",
                            session.getUploadId(), session.getObjectKey(), committed.versionId());
                }
                session.setStatus(UploadSessionState.Status.COMPLETED);
                metadataStore.putSession(session);
                commitFinished++;
            } else {
                // 防御性回滚：内容缺失说明提交未生效，回到 ACTIVE 允许客户端重试。
                session.setStatus(UploadSessionState.Status.ACTIVE);
                session.setCommittedVersion(null);
                metadataStore.putSession(session);
                commitRolledBack++;
                log.warn("recover: 提交内容缺失，回滚会话 uploadId={} key={}",
                        session.getUploadId(), session.getObjectKey());
            }
        }
        int orphanDirs = 0;
        int finishedDirs = 0;
        try (Stream<Path> stream = Files.list(uploadDir)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                String uploadId = dir.getFileName().toString();
                UploadSessionState session = sessions.get(uploadId);
                if (session == null) {
                    deleteRecursively(dir);
                    orphanDirs++;
                } else if (session.getStatus() != UploadSessionState.Status.ACTIVE) {
                    deleteRecursively(dir);
                    finishedDirs++;
                }
            }
        }
        // 防御性检查：可见版本不得指向不存在的内容。
        for (Map.Entry<String, List<StoredVersion>> e : metadataStore.allObjects().entrySet()) {
            for (StoredVersion v : e.getValue()) {
                if (!v.deleted() && !blobStore.exists(v.contentHash())) {
                    throw new IOException("元数据与内容不一致: key=" + e.getKey()
                            + " version=" + v.versionId() + " 缺失内容 " + v.contentHash());
                }
            }
        }
        log.info("recover done: blobTmpRemoved={}, commitFinished={}, commitRolledBack={}, orphanUploadDirs={}, finishedUploadDirs={}, activeSessions={}",
                tmpRemoved, commitFinished, commitRolledBack, orphanDirs, finishedDirs,
                sessions.values().stream().filter(s -> s.getStatus() == UploadSessionState.Status.ACTIVE).count());
    }

    /** 直接写入对象，产生新版本；expectedVersionId 非空时做条件更新。 */
    public synchronized StoredVersion putObject(String key, InputStream in, String expectedVersionId) throws IOException {
        checkConditional(key, expectedVersionId);
        String hash = blobStore.put(in);
        long size = blobStore.get(hash).length;
        StoredVersion version = new StoredVersion(MetadataStore.newVersionId(), hash, size, false, System.currentTimeMillis());
        metadataStore.appendVersion(key, version);
        log.info("putObject key={} expectedVersion={} -> versionId={} hash={} size={}",
                key, expectedVersionId, version.versionId(), hash, size);
        return version;
    }

    /** 删除对象（写入墓碑版本）；历史版本保持可读。 */
    public synchronized StoredVersion deleteObject(String key, String expectedVersionId) throws IOException {
        StoredVersion latest = metadataStore.latestVersion(key)
                .orElseThrow(() -> new ApiException(ErrorCode.OBJECT_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "object not found: " + key));
        if (latest.deleted()) {
            throw new ApiException(ErrorCode.OBJECT_NOT_FOUND, HttpStatus.NOT_FOUND,
                    "object already deleted: " + key);
        }
        checkConditional(key, expectedVersionId);
        StoredVersion tombstone = new StoredVersion(MetadataStore.newVersionId(), null, 0, true, System.currentTimeMillis());
        metadataStore.appendVersion(key, tombstone);
        log.info("deleteObject key={} expectedVersion={} -> tombstoneVersion={}", key, expectedVersionId, tombstone.versionId());
        return tombstone;
    }

    /** 读取对象内容；versionId 为空时读取最新版本；墓碑按不存在处理。 */
    public synchronized byte[] getObject(String key, String versionId) throws IOException {
        StoredVersion version;
        if (versionId == null || versionId.isBlank()) {
            version = metadataStore.latestVersion(key)
                    .orElseThrow(() -> new ApiException(ErrorCode.OBJECT_NOT_FOUND, HttpStatus.NOT_FOUND,
                            "object not found: " + key));
        } else {
            if (metadataStore.listVersions(key).isEmpty()) {
                throw new ApiException(ErrorCode.OBJECT_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "object not found: " + key);
            }
            version = metadataStore.findVersion(key, versionId)
                    .orElseThrow(() -> new ApiException(ErrorCode.VERSION_NOT_FOUND, HttpStatus.NOT_FOUND,
                            "version not found: " + key + "@" + versionId));
        }
        if (version.deleted()) {
            throw new ApiException(ErrorCode.OBJECT_NOT_FOUND, HttpStatus.NOT_FOUND,
                    "object is deleted: " + key + "@" + version.versionId());
        }
        byte[] data = blobStore.get(version.contentHash());
        if (data == null) {
            throw new IllegalStateException("可见版本缺失内容: " + key + "@" + version.versionId());
        }
        log.info("getObject key={} versionId={} -> resolved={} size={}", key, versionId, version.versionId(), data.length);
        return data;
    }

    public synchronized List<StoredVersion> listVersions(String key) {
        return metadataStore.listVersions(key);
    }

    public synchronized UploadSessionState createUpload(String key) throws IOException {
        UploadSessionState session = new UploadSessionState();
        session.setUploadId(UUID.randomUUID().toString());
        session.setObjectKey(key);
        session.setCreatedAtEpochMs(System.currentTimeMillis());
        metadataStore.putSession(session);
        Files.createDirectories(uploadDir.resolve(session.getUploadId()));
        log.info("createUpload key={} -> uploadId={}", key, session.getUploadId());
        return session;
    }

    /** 上传分片（幂等：同分片号同校验和重复提交不产生重复数据）。 */
    public synchronized void uploadPart(String uploadId, int partNumber, InputStream in, String checksumSha256) throws IOException {
        UploadSessionState session = requireActiveSession(uploadId);
        if (partNumber < 1) {
            throw new ApiException(ErrorCode.INVALID_PART, HttpStatus.BAD_REQUEST,
                    "partNumber must be >= 1, got " + partNumber);
        }
        String existing = session.getParts().get(partNumber);
        if (existing != null && existing.equalsIgnoreCase(checksumSha256)
                && Files.exists(partPath(uploadId, partNumber))) {
            log.info("uploadPart uploadId={} part={} checksum={} -> 已存在相同分片，幂等跳过", uploadId, partNumber, checksumSha256);
            return;
        }
        if (existing != null && !existing.equalsIgnoreCase(checksumSha256)) {
            throw new ApiException(ErrorCode.CHECKSUM_MISMATCH, HttpStatus.CONFLICT,
                    "part " + partNumber + " already uploaded with different checksum: " + existing);
        }
        // 先写临时文件并计算实际摘要，校验通过后原子改名就位。
        MessageDigest digest = BlobStore.newDigest();
        Path tmp = uploadDir.resolve(uploadId).resolve("part-" + partNumber + ".tmp");
        Files.createDirectories(tmp.getParent());
        try (OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        }
        String actual = BlobStore.toHex(digest.digest());
        if (!actual.equalsIgnoreCase(checksumSha256)) {
            Files.deleteIfExists(tmp);
            log.info("uploadPart uploadId={} part={} 校验和不匹配: 声明={} 实际={}", uploadId, partNumber, checksumSha256, actual);
            throw new ApiException(ErrorCode.CHECKSUM_MISMATCH, HttpStatus.BAD_REQUEST,
                    "checksum mismatch for part " + partNumber + ": declared=" + checksumSha256 + " actual=" + actual);
        }
        Files.move(tmp, partPath(uploadId, partNumber), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        session.getParts().put(partNumber, actual);
        metadataStore.putSession(session);
        log.info("uploadPart uploadId={} part={} checksum={} -> 已接收", uploadId, partNumber, actual);
    }

    /**
     * 合并分片完成上传，产生新版本；分片必须连续从 1 开始且校验和全部匹配。
     * 提交采用两段式落盘，可放心重试：
     * 1. 内容写入内容存储后，先把「提交意图」（COMMITTING + 预定版本）落盘，版本标识从此固定；
     * 2. 追加版本元数据落盘，版本对外可见；
     * 3. 会话标记 COMPLETED 落盘，清理分片。
     * 客户端在任意时刻断线或进程退出后重试（含重启后重试）：
     * 会话已 COMPLETED 时直接返回首次提交的同一个版本，不产生新版本；
     * 进程在提交中途退出时，重启恢复会把会话收敛到 COMPLETED（补完）或 ACTIVE（可重新提交），
     * 同一个上传会话永远只产生一个对象版本。并发重试由方法级互斥串行化，
     * 只有第一个请求真正提交，其余请求拿到同一个结果。
     */
    public synchronized StoredVersion completeUpload(String uploadId, String expectedVersionId) throws IOException {
        UploadSessionState session = metadataStore.getSession(uploadId)
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "upload session not found: " + uploadId));
        if (session.getStatus() == UploadSessionState.Status.COMPLETED) {
            StoredVersion committed = session.getCommittedVersion();
            if (committed != null) {
                log.info("completeUpload uploadId={} 幂等重试 -> 返回首次提交的版本 versionId={}",
                        uploadId, committed.versionId());
                return committed;
            }
            // 兼容旧数据目录：历史 COMPLETED 会话没有记录提交结果，只能按会话失效处理。
            throw new ApiException(ErrorCode.SESSION_EXPIRED, HttpStatus.CONFLICT,
                    "upload session " + uploadId + " is COMPLETED (result not recorded)");
        }
        if (session.getStatus() == UploadSessionState.Status.ABORTED) {
            throw new ApiException(ErrorCode.SESSION_EXPIRED, HttpStatus.CONFLICT,
                    "upload session " + uploadId + " is ABORTED");
        }
        if (session.getStatus() == UploadSessionState.Status.COMMITTING) {
            // 正常运行期不会出现（recover 已收敛）；防御性拒绝，提示重试触发收敛。
            throw new ApiException(ErrorCode.SESSION_EXPIRED, HttpStatus.CONFLICT,
                    "upload session " + uploadId + " is COMMITTING, retry after recovery");
        }
        Map<Integer, String> parts = session.getParts();
        if (parts.isEmpty()) {
            throw new ApiException(ErrorCode.MISSING_PARTS, HttpStatus.BAD_REQUEST,
                    "no parts uploaded for session " + uploadId);
        }
        int max = parts.keySet().stream().max(Comparator.naturalOrder()).orElse(0);
        List<Integer> missing = new ArrayList<>();
        for (int i = 1; i <= max; i++) {
            if (!parts.containsKey(i) || !Files.exists(partPath(uploadId, i))) {
                missing.add(i);
            }
        }
        if (!missing.isEmpty()) {
            log.info("completeUpload uploadId={} 缺失分片: {}", uploadId, missing);
            throw new ApiException(ErrorCode.MISSING_PARTS, HttpStatus.BAD_REQUEST,
                    "missing parts: " + missing);
        }
        // 合并前逐个校验分片内容，再写入内容存储（先内容后元数据）。
        checkConditional(session.getObjectKey(), expectedVersionId);
        Path merged = uploadDir.resolve(uploadId).resolve("merged.tmp");
        try (OutputStream out = Files.newOutputStream(merged)) {
            for (int i = 1; i <= max; i++) {
                Path partFile = partPath(uploadId, i);
                byte[] data = Files.readAllBytes(partFile);
                String actual = BlobStore.toHex(BlobStore.newDigest().digest(data));
                if (!actual.equalsIgnoreCase(parts.get(i))) {
                    Files.deleteIfExists(merged);
                    throw new ApiException(ErrorCode.CHECKSUM_MISMATCH, HttpStatus.BAD_REQUEST,
                            "part " + i + " content corrupted: expected=" + parts.get(i) + " actual=" + actual);
                }
                out.write(data);
            }
        }
        String hash;
        try (InputStream in = Files.newInputStream(merged)) {
            hash = blobStore.put(in);
        }
        long size = Files.size(merged);
        StoredVersion version = new StoredVersion(MetadataStore.newVersionId(), hash, size, false, System.currentTimeMillis());
        // 两段式提交：先落盘提交意图（版本标识固定），再让版本生效，最后标记完成。
        session.setCommittedVersion(version);
        session.setStatus(UploadSessionState.Status.COMMITTING);
        metadataStore.putSession(session);
        metadataStore.appendVersion(session.getObjectKey(), version);
        session.setStatus(UploadSessionState.Status.COMPLETED);
        metadataStore.putSession(session);
        deleteRecursively(uploadDir.resolve(uploadId));
        log.info("completeUpload uploadId={} key={} parts={} -> versionId={} hash={} size={}",
                uploadId, session.getObjectKey(), max, version.versionId(), hash, size);
        return version;
    }

    public synchronized void abortUpload(String uploadId) throws IOException {
        UploadSessionState session = requireActiveSession(uploadId);
        session.setStatus(UploadSessionState.Status.ABORTED);
        metadataStore.putSession(session);
        deleteRecursively(uploadDir.resolve(uploadId));
        log.info("abortUpload uploadId={} key={}", uploadId, session.getObjectKey());
    }

    /**
     * 显式回收历史版本：将指定版本从元数据中移除（最新版本不允许回收）。
     * 被回收版本引用的内容在此之后才可能被 GC 清除。
     */
    public synchronized List<StoredVersion> reclaimVersions(String key, List<String> versionIds) throws IOException {
        String latestId = metadataStore.latestVersion(key).map(StoredVersion::versionId).orElse(null);
        if (latestId != null && versionIds.contains(latestId)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                    "cannot reclaim the latest version: " + key + "@" + latestId);
        }
        List<StoredVersion> removed = metadataStore.removeVersions(key, versionIds);
        log.info("reclaimVersions key={} requested={} removed={}", key, versionIds,
                removed.stream().map(StoredVersion::versionId).toList());
        return removed;
    }

    /** 回收不被任何非删除版本引用的内容，返回回收的内容哈希列表。可安全重跑：引用集每次从元数据重新计算。 */
    public synchronized List<String> garbageCollect() throws IOException {
        Set<String> referenced = referencedHashes();
        List<String> removed = new ArrayList<>();
        for (String hash : blobStore.listAll()) {
            if (!referenced.contains(hash)) {
                blobStore.delete(hash);
                removed.add(hash);
            }
        }
        log.info("garbageCollect referenced={} removed={}", referenced.size(), removed.size());
        return removed;
    }

    /**
     * 只读容量视图：统计磁盘占用、仍被引用的部分、以及不再被任何版本引用可安全释放的部分。
     * 不删除、不修改任何数据，可任意时刻调用。
     */
    public synchronized StorageStats storageStats() throws IOException {
        Set<String> referenced = referencedHashes();
        Map<String, Long> onDisk = blobStore.listAllWithSizes();
        long totalBytes = 0;
        long referencedBytes = 0;
        long referencedBlobs = 0;
        for (Map.Entry<String, Long> e : onDisk.entrySet()) {
            totalBytes += e.getValue();
            if (referenced.contains(e.getKey())) {
                referencedBlobs++;
                referencedBytes += e.getValue();
            }
        }
        StorageStats stats = new StorageStats(onDisk.size(), totalBytes,
                referencedBlobs, referencedBytes,
                onDisk.size() - referencedBlobs, totalBytes - referencedBytes);
        log.info("storageStats totalBlobs={} totalBytes={} referencedBlobs={} referencedBytes={} reclaimableBlobs={} reclaimableBytes={}",
                stats.totalBlobs(), stats.totalBytes(), stats.referencedBlobs(),
                stats.referencedBytes(), stats.reclaimableBlobs(), stats.reclaimableBytes());
        return stats;
    }

    /** 当前被至少一个非删除版本引用的内容哈希集合。 */
    private Set<String> referencedHashes() {
        Set<String> referenced = new HashSet<>();
        for (List<StoredVersion> versions : metadataStore.allObjects().values()) {
            for (StoredVersion v : versions) {
                if (!v.deleted() && v.contentHash() != null) {
                    referenced.add(v.contentHash());
                }
            }
        }
        return referenced;
    }

    private void checkConditional(String key, String expectedVersionId) {
        if (expectedVersionId == null || expectedVersionId.isBlank()) {
            return;
        }
        String latestId = metadataStore.latestVersion(key).map(StoredVersion::versionId).orElse(null);
        if (!expectedVersionId.equals(latestId)) {
            log.info("条件更新冲突 key={} expected={} latest={}", key, expectedVersionId, latestId);
            throw new ApiException(ErrorCode.VERSION_CONFLICT, HttpStatus.CONFLICT,
                    "version conflict for " + key + ": expected=" + expectedVersionId + " latest=" + latestId);
        }
    }

    private UploadSessionState requireActiveSession(String uploadId) {
        UploadSessionState session = metadataStore.getSession(uploadId)
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "upload session not found: " + uploadId));
        if (session.getStatus() != UploadSessionState.Status.ACTIVE) {
            throw new ApiException(ErrorCode.SESSION_EXPIRED, HttpStatus.CONFLICT,
                    "upload session " + uploadId + " is " + session.getStatus());
        }
        return session;
    }

    private Path partPath(String uploadId, int partNumber) {
        return uploadDir.resolve(uploadId).resolve("part-" + partNumber);
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
