package com.github.xingjianluo.versionedobjectstoragejava.tests;

import com.github.xingjianluo.versionedobjectstoragejava.storage.BlobStore;
import com.github.xingjianluo.versionedobjectstoragejava.storage.MetadataStore;
import com.github.xingjianluo.versionedobjectstoragejava.storage.ObjectStorageService;
import com.github.xingjianluo.versionedobjectstoragejava.storage.StorageStats;
import com.github.xingjianluo.versionedobjectstoragejava.storage.StoredVersion;
import com.github.xingjianluo.versionedobjectstoragejava.storage.UploadSessionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 崩溃恢复与重试语义测试：
 * 上传收尾可安全重试（含跨重启）、提交中途崩溃后重启自愈、
 * 回收中断后重跑结果一致、容量视图只读。
 * 每个用例打印关键参数与判定依据。
 */
class CrashRecoveryTests {

    private static final Logger log = LoggerFactory.getLogger(CrashRecoveryTests.class);

    @TempDir
    Path dataDir;

    /** 在指定位置注入崩溃的元数据存储：模拟进程在收尾不同阶段退出（内存状态直接丢弃，只保留已落盘数据）。 */
    static class CrashMetadataStore extends MetadataStore {
        boolean failOnCommittingFlush;
        boolean failOnCompletedFlush;
        boolean failOnAppendVersion;

        CrashMetadataStore(Path metadataFile) {
            super(metadataFile);
        }

        @Override
        public synchronized void putSession(UploadSessionState session) throws IOException {
            if (failOnCommittingFlush && session.getStatus() == UploadSessionState.Status.COMMITTING) {
                throw new IOException("模拟崩溃：提交意图落盘前进程退出");
            }
            if (failOnCompletedFlush && session.getStatus() == UploadSessionState.Status.COMPLETED) {
                throw new IOException("模拟崩溃：COMPLETED 标记落盘前进程退出");
            }
            super.putSession(session);
        }

        @Override
        public synchronized StoredVersion appendVersion(String key, StoredVersion version) throws IOException {
            if (failOnAppendVersion) {
                throw new IOException("模拟崩溃：版本元数据落盘前进程退出");
            }
            return super.appendVersion(key, version);
        }
    }

    /** 删除指定次数后模拟进程退出的内容存储：用于复现回收执行到一半崩溃。 */
    static class FlakyBlobStore extends BlobStore {
        private int deletesAllowed;

        FlakyBlobStore(Path blobDir, int deletesAllowed) {
            super(blobDir);
            this.deletesAllowed = deletesAllowed;
        }

        @Override
        public synchronized boolean delete(String contentHash) throws IOException {
            if (deletesAllowed-- <= 0) {
                throw new IOException("模拟崩溃：回收执行到一半进程退出");
            }
            return super.delete(contentHash);
        }
    }

    private ObjectStorageService newService(BlobStore bs, MetadataStore ms) throws IOException {
        ms.load();
        ObjectStorageService s = new ObjectStorageService(bs, ms, dataDir.resolve("uploads"));
        s.recover();
        return s;
    }

