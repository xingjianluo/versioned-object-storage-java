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
     * 启动恢复（幂等，可反复执行）：
     * 1. 清理内容写入中断遗留的临时文件、元数据落盘中断遗留的临时文件；
     * 2. 已结束（完成/中止）会话的分片目录直接清理；
     * 3. 活跃会话：保留已就位分片供续传，仅清理上传/合并中断遗留的 *.tmp；
     * 4. 元数据中没有对应会话记录的孤儿分片目录一律清理；
     * 5. 校验所有可见版本指向的内容确实存在（防御性一致性检查）。
     * 分片完成提交是原子的（版本与会话完成同一次落盘），因此不存在
     * 「版本已可见但会话仍 ACTIVE」的中间态，恢复无需补提交或回滚版本。
     */
    public synchronized void recover() throws IOException {
        int tmpRemoved = blobStore.cleanTempFiles();
        metadataStore.cleanStaleTempFile();
        Files.createDirectories(uploadDir);
        Map<String, UploadSessionState> sessions = metadataStore.allSessions();
        int orphanDirs = 0;
        int finishedDirs = 0;
        int sessionTmpRemoved = 0;
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
                } else {
                    // 活跃会话：只清理中断残留的临时文件，保留 part-N 分片供续传。
                    sessionTmpRemoved += cleanSessionTempFiles(dir);
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
        log.info("recover done: blobTmpRemoved={}, orphanUploadDirs={}, finishedUploadDirs={}, sessionTmpRemoved={}, activeSessions={} (metadata.json.tmp cleaned if present)",
                tmpRemoved, orphanDirs, finishedDirs, sessionTmpRemoved,
                sessions.values().stream().filter(s -> s.getStatus() == UploadSessionState.Status.ACTIVE).count());
    }

    /** 清理单个活跃会话目录内中断残留的临时文件（part-N.tmp、merged.tmp），返回清理数量。 */
    private int cleanSessionTempFiles(Path sessionDir) throws IOException {
        int removed = 0;
        try (Stream<Path> stream = Files.list(sessionDir)) {
            for (Path p : stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".tmp")).toList()) {
                Files.deleteIfExists(p);
                removed++;
            }
        }
        return removed;
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
     * 该步骤可安全重试（同进程或重启后）：
     * 一旦版本提交成功（版本元数据与会话完成状态在同一次原子落盘中生效），
     * 之后任意次数的 complete 都直接返回同一个版本结果，绝不产生第二个版本，
     * 也不会因为会话已完成而报 SESSION_EXPIRED。并发 complete 由 synchronized 串行化，
     * 只有第一个请求真正提交，其余请求拿到同一结果。
     */
    public synchronized StoredVersion completeUpload(String uploadId, String expectedVersionId) throws IOException {
        UploadSessionState session = metadataStore.getSession(uploadId)
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "upload session not found: " + uploadId));
        // 已提交过：幂等返回首次提交结果（提交即承诺，重试不再做条件判断）。
        if (session.getStatus() == UploadSessionState.Status.COMPLETED || session.getCompletedVersion() != null) {
            StoredVersion committed = session.getCompletedVersion();
            if (committed == null) {
                // 理论上不可达：COMPLETED 一定携带提交结果。防御性兜底。
                throw new ApiException(ErrorCode.SESSION_EXPIRED, HttpStatus.CONFLICT,
                        "upload session " + uploadId + " completed but result is missing");
            }
            log.info("completeUpload uploadId={} 已提交过，幂等返回 versionId={} hash={}",
                    uploadId, committed.versionId(), committed.contentHash());
            return committed;
        }
        if (session.getStatus() == UploadSessionState.Status.ABORTED) {
            throw new ApiException(ErrorCode.SESSION_EXPIRED, HttpStatus.CONFLICT,
                    "upload session " + uploadId + " is ABORTED");
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
        // 仅在首次提交前做条件更新校验；提交成功后的重试一律返回提交结果。
        checkConditional(session.getObjectKey(), expectedVersionId);
        // 合并前逐个校验分片内容，再写入内容存储（先内容后元数据）。
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
        // 关键一步：版本可见 + 会话完成在同一次原子落盘提交。
        // 落盘前崩溃：什么都没生效，客户端可原样重试；落盘后崩溃：结果已确定，重试幂等返回。
        metadataStore.commitCompletedUpload(session.getObjectKey(), session, version);
        // 提交完成后清理分片目录仅为空间优化；失败不影响正确性（重启恢复会兜底清理）。
        try {
            deleteRecursively(uploadDir.resolve(uploadId));
        } catch (IOException e) {
            log.warn("completeUpload uploadId={} 分片目录清理失败，将在重启恢复时兜底: {}", uploadId, e.toString());
        }
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

    /**
     * 回收不被任何非删除版本引用的内容，返回回收的内容哈希列表。
     * 该操作天然幂等、可安全重跑：每一轮都先按当前元数据重新计算引用集合，
     * 只删除引用集合之外的内容；执行到一半崩溃后重跑，结果一致，
     * 绝不会删除仍被任何可读版本引用的内容。
     */
    public synchronized List<String> garbageCollect() throws IOException {
        Set<String> referenced = collectReferencedHashes();
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
     * 只读容量视图：统计内容存储的总占用、仍被可读版本引用的占用，
     * 以及不被任何版本引用、可安全释放的部分。本方法只读取磁盘与元数据，
     * 不删除、不移动任何文件。引用集合的计算口径与 {@link #garbageCollect()} 完全一致。
     */
    public synchronized CapacityView capacityView() throws IOException {
        Set<String> referenced = collectReferencedHashes();
        List<BlobStore.BlobInfo> all = blobStore.listBlobInfos();
        long totalBytes = 0;
        long referencedBytes = 0;
        List<String> reclaimable = new ArrayList<>();
        long reclaimableBytes = 0;
        for (BlobStore.BlobInfo info : all) {
            totalBytes += info.size();
            if (referenced.contains(info.hash())) {
                referencedBytes += info.size();
            } else {
                reclaimable.add(info.hash());
                reclaimableBytes += info.size();
            }
        }
        reclaimable.sort(Comparator.naturalOrder());
        CapacityView view = new CapacityView(totalBytes, all.size(), referencedBytes, reclaimableBytes, reclaimable);
        log.info("capacityView totalBlobs={} totalBytes={} referencedBytes={} reclaimableBlobs={} reclaimableBytes={}",
                view.totalBlobCount(), view.totalBytes(), view.referencedBytes(),
                view.reclaimableBlobs().size(), view.reclaimableBytes());
        return view;
    }

    /** 计算仍被任一非墓碑版本引用的内容哈希集合（GC 与容量视图共用同一口径）。 */
    private Set<String> collectReferencedHashes() {
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
