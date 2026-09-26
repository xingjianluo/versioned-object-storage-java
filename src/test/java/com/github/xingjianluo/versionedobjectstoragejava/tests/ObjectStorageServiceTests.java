package com.github.xingjianluo.versionedobjectstoragejava.tests;

import com.github.xingjianluo.versionedobjectstoragejava.api.ApiException;
import com.github.xingjianluo.versionedobjectstoragejava.api.ErrorCode;
import com.github.xingjianluo.versionedobjectstoragejava.storage.BlobStore;
import com.github.xingjianluo.versionedobjectstoragejava.storage.MetadataStore;
import com.github.xingjianluo.versionedobjectstoragejava.storage.ObjectStorageService;
import com.github.xingjianluo.versionedobjectstoragejava.storage.StoredVersion;
import com.github.xingjianluo.versionedobjectstoragejava.storage.UploadSessionState;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对象存储核心场景测试：并发写入、条件更新冲突、重复内容去重、
 * 上传中断恢复、非法分片。每个用例打印关键请求参数与判定依据。
 */
class ObjectStorageServiceTests {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageServiceTests.class);

    @TempDir
    Path dataDir;

    BlobStore blobStore;
    MetadataStore metadataStore;
    ObjectStorageService service;

    @BeforeEach
    void setUp() throws IOException {
        blobStore = new BlobStore(dataDir.resolve("blobs"));
        metadataStore = new MetadataStore(dataDir.resolve("metadata.json"));
        metadataStore.load();
        service = new ObjectStorageService(blobStore, metadataStore, dataDir.resolve("uploads"));
        service.recover();
    }

    /** 模拟进程重启：在同一数据目录上重建全部组件并执行恢复。 */
    private ObjectStorageService restart() throws IOException {
        BlobStore bs = new BlobStore(dataDir.resolve("blobs"));
        MetadataStore ms = new MetadataStore(dataDir.resolve("metadata.json"));
        ms.load();
        ObjectStorageService s = new ObjectStorageService(bs, ms, dataDir.resolve("uploads"));
        s.recover();
        this.blobStore = bs;
        this.metadataStore = ms;
        this.service = s;
        return s;
    }

    private StoredVersion putObj(String key, byte[] data, String expected) throws IOException {
        return service.putObject(key, new ByteArrayInputStream(data), expected);
    }

    private static StoredVersion putObjOn(ObjectStorageService s, String key, byte[] data, String expected) throws IOException {
        return s.putObject(key, new ByteArrayInputStream(data), expected);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] hash = d.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void concurrentConditionalWrites_exactlyOneWins() throws Exception {
        StoredVersion v1 = putObj("hot", bytes("v1"), null);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<StoredVersion> successes = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ApiException> conflicts = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    successes.add(putObj("hot", bytes("concurrent-" + idx), v1.versionId()));
                } catch (ApiException e) {
                    conflicts.add(e);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return null;
            }));
        }
        ready.await();
        go.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        log.info("并发条件写入: threads={} expectedVersion={} 成功={} 冲突={}",
                threads, v1.versionId(), successes.size(), conflicts.size());
        assertEquals(1, successes.size(), "并发下基于同一版本的条件更新只能有一个成功");
        assertEquals(threads - 1, conflicts.size());
        assertTrue(conflicts.stream().allMatch(e -> e.getCode() == ErrorCode.VERSION_CONFLICT),
                "所有失败原因必须可区分为 VERSION_CONFLICT");
        List<StoredVersion> versions = service.listVersions("hot");
        assertEquals(2, versions.size(), "最终只能新增一个版本");
        StoredVersion winner = successes.peek();
        assertEquals(winner.versionId(), versions.get(1).versionId(), "可见最新版本必须是唯一成功者");
        assertEquals(winner.contentHash(), sha256(service.getObject("hot", null)),
                "最终内容必须与唯一成功写入一致（判定依据：内容哈希相等）");
    }

    @Test
    void concurrentUnconditionalWrites_allVersionsVisible() throws Exception {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<StoredVersion>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> putObj("multi", bytes("w" + idx), null)));
        }
        List<String> versionIds = new ArrayList<>();
        for (Future<StoredVersion> f : futures) {
            versionIds.add(f.get().versionId());
        }
        pool.shutdown();
        List<StoredVersion> versions = service.listVersions("multi");
        log.info("并发无条件写入: threads={} 产生版本数={} 版本ID唯一数={}",
                threads, versions.size(), versionIds.stream().distinct().count());
        assertEquals(threads, versions.size(), "每次写入都必须产生可查询的新版本");
        assertEquals(threads, versionIds.stream().distinct().count());
        for (String vid : versionIds) {
            StoredVersion v = versions.stream().filter(x -> x.versionId().equals(vid)).findFirst().orElseThrow();
            assertArrayEquals(service.getObject("multi", vid), blobStore.get(v.contentHash()),
                    "每个历史版本必须可读且内容一致");
        }
    }

    @Test
    void conditionalUpdate_conflictRejectedAndStateUnchanged() throws IOException {
        StoredVersion v1 = putObj("doc", bytes("first"), null);
        log.info("条件更新冲突用例: key=doc latestVersion={} 请求携带 expectedVersion=stale", v1.versionId());
        ApiException e = assertThrows(ApiException.class,
                () -> putObj("doc", bytes("second"), "stale-version-id"));
        log.info("判定依据: errorCode={} message={}", e.getCode(), e.getMessage());
        assertEquals(ErrorCode.VERSION_CONFLICT, e.getCode());
        assertEquals(1, service.listVersions("doc").size(), "冲突写入不得产生新版本");
        assertArrayEquals(bytes("first"), service.getObject("doc", null), "冲突写入不得静默覆盖");

        // 携带正确版本标识则成功。
        StoredVersion v2 = putObj("doc", bytes("second"), v1.versionId());
        assertNotEquals(v1.versionId(), v2.versionId());
        assertArrayEquals(bytes("second"), service.getObject("doc", null));
        assertArrayEquals(bytes("first"), service.getObject("doc", v1.versionId()), "历史版本保持可读");
    }

    @Test
    void duplicateContent_dedupAndGc() throws IOException {
        byte[] content = bytes("identical-content");
        StoredVersion a1 = putObj("a", content, null);
        StoredVersion b1 = putObj("b", content, null);
        log.info("重复内容去重: keyA={} hashA={} keyB={} hashB={} 磁盘副本数={}",
                "a", a1.contentHash(), "b", b1.contentHash(), blobStore.listAll().size());
        assertEquals(a1.contentHash(), b1.contentHash(), "相同内容哈希必须一致");
        assertEquals(1, blobStore.listAll().size(), "相同内容不得留下额外持久化副本");

        // 删除 a 后内容仍被 b 引用，GC 不得回收；全部删除后历史版本仍引用内容，GC 同样不得回收。
        service.deleteObject("a", a1.versionId());
        assertTrue(service.garbageCollect().isEmpty(), "仍被引用的内容不得回收");
        assertArrayEquals(content, service.getObject("b", null));
        service.deleteObject("b", b1.versionId());
        assertTrue(service.garbageCollect().isEmpty(), "未显式回收的历史版本仍引用内容，GC 不得回收");
        assertArrayEquals(content, service.getObject("a", a1.versionId()), "未显式回收的历史版本必须可读");

        // 显式回收历史版本后，GC 才能清除内容。
        service.reclaimVersions("a", List.of(a1.versionId()));
        service.reclaimVersions("b", List.of(b1.versionId()));
        List<String> removed = service.garbageCollect();
        log.info("GC 判定: 显式回收历史版本后引用数=0 回收={}", removed);
        assertEquals(List.of(a1.contentHash()), removed);
        assertTrue(blobStore.listAll().isEmpty());
        // 已回收的版本不再可读；墓碑语义保持不变。
        ApiException gone = assertThrows(ApiException.class, () -> service.getObject("a", a1.versionId()));
        assertEquals(ErrorCode.VERSION_NOT_FOUND, gone.getCode(), "已显式回收的版本按版本不存在处理");
        ApiException e = assertThrows(ApiException.class, () -> service.getObject("a", null));
        assertEquals(ErrorCode.OBJECT_NOT_FOUND, e.getCode());
    }

    @Test
    void deleteThenRewrite_historyPreserved() throws IOException {
        StoredVersion v1 = putObj("k", bytes("old"), null);
        StoredVersion tomb = service.deleteObject("k", v1.versionId());
        StoredVersion v3 = putObj("k", bytes("new"), null);
        List<StoredVersion> versions = service.listVersions("k");
        log.info("删除后重写: versions={} (v1={}, tomb={}, v3={})",
                versions.size(), v1.versionId(), tomb.versionId(), v3.versionId());
        assertEquals(3, versions.size(), "删除后重新写入不得覆盖历史版本");
        assertTrue(versions.get(1).deleted());
        assertArrayEquals(bytes("old"), service.getObject("k", v1.versionId()), "未回收的历史版本必须可读");
        assertArrayEquals(bytes("new"), service.getObject("k", null));
        ApiException e = assertThrows(ApiException.class, () -> service.getObject("k", tomb.versionId()));
        assertEquals(ErrorCode.OBJECT_NOT_FOUND, e.getCode(), "读取墓碑版本按对象不存在处理");
    }

    @Test
    void multipartUpload_outOfOrderAndIdempotentParts() throws IOException {
        UploadSessionState session = service.createUpload("big");
        String uploadId = session.getUploadId();
        byte[] p1 = bytes("part-1-");
        byte[] p2 = bytes("part-2-");
        byte[] p3 = bytes("part-3");
        // 乱序提交。
        service.uploadPart(uploadId, 2, new ByteArrayInputStream(p2), sha256(p2));
        service.uploadPart(uploadId, 3, new ByteArrayInputStream(p3), sha256(p3));
        service.uploadPart(uploadId, 1, new ByteArrayInputStream(p1), sha256(p1));
        // 重复提交相同分片：幂等，不得产生重复数据。
        service.uploadPart(uploadId, 2, new ByteArrayInputStream(p2), sha256(p2));
        long partFiles = Files.list(dataDir.resolve("uploads").resolve(uploadId)).count();
        log.info("分片幂等: uploadId={} 重复提交part2后分片文件数={}", uploadId, partFiles);
        assertEquals(3, partFiles, "重复提交相同分片不得产生重复数据");

        StoredVersion v = service.completeUpload(uploadId, null);
        byte[] expected = bytes("part-1-part-2-part-3");
        log.info("乱序合并: versionId={} 期望大小={} 实际大小={}", v.versionId(), expected.length, v.size());
        assertArrayEquals(expected, service.getObject("big", null), "乱序分片必须按分片号正确合并");
        assertEquals(sha256(expected), v.contentHash());
    }

    @Test
    void multipartUpload_invalidRequests_distinguishableFailures() throws IOException {
        UploadSessionState session = service.createUpload("obj");
        String uploadId = session.getUploadId();
        byte[] p1 = bytes("data-1");

        // 1. 校验和不匹配。
        ApiException checksumErr = assertThrows(ApiException.class,
                () -> service.uploadPart(uploadId, 1, new ByteArrayInputStream(p1), "deadbeef"));
        log.info("非法分片-校验和: part=1 声明=deadbeef 实际={} -> {}", sha256(p1), checksumErr.getCode());
        assertEquals(ErrorCode.CHECKSUM_MISMATCH, checksumErr.getCode());

        // 2. 非法分片号。
        ApiException invalidPart = assertThrows(ApiException.class,
                () -> service.uploadPart(uploadId, 0, new ByteArrayInputStream(p1), sha256(p1)));
        log.info("非法分片-分片号: part=0 -> {}", invalidPart.getCode());
        assertEquals(ErrorCode.INVALID_PART, invalidPart.getCode());

        // 3. 分片缺失时完成上传。
        service.uploadPart(uploadId, 1, new ByteArrayInputStream(p1), sha256(p1));
        service.uploadPart(uploadId, 3, new ByteArrayInputStream(bytes("data-3")), sha256(bytes("data-3")));
        ApiException missing = assertThrows(ApiException.class, () -> service.completeUpload(uploadId, null));
        log.info("非法分片-缺失: 已传=[1,3] -> {}", missing.getCode());
        assertEquals(ErrorCode.MISSING_PARTS, missing.getCode());
        assertTrue(service.listVersions("obj").isEmpty(), "失败完成不得产生可见版本");

        // 4. 会话不存在。
        ApiException notFound = assertThrows(ApiException.class,
                () -> service.uploadPart("no-such-id", 1, new ByteArrayInputStream(p1), sha256(p1)));
        log.info("非法分片-会话不存在: -> {}", notFound.getCode());
        assertEquals(ErrorCode.SESSION_NOT_FOUND, notFound.getCode());

        // 5. 中止后会话失效。
        service.abortUpload(uploadId);
        ApiException expired = assertThrows(ApiException.class,
                () -> service.uploadPart(uploadId, 2, new ByteArrayInputStream(bytes("x")), sha256(bytes("x"))));
        log.info("非法分片-会话失效: 中止后上传 -> {}", expired.getCode());
        assertEquals(ErrorCode.SESSION_EXPIRED, expired.getCode());
        ApiException expiredComplete = assertThrows(ApiException.class, () -> service.completeUpload(uploadId, null));
        assertEquals(ErrorCode.SESSION_EXPIRED, expiredComplete.getCode());
    }

    @Test
    void crashDuringUpload_restartAndResume() throws IOException {
        UploadSessionState session = service.createUpload("resumable");
        String uploadId = session.getUploadId();
        byte[] p1 = bytes("chunk-A-");
        byte[] p2 = bytes("chunk-B");
        service.uploadPart(uploadId, 1, new ByteArrayInputStream(p1), sha256(p1));

        // 模拟进程在写入中途退出：遗留 blob 临时文件与半个分片临时文件。
        Files.createDirectories(dataDir.resolve("blobs"));
        Files.writeString(dataDir.resolve("blobs").resolve("tmp-crashed"), "partial");
        Files.writeString(dataDir.resolve("uploads").resolve(uploadId).resolve("part-2.tmp"), "partial");
        log.info("模拟崩溃: uploadId={} 已持久化分片=[1] 遗留临时文件=2", uploadId);

        // 重启恢复。
        ObjectStorageService s2 = restart();
        assertFalse(Files.exists(dataDir.resolve("blobs").resolve("tmp-crashed")), "崩溃遗留的内容临时文件必须被清理");

        // 会话仍然有效，可续传剩余分片并完成。
        s2.uploadPart(uploadId, 2, new ByteArrayInputStream(p2), sha256(p2));
        StoredVersion v = s2.completeUpload(uploadId, null);
        byte[] expected = bytes("chunk-A-chunk-B");
        log.info("中断恢复: 续传part2后完成 versionId={} 内容匹配={}",
                v.versionId(), java.util.Arrays.equals(expected, s2.getObject("resumable", null)));
        assertArrayEquals(expected, s2.getObject("resumable", null));
        // 完成的会话目录已清理，再次重启不得遗留临时数据。
        restart();
        assertFalse(Files.exists(dataDir.resolve("uploads").resolve(uploadId)), "已完成会话不得遗留分片数据");
    }

    @Test
    void crashLeavesOrphanUploadDir_recoverCleansUp() throws IOException {
        // 模拟会话元数据落盘前进程退出：磁盘上留下孤儿分片目录。
        Path orphan = dataDir.resolve("uploads").resolve("orphan-session");
        Files.createDirectories(orphan);
        Files.writeString(orphan.resolve("part-1"), "garbage");
        log.info("孤儿分片目录: {}", orphan);
        restart();
        assertFalse(Files.exists(orphan), "元数据中无会话记录的孤儿分片目录必须被回收");
        assertTrue(metadataStore.allSessions().isEmpty());
    }

    @Test
    void restartConsistency_versionsAndContentIntact() throws IOException {
        StoredVersion v1 = putObj("persist", bytes("gen1"), null);
        StoredVersion v2 = putObj("persist", bytes("gen2"), v1.versionId());
        ObjectStorageService s2 = restart();
        List<StoredVersion> versions = s2.listVersions("persist");
        log.info("重启一致性: 版本数={} 最新={}", versions.size(), versions.get(versions.size() - 1).versionId());
        assertEquals(2, versions.size());
        assertArrayEquals(bytes("gen1"), s2.getObject("persist", v1.versionId()));
        assertArrayEquals(bytes("gen2"), s2.getObject("persist", v2.versionId()));
        assertEquals(v2.versionId(), versions.get(1).versionId());
    }
}