    /** 模拟进程重启：在同一数据目录上重建全部组件并执行恢复。 */
    private ObjectStorageService restart() throws IOException {
        return newService(new BlobStore(dataDir.resolve("blobs")),
                new MetadataStore(dataDir.resolve("metadata.json")));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : d.digest(data)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static StoredVersion putObj(ObjectStorageService s, String key, byte[] data) throws IOException {
        return s.putObject(key, new ByteArrayInputStream(data), null);
    }

    private static String uploadOnePart(ObjectStorageService s, String key, byte[] content) throws IOException {
        UploadSessionState session = s.createUpload(key);
        s.uploadPart(session.getUploadId(), 1, new ByteArrayInputStream(content), sha256(content));
        return session.getUploadId();
    }

    private Set<String> blobHashes() throws IOException {
        return new BlobStore(dataDir.resolve("blobs")).listAll();
    }

    private List<String> uploadDirEntries() throws IOException {
        Path uploads = dataDir.resolve("uploads");
        if (!Files.isDirectory(uploads)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(uploads)) {
            return stream.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void completeRetryAcrossRestart_singleVersionSameResult() throws IOException {
        ObjectStorageService s = restart();
        byte[] content = bytes("retry-content");
        String uploadId = uploadOnePart(s, "retry-key", content);
        StoredVersion v1 = s.completeUpload(uploadId, null);
        log.info("首次完成: uploadId={} -> versionId={} hash={}", uploadId, v1.versionId(), v1.contentHash());

        // 客户端没收到响应，进程随后重启；重启后再重试同一个上传会话的完成请求。
        ObjectStorageService s2 = restart();
        StoredVersion v2 = s2.completeUpload(uploadId, null);
        // 携带过期条件版本标识重试，也必须拿到同一个已提交结果而不是冲突。
        StoredVersion v3 = s2.completeUpload(uploadId, "stale-version-id");
        log.info("重启后重试: versionId={} 与首次一致={} 版本总数={}",
                v2.versionId(), v2.versionId().equals(v1.versionId()), s2.listVersions("retry-key").size());

        assertEquals(v1.versionId(), v2.versionId(), "跨重启重试必须返回首次提交的同一个版本标识");
        assertEquals(v1.contentHash(), v2.contentHash());
        assertEquals(v1.versionId(), v3.versionId(), "携带过期条件的重试也必须返回同一个已提交结果");
        assertEquals(1, s2.listVersions("retry-key").size(), "同一上传会话重试不得产生第二个版本");
        assertArrayEquals(content, s2.getObject("retry-key", null), "重试后内容必须可读且一致");
    }

    @Test
    void concurrentCompleteRetries_singleVersionPublished() throws Exception {
        ObjectStorageService s = restart();
        byte[] content = bytes("concurrent-complete");
        String uploadId = uploadOnePart(s, "race-key", content);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<StoredVersion> results = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                results.add(s.completeUpload(uploadId, null));
                return null;
            }));
        }
        ready.await();
        go.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        long distinct = results.stream().map(StoredVersion::versionId).distinct().count();
        log.info("并发重试完成: threads={} 全部成功={} 不同versionId数={} 版本总数={}",
                threads, results.size(), distinct, s.listVersions("race-key").size());
        assertEquals(threads, results.size(), "并发重试都应成功返回（同一个结果）");
        assertEquals(1, distinct, "并发重试只能有一个版本真正发出，其余必须返回同一个结果");
        assertEquals(1, s.listVersions("race-key").size(), "并发重试不得产生多个版本");
        assertArrayEquals(content, s.getObject("race-key", null));
    }

    @Test
    void crashAfterVersionAppendedBeforeCompletedFlag_recoverCompletesCommit() throws IOException {
        // 崩溃点：版本元数据已落盘、会话 COMPLETED 标记落盘前。
        CrashMetadataStore crashMs = new CrashMetadataStore(dataDir.resolve("metadata.json"));
        ObjectStorageService s = newService(new BlobStore(dataDir.resolve("blobs")), crashMs);
        byte[] content = bytes("crash-after-append");
        String uploadId = uploadOnePart(s, "heal-key", content);
        crashMs.failOnCompletedFlush = true;
        IOException crash = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> s.completeUpload(uploadId, null));
        log.info("模拟崩溃: uploadId={} 崩溃点=COMPLETED落盘前 异常={}", uploadId, crash.getMessage());

        // 重启：恢复流程应把 COMMITTING 会话补完为 COMPLETED（版本已生效，仅补标记）。
        ObjectStorageService s2 = restart();
        UploadSessionState healed = s2.listVersions("heal-key").isEmpty() ? null : null;
        List<StoredVersion> versions = s2.listVersions("heal-key");
        log.info("重启自愈: 版本数={} versionId={}", versions.size(),
                versions.isEmpty() ? "-" : versions.get(0).versionId());
        assertEquals(1, versions.size(), "崩溃前已生效的版本重启后必须可见");

        // 重试完成请求：返回同一个版本，不产生新版本。
        StoredVersion retried = s2.completeUpload(uploadId, null);
        assertEquals(versions.get(0).versionId(), retried.versionId(), "补完后重试必须返回同一个版本");
        assertEquals(1, s2.listVersions("heal-key").size());
        assertArrayEquals(content, s2.getObject("heal-key", null));
        assertTrue(uploadDirEntries().isEmpty(), "已完成的会话不得遗留分片目录: " + uploadDirEntries());
    }

    @Test
    void crashAfterCommitIntentBeforeVersionAppend_recoverCompletesCommit() throws IOException {
        // 崩溃点：提交意图（COMMITTING + 预定版本）已落盘、版本元数据落盘前。
        CrashMetadataStore crashMs = new CrashMetadataStore(dataDir.resolve("metadata.json"));
        ObjectStorageService s = newService(new BlobStore(dataDir.resolve("blobs")), crashMs);
        byte[] content = bytes("crash-before-append");
        String uploadId = uploadOnePart(s, "heal-key2", content);
        crashMs.failOnAppendVersion = true;
        IOException crash = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> s.completeUpload(uploadId, null));
        log.info("模拟崩溃: uploadId={} 崩溃点=版本元数据落盘前 异常={}", uploadId, crash.getMessage());

        // 重启：内容已在内容存储（先内容后元数据），恢复流程补写版本元数据完成提交。
        ObjectStorageService s2 = restart();
        List<StoredVersion> versions = s2.listVersions("heal-key2");
        log.info("重启自愈: 补写版本后版本数={} versionId={}", versions.size(),
                versions.isEmpty() ? "-" : versions.get(0).versionId());
        assertEquals(1, versions.size(), "恢复流程必须把中断的提交补完，版本可见");

        StoredVersion retried = s2.completeUpload(uploadId, null);
        assertEquals(versions.get(0).versionId(), retried.versionId(), "重试必须返回补完出来的同一个版本");
        assertEquals(1, s2.listVersions("heal-key2").size(), "补完+重试不得产生第二个版本");
        assertArrayEquals(content, s2.getObject("heal-key2", null));
        assertTrue(uploadDirEntries().isEmpty(), "已完成的会话不得遗留分片目录");
    }

    @Test
    void crashBeforeCommitIntent_retryAfterRestartProducesSingleVersion() throws IOException {
        // 崩溃点：内容已写入内容存储、提交意图落盘前。会话在盘上仍是 ACTIVE。
        CrashMetadataStore crashMs = new CrashMetadataStore(dataDir.resolve("metadata.json"));
        ObjectStorageService s = newService(new BlobStore(dataDir.resolve("blobs")), crashMs);
        byte[] content = bytes("crash-before-intent");
        String uploadId = uploadOnePart(s, "heal-key3", content);
        crashMs.failOnCommittingFlush = true;
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> s.completeUpload(uploadId, null));
        log.info("模拟崩溃: uploadId={} 崩溃点=提交意图落盘前 盘上blob数={}", uploadId, blobHashes().size());

        // 重启：会话仍是 ACTIVE，分片保留，客户端重试完成；已写入的内容被去重复用，不产生泄漏。
        ObjectStorageService s2 = restart();
        StoredVersion v = s2.completeUpload(uploadId, null);
        log.info("重启后重试成功: versionId={} hash={} 盘上blob数={}", v.versionId(), v.contentHash(), blobHashes().size());
        assertEquals(1, s2.listVersions("heal-key3").size(), "未生效的写入重试后只能产生一个版本");
        assertArrayEquals(content, s2.getObject("heal-key3", null));
        assertEquals(Set.of(v.contentHash()), blobHashes(), "崩溃前写入的内容必须被去重复用，不得产生残留");

        // 再次重试：幂等返回同一版本。
        StoredVersion again = s2.completeUpload(uploadId, null);
        assertEquals(v.versionId(), again.versionId());
    }

    @Test
    void repeatedCrashAtCommitBoundary_diskUsageStable() throws IOException {
        // 反复在崩溃边缘重启：每次都崩在 COMPLETED 落盘前，恢复补完，磁盘占用不得增长。
        byte[] content = bytes("same-content-every-round");
        int rounds = 5;
        String lastVersionId = null;
        for (int i = 0; i < rounds; i++) {
            CrashMetadataStore crashMs = new CrashMetadataStore(dataDir.resolve("metadata.json"));
            ObjectStorageService s = newService(new BlobStore(dataDir.resolve("blobs")), crashMs);
            String uploadId = uploadOnePart(s, "loop-key-" + i, content);
            crashMs.failOnCompletedFlush = true;
            org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> s.completeUpload(uploadId, null));
            ObjectStorageService s2 = restart();
            StoredVersion v = s2.completeUpload(uploadId, null);
            lastVersionId = v.versionId();
            log.info("第{}轮: 崩溃->重启->补完->重试 versionId={} blob数={} uploads残留={}",
                    i + 1, v.versionId(), blobHashes().size(), uploadDirEntries());
        }
        assertEquals(1, blobHashes().size(), "相同内容反复提交+崩溃，盘上只能有一份内容（去重）");
        assertTrue(uploadDirEntries().isEmpty(), "每轮恢复后不得遗留分片目录，磁盘占用不能无界增长");
        ObjectStorageService s3 = restart();
        assertArrayEquals(content, s3.getObject("loop-key-" + (rounds - 1), lastVersionId), "最后一轮版本必须可读");
    }

    @Test
    void gcInterruptedMidway_rerunAfterRestartConsistent() throws IOException {
        ObjectStorageService s = restart();
        StoredVersion a = putObj(s, "live", bytes("live-content"));
        StoredVersion b = putObj(s, "dead-1", bytes("dead-content-1"));
        StoredVersion c = putObj(s, "dead-2", bytes("dead-content-2"));
        // 让 b、c 的内容不再被任何版本引用：先删除（墓碑成为最新），再显式回收历史版本。
        s.deleteObject("dead-1", b.versionId());
        s.reclaimVersions("dead-1", List.of(b.versionId()));
        s.deleteObject("dead-2", c.versionId());
        s.reclaimVersions("dead-2", List.of(c.versionId()));
        log.info("GC前: 被引用={} 待回收=[{}, {}] 盘上={}", a.contentHash(), b.contentHash(), c.contentHash(), blobHashes());
        assertEquals(3, blobHashes().size());

        // 第一次 GC 执行到一半（删掉 1 个后）进程退出。
        ObjectStorageService flaky = newService(new FlakyBlobStore(dataDir.resolve("blobs"), 1),
                new MetadataStore(dataDir.resolve("metadata.json")));
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, flaky::garbageCollect);
        log.info("模拟崩溃: GC 删除 1 个后进程退出 剩余blob={}", blobHashes());

        // 重启后再跑一遍：结果必须与一次性跑完一致——只剩被引用的内容。
        ObjectStorageService s2 = restart();
        List<String> removed = s2.garbageCollect();
        log.info("重启后重跑GC: 本次回收={} 剩余blob={}", removed, blobHashes());
        assertEquals(Set.of(a.contentHash()), blobHashes(),
                "中断后重跑必须收敛到与一次性 GC 相同的结果（判定依据：只剩被引用内容）");
        assertArrayEquals(bytes("live-content"), s2.getObject("live", null),
                "被可读版本引用的内容绝不得被回收");
        assertArrayEquals(bytes("live-content"), s2.getObject("live", a.versionId()));

        // 再跑一遍：幂等，没有可回收内容。
        assertTrue(s2.garbageCollect().isEmpty(), "GC 必须幂等，重复执行不再有可回收内容");
    }

    @Test
    void storageStats_readOnlyViewDoesNotDeleteAnything() throws IOException {
        ObjectStorageService s = restart();
        byte[] live = bytes("stats-live-content");
        byte[] dead = bytes("stats-dead-content");
        StoredVersion a = putObj(s, "stats-live", live);
        StoredVersion b = putObj(s, "stats-dead", dead);
        s.deleteObject("stats-dead", b.versionId());
        s.reclaimVersions("stats-dead", List.of(b.versionId()));

        StorageStats stats = s.storageStats();
        log.info("容量视图: totalBlobs={} totalBytes={} referenced={}/{}B reclaimable={}/{}B",
                stats.totalBlobs(), stats.totalBytes(),
                stats.referencedBlobs(), stats.referencedBytes(),
                stats.reclaimableBlobs(), stats.reclaimableBytes());
        assertEquals(2, stats.totalBlobs());
        assertEquals(live.length + dead.length, stats.totalBytes());
        assertEquals(1, stats.referencedBlobs());
        assertEquals(live.length, stats.referencedBytes());
        assertEquals(1, stats.reclaimableBlobs(), "不再被任何版本引用的内容必须被识别为可安全释放");
        assertEquals(dead.length, stats.reclaimableBytes());

        // 只读性判定：调用前后元数据文件内容与磁盘内容清单完全一致。
        byte[] metadataBefore = Files.readAllBytes(dataDir.resolve("metadata.json"));
        Set<String> blobsBefore = blobHashes();
        s.storageStats();
        s.storageStats();
        assertArrayEquals(metadataBefore, Files.readAllBytes(dataDir.resolve("metadata.json")),
                "容量视图不得修改元数据");
        assertEquals(blobsBefore, blobHashes(), "容量视图不得删除任何内容");
        assertArrayEquals(live, s.getObject("stats-live", null), "容量视图调用后数据必须完好");

        // GC 之后容量视图应反映回收结果。
        s.garbageCollect();
        StorageStats after = s.storageStats();
        log.info("GC后容量视图: totalBlobs={} reclaimableBlobs={}", after.totalBlobs(), after.reclaimableBlobs());
        assertEquals(1, after.totalBlobs());
        assertEquals(0, after.reclaimableBlobs());
    }
}
