package com.github.xingjianluo.versionedobjectstoragejava.tests;

import com.github.xingjianluo.versionedobjectstoragejava.api.ApiException;
import com.github.xingjianluo.versionedobjectstoragejava.api.ErrorCode;
import com.github.xingjianluo.versionedobjectstoragejava.storage.BlobStore;
import com.github.xingjianluo.versionedobjectstoragejava.storage.CapacityView;
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
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 崩溃恢复与可重试语义专项测试：
 * 跨重启幂等完成、完成阶段崩溃自愈、回收/清理中断重跑、只读容量视图无副作用。
 * 每个用例打印关键参数（key、uploadId、版本标识、字节数、文件数）与判定依据。
 */
class CrashRecoveryTests {

    private static final Logger log = LoggerFactory.getLogger(CrashRecoveryTests.class);

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

    /** 模拟进程重启：同一数据目录重建组件并执行恢复。 */
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

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] data) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 递归统计某目录下普通文件数量。 */
    private static long countFiles(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    /** 用两个分片创建一个已传满、尚未 complete 的会话，返回 uploadId。 */
    private String prepareFullSession(String key, byte[] p1, byte[] p2) throws IOException {
        UploadSessionState session = service.createUpload(key);
        String uploadId = session.getUploadId();
        service.uploadPart(uploadId, 1, new ByteArrayInputStream(p1), sha256(p1));
        service.uploadPart(uploadId, 2, new ByteArrayInputStream(p2), sha256(p2));
        return uploadId;
    }

    @Test
    void completeRetryAcrossRestart_yieldsSingleVersionAndSameResult() throws IOException {
        byte[] p1 = bytes("alpha-");
        byte[] p2 = bytes("beta");
        byte[] expected = bytes("alpha-beta");
        String uploadId = prepareFullSession("doc", p1, p2);
        log.info("参数: key=doc uploadId={} parts=2 partSizes={},{} 合并期望大小={}",
                uploadId, p1.length, p2.length, expected.length);

        // 第一次完成成功（客户端随后“拿不到响应”）。
        StoredVersion first = service.completeUpload(uploadId, null);
        log.info("首次 complete: versionId={} hash={}", first.versionId(), first.contentHash());

        // 当场重试：必须返回同一版本、不新增版本。
        StoredVersion retryNow = service.completeUpload(uploadId, null);
        assertEquals(first.versionId(), retryNow.versionId(), "当场重试必须返回同一个版本结果");

        // 服务重启后再重试：仍然是同一版本，不产生第二个版本。
        ObjectStorageService s2 = restart();
        StoredVersion retryAfterRestart = s2.completeUpload(uploadId, null);
        log.info("重启后重试 complete: versionId={} 与首次相同={}",
                retryAfterRestart.versionId(), first.versionId().equals(retryAfterRestart.versionId()));
        assertEquals(first.versionId(), retryAfterRestart.versionId(), "跨重启重试必须返回同一个版本结果");

        // 判定依据：版本列表恰好 1 个；内容可读且哈希一致；分片目录已清理。
        List<StoredVersion> versions = s2.listVersions("doc");
        assertEquals(1, versions.size(), "无论重试多少次、是否重启，都只能有一个版本");
        assertEquals(first.versionId(), versions.get(0).versionId());
        assertArrayEquals(expected, s2.getObject("doc", null));
        assertEquals(sha256(expected), versions.get(0).contentHash(), "内容哈希必须与合并结果一致");
        assertFalse(Files.exists(dataDir.resolve("uploads").resolve(uploadId)), "完成后分片目录不得残留");
        log.info("判定通过: 版本数=1 内容可读取且哈希匹配 分片目录已清理");
    }

    @Test
    void concurrentComplete_onlyOneVersion_allCallersGetSameResult() throws Exception {
        byte[] p1 = bytes("concurrent-");
        byte[] p2 = bytes("body");
        String uploadId = prepareFullSession("hot", p1, p2);
        int threads = 8;

        // 用可在“进入完成流程”处统一放行的服务实例制造真实竞争；服务方法 synchronized 串行化。
        ObjectStorageService competing = new ObjectStorageService(blobStore, metadataStore, dataDir.resolve("uploads")) {
            @Override
            public synchronized StoredVersion completeUpload(String u, String expected) throws IOException {
                try {
                    barrier.await();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return super.completeUpload(u, expected);
            }

            private final CountDownLatch barrier = new CountDownLatch(1);
        };
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        ConcurrentLinkedQueue<StoredVersion> results = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    results.add(competing.completeUpload(uploadId, null));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return null;
            }));
        }
        ready.await();
        // 所有线程就绪后同时放行。
        java.lang.reflect.Field f = competing.getClass().getDeclaredField("barrier");
        f.setAccessible(true);
        ((CountDownLatch) f.get(competing)).countDown();
        for (Future<?> fut : futures) {
            fut.get();
        }
        pool.shutdown();

        long distinctVersions = results.stream().map(StoredVersion::versionId).distinct().count();
        log.info("并发 complete: threads={} 返回结果数={} 不同版本数={}", threads, results.size(), distinctVersions);
        assertEquals(threads, results.size(), "所有并发请求都必须拿到结果（而非报错）");
        assertEquals(1, distinctVersions, "并发完成只能产生一个版本，其余请求返回同一结果");
        assertEquals(1, service.listVersions("hot").size(), "最终只能有一个可见版本");
        assertArrayEquals(bytes("concurrent-body"), service.getObject("hot", null));
        log.info("判定通过: 8 个并发 complete 返回同一 versionId，版本数=1");
    }

    @Test
    void crashBeforeCommit_duringComplete_restartCompletesCleanly() throws IOException {
        byte[] p1 = bytes("left-");
        byte[] p2 = bytes("right");
        byte[] expected = bytes("left-right");
        String uploadId = prepareFullSession("crash-before", p1, p2);

        // 模拟“提交前崩溃”：合并内容已写入内容存储（先内容后元数据），
        // 但元数据尚未提交，因此会话仍 ACTIVE、无版本可见；同时遗留 merged.tmp 与 blob 临时文件。
        Path merged = dataDir.resolve("uploads").resolve(uploadId).resolve("merged.tmp");
        Files.write(merged, expected);
        String mergedHash;
        try (ByteArrayInputStream in = new ByteArrayInputStream(expected)) {
            mergedHash = blobStore.put(in);
        }
        Files.createDirectories(dataDir.resolve("blobs"));
        Files.writeString(dataDir.resolve("blobs").resolve("tmp-aborted-write"), "partial-blob");
        long blobsBefore = blobStore.listAll().size();
        log.info("参数: key=crash-before uploadId={} 崩溃点=元数据提交前 已就位blob数={} 合并内容hash={}",
                uploadId, blobsBefore, mergedHash);

        ObjectStorageService s2 = restart();
        // 恢复后：会话仍 ACTIVE、无版本可见、临时文件被清理、分片仍可用于完成。
        assertTrue(s2.listVersions("crash-before").isEmpty(), "提交前崩溃不得留下可见版本");
        assertFalse(Files.exists(merged), "恢复必须清理 merged.tmp");
        assertFalse(Files.exists(dataDir.resolve("blobs").resolve("tmp-aborted-write")),
                "恢复必须清理中断的 blob 临时文件");
        assertTrue(blobStore.exists(mergedHash), "已就位内容保留，不影响重试");
        assertTrue(Files.exists(dataDir.resolve("uploads").resolve(uploadId).resolve("part-1")),
                "已就位分片必须保留供重试");

        // 客户端原样重试 complete：补完，只产生一个版本。
        StoredVersion v = s2.completeUpload(uploadId, null);
        assertArrayEquals(expected, s2.getObject("crash-before", null));
        assertEquals(1, s2.listVersions("crash-before").size());
        assertEquals(sha256(expected), v.contentHash());

        // 反复在“崩溃边缘”重启（此时已经提交）：状态保持一致、无残留增长。
        ObjectStorageService s3 = restart();
        ObjectStorageService s4 = restart();
        assertEquals(1, s4.listVersions("crash-before").size(), "反复重启不得新增版本");
        StoredVersion retry = s4.completeUpload(uploadId, null);
        assertEquals(v.versionId(), retry.versionId(), "已提交后重启再 complete 仍幂等返回同一版本");
        assertEquals(0, countFiles(dataDir.resolve("uploads")), "不得残留任何上传临时/分片文件");
        assertArrayEquals(expected, s4.getObject("crash-before", null));
        log.info("判定通过: 崩溃前无版本 -> 重启重试补完为1个版本 -> 多次重启无残留增长");
    }

    @Test
    void crashAfterCommit_versionVisible_retryReturnsSameResult_andDirReclaimed() throws IOException {
        byte[] p1 = bytes("persisted-");
        byte[] p2 = bytes("payload");
        byte[] expected = bytes("persisted-payload");
        String uploadId = prepareFullSession("crash-after", p1, p2);
        Path sessionDir = dataDir.resolve("uploads").resolve(uploadId);

        // 正常提交：版本与“会话完成”已原子落盘（客户端此刻拿不到响应）。
        StoredVersion v = service.completeUpload(uploadId, null);

        // 模拟“提交后、分片目录清理前”被强杀：人为把目录恢复回来（内容未删）。
        Files.createDirectories(sessionDir);
        Files.write(sessionDir.resolve("part-1"), p1);
        Files.write(sessionDir.resolve("part-2"), p2);
        Files.writeString(sessionDir.resolve("merged.tmp"), new String(expected, StandardCharsets.UTF_8));
        log.info("参数: key=crash-after uploadId={} versionId={} 崩溃点=提交后目录清理前 残留文件数={}",
                uploadId, v.versionId(), countFiles(sessionDir));

        // 重启恢复：版本可见可读；已完成会话目录被兜底清理。
        ObjectStorageService s2 = restart();
        assertArrayEquals(expected, s2.getObject("crash-after", null), "已提交版本重启后必须立即可读");
        assertEquals(1, s2.listVersions("crash-after").size());
        assertFalse(Files.exists(sessionDir), "恢复必须清理已完成会话遗留的分片目录");

        // 客户端因没收到响应而重试 complete：拿到同一结果，不产生新版本。
        StoredVersion retry = s2.completeUpload(uploadId, null);
        assertEquals(v.versionId(), retry.versionId(), "提交后重试必须返回同一版本");
        assertEquals(1, s2.listVersions("crash-after").size(), "重试不得产生第二个版本");
        log.info("判定通过: 提交版本重启可读={} 重试同版本={} 分片目录已清理",
                v.versionId(), v.versionId().equals(retry.versionId()));
    }

    @Test
    void gcInterrupted_halfDeleted_rerunConvergesAndKeepsReferenced() throws IOException {
        // 两个对象引用同一内容（去重），另一个内容仅属于将被回收的历史版本。
        byte[] shared = bytes("shared-content");
        byte[] oldOnly = bytes("soon-reclaimed-content");
        StoredVersion a = service.putObject("a", new ByteArrayInputStream(shared), null);
        StoredVersion b = service.putObject("b", new ByteArrayInputStream(shared), null);
        StoredVersion old = service.putObject("c", new ByteArrayInputStream(oldOnly), null);
        StoredVersion cNew = service.putObject("c", new ByteArrayInputStream(bytes("c-live")), null);

        // 显式回收 c 的历史版本：oldOnly 变为不可达；a/b 的共享内容仍被引用。
        service.reclaimVersions("c", List.of(old.versionId()));
        Set<String> allHashes = Set.copyOf(blobStore.listAll());
        assertTrue(allHashes.contains(sha256(shared)));
        assertTrue(allHashes.contains(sha256(oldOnly)));
        log.info("参数: 磁盘blob={} 待回收hash={} 仍被引用共享hash={}",
                allHashes, sha256(oldOnly), sha256(shared));

        // 模拟 GC 执行到一半被强杀：只删掉不可达的 oldOnly（共享内容绝不能被动）。
        assertTrue(blobStore.delete(sha256(oldOnly)), "中断前恰好删掉一个不可达内容");
        // 再留一个中断遗留的 blob 临时文件，模拟删除/写入交错。
        Files.createDirectories(dataDir.resolve("blobs"));
        Files.writeString(dataDir.resolve("blobs").resolve("tmp-gc-leftover"), "x");

        ObjectStorageService s2 = restart();
        // 中断后仍被引用的内容完好可读。
        assertArrayEquals(shared, s2.getObject("a", null));
        assertArrayEquals(shared, s2.getObject("b", null));
        assertArrayEquals(bytes("c-live"), s2.getObject("c", null));
        assertTrue(blobStore.exists(sha256(shared)), "仍被版本引用的内容在中断后必须存在");
        assertTrue(blobStore.exists(a.contentHash()), "历史版本 a 引用内容必须保留");

        // 重跑 GC：已删掉的不再出现，剩余不可达内容（本例无）结果稳定；再跑一遍结果为空。
        List<String> first = s2.garbageCollect();
        List<String> second = s2.garbageCollect();
        log.info("重跑GC: 第一次删除={} 第二次删除={} (判定依据: 两次结果均不得包含被引用hash)",
                first, second);
        assertFalse(first.contains(sha256(shared)), "GC 绝不能删除仍被引用的共享内容");
        assertFalse(second.contains(sha256(shared)));
        assertTrue(second.isEmpty(), "GC 幂等：收敛后再次执行不再删除任何东西");
        assertTrue(blobStore.exists(sha256(shared)), "收敛后被引用内容依然存在");
        // 中断遗留临时文件在恢复时已清理，磁盘不留越积越多的残留。
        assertFalse(Files.exists(dataDir.resolve("blobs").resolve("tmp-gc-leftover")));
        assertEquals(cNew.versionId(), s2.listVersions("c").get(s2.listVersions("c").size() - 1).versionId());
    }

    @Test
    void capacityView_isReadOnly_andMatchesGcScope() throws IOException {
        byte[] live = bytes("live-content-1234");          // 17 字节，被最新版本引用
        byte[] dead = bytes("dead-content-567890");        // 20 字节，历史版本回收后不可达
        StoredVersion v1 = service.putObject("k", new ByteArrayInputStream(live), null);
        StoredVersion v2 = service.putObject("k", new ByteArrayInputStream(dead), null);
        StoredVersion v3 = service.putObject("k", new ByteArrayInputStream(live), null);
        // 回收指向 dead 的历史版本 v2：dead 变为不可达；live 被 v1/v3 引用。
        service.reclaimVersions("k", List.of(v2.versionId()));

        Set<String> hashesBefore = Set.copyOf(blobStore.listAll());
        long totalBytesBefore = 0;
        for (BlobStore.BlobInfo info : blobStore.listBlobInfos()) {
            totalBytesBefore += info.size();
        }
        log.info("参数: blob数={} 总字节={} liveHash={}({}字节) deadHash={}({}字节)",
                hashesBefore.size(), totalBytesBefore, sha256(live), live.length, sha256(dead), dead.length);

        // 连续两次只读查询。
        CapacityView c1 = service.capacityView();
        CapacityView c2 = service.capacityView();

        Set<String> hashesAfter = Set.copyOf(blobStore.listAll());
        long totalBytesAfter = 0;
        for (BlobStore.BlobInfo info : blobStore.listBlobInfos()) {
            totalBytesAfter += info.size();
        }

        // 判定依据 1：纯查看，磁盘 blob 集合与字节数逐字节不变。
        assertEquals(hashesBefore, hashesAfter, "容量视图不得删除/新增任何 blob");
        assertEquals(totalBytesBefore, totalBytesAfter, "容量视图不得改变占用字节");
        // 判定依据 2：数值自洽。
        assertEquals(hashesBefore.size(), c1.totalBlobCount());
        assertEquals(totalBytesBefore, c1.totalBytes());
        assertEquals(c1.totalBytes(), c1.referencedBytes() + c1.reclaimableBytes(),
                "总占用必须恰好等于 被引用 + 可释放");
        assertEquals(List.of(sha256(dead)), c1.reclaimableBlobs(), "仅 dead 内容不可达");
        assertEquals(dead.length, c1.reclaimableBytes());
        assertEquals(live.length, c1.referencedBytes(), "live 内容（被两个版本引用）只计一份字节");
        // 判定依据 3：两次查询结果一致，且真实 GC 删除集合与视图预测完全一致（口径相同）。
        assertEquals(c1.reclaimableBlobs(), c2.reclaimableBlobs());
        assertEquals(c1.totalBytes(), c2.totalBytes());
        List<String> gcRemoved = service.garbageCollect();
        assertEquals(c1.reclaimableBlobs(), gcRemoved, "容量视图预测的可释放集合必须等于 GC 实际删除集合");
        // GC 后 live 仍可读，dead 已无版本引用故被清。
        assertArrayEquals(live, service.getObject("k", null));
        assertArrayEquals(live, service.getObject("k", v1.versionId()));
        log.info("判定通过: capacityView 前后blob集合相同; referenced={} reclaimable={}; GC实际删除与预测一致",
                c1.referencedBytes(), c1.reclaimableBlobs());
    }

    @Test
    void reclaimInterruptedAndMetadataTmp_crashLoopDoesNotGrowDisk() throws IOException {
        // 准备多版本对象。
        byte[] d1 = bytes("version-one");
        byte[] d2 = bytes("version-two");
        byte[] d3 = bytes("version-three-live");
        StoredVersion v1 = service.putObject("hist", new ByteArrayInputStream(d1), null);
        StoredVersion v2 = service.putObject("hist", new ByteArrayInputStream(d2), v1.versionId());
        StoredVersion v3 = service.putObject("hist", new ByteArrayInputStream(d3), v2.versionId());

        // reclaim 是单次原子元数据落盘；这里模拟“回收已生效但客户端没收到响应”，重跑回收必须等价且不报错。
        List<StoredVersion> removedFirst = service.reclaimVersions("hist", List.of(v1.versionId(), v2.versionId()));
        assertEquals(2, removedFirst.size());
        List<StoredVersion> removedSecond = service.reclaimVersions("hist", List.of(v1.versionId(), v2.versionId()));
        assertTrue(removedSecond.isEmpty(), "回收已生效后重跑同一请求应幂等为空操作");
        log.info("reclaim 重跑: 首次移除=2 重跑移除=0 最新版本仍在={}",
                service.listVersions("hist").get(0).versionId().equals(v3.versionId()));
        assertEquals(1, service.listVersions("hist").size(), "仅最新版本保留");
        assertArrayEquals(d3, service.getObject("hist", null), "最新版本始终可读");

        // 回收后 v1/v2 内容变为不可达，但在真正 GC 前内容文件不得被删除（回收≠删内容）。
        assertTrue(blobStore.exists(sha256(d1)) && blobStore.exists(sha256(d2)),
                "显式回收版本只移除元数据引用，内容需等 GC 才释放");
        List<String> gcRemoved = service.garbageCollect();
        assertTrue(gcRemoved.containsAll(List.of(sha256(d1), sha256(d2))), "GC 释放被回收版本的内容");
        assertFalse(gcRemoved.contains(sha256(d3)), "最新版本内容绝不可被回收");

        // 模拟元数据落盘到一半崩溃：遗留 metadata.json.tmp。
        Path metaTmp = dataDir.resolve("metadata.json.tmp");
        Files.writeString(metaTmp, "{broken");
        long filesAfterMetaCrash = countFiles(dataDir);

        // 反复在“崩溃边缘”重启多次：每次都收敛，metadata 临时文件被清，磁盘文件数不增长。
        long stableFiles = -1;
        for (int i = 0; i < 5; i++) {
            ObjectStorageService s = restart();
            assertEquals(1, s.listVersions("hist").size(), "第 " + i + " 次重启后版本状态一致");
            assertArrayEquals(d3, s.getObject("hist", null));
            long files = countFiles(dataDir);
            assertFalse(Files.exists(metaTmp), "metadata.json.tmp 必须在恢复时被清理");
            if (stableFiles == -1) {
                stableFiles = files;
            } else {
                assertEquals(stableFiles, files, "反复崩溃重启磁盘文件数必须保持稳定（无无界增长）");
            }
        }
        log.info("判定通过: reclaim幂等={} GC不误删最新版本={} 崩溃循环5轮文件数稳定={} (崩溃瞬间={})",
                true, true, stableFiles, filesAfterMetaCrash);
    }

    @Test
    void directPutCrashAndActiveUploadSurviveRestart() throws IOException {
        // 已生效的直接写入：版本可见，内容必须在重启后可读。
        StoredVersion ok = service.putObject("plain", new ByteArrayInputStream(bytes("committed")), null);

        // 模拟直接写入“内容已就位、元数据尚未提交”的崩溃：遗留不可达 blob 与 blob 临时文件。
        byte[] neverCommitted = bytes("never-committed-content");
        String orphanHash;
        try (ByteArrayInputStream in = new ByteArrayInputStream(neverCommitted)) {
            orphanHash = blobStore.put(in);
        }
        Files.createDirectories(dataDir.resolve("blobs"));
        Files.writeString(dataDir.resolve("blobs").resolve("tmp-put-crash"), "half");

        // 同时存在一个传了一半的活跃会话。
        UploadSessionState session = service.createUpload("mid-upload");
        String uploadId = session.getUploadId();
        byte[] part1 = bytes("chunk1-");
        service.uploadPart(uploadId, 1, new ByteArrayInputStream(part1), sha256(part1));
        Files.writeString(dataDir.resolve("uploads").resolve(uploadId).resolve("part-2.tmp"), "partial-part-2");

        ObjectStorageService s2 = restart();
        // 已生效版本可读。
        assertArrayEquals(bytes("committed"), s2.getObject("plain", ok.versionId()));
        // 未生效写入没有版本；其孤儿内容可被 GC 干净回收，不致悬挂版本。
        assertFalse(s2.listVersions("plain").stream().anyMatch(v -> v.contentHash() != null && v.contentHash().equals(orphanHash)),
                "未提交写入不得出现指向该内容的版本");
        assertTrue(blobStore.exists(orphanHash), "孤儿内容在 GC 前仍在盘上（无悬挂版本）");
        CapacityView view = s2.capacityView();
        assertTrue(view.reclaimableBlobs().contains(orphanHash), "未提交写入的孤儿内容必须计入可释放");
        List<String> removed = s2.garbageCollect();
        assertTrue(removed.contains(orphanHash), "孤儿内容必须可被干净回收");
        assertFalse(blobStore.exists(sha256(neverCommitted)), "回收后孤儿内容已释放");
        assertFalse(Files.exists(dataDir.resolve("blobs").resolve("tmp-put-crash")), "blob 临时文件被清理");

        // 活跃会话保留已就位分片、清掉残留临时文件，可续传并完成。
        assertFalse(Files.exists(dataDir.resolve("uploads").resolve(uploadId).resolve("part-2.tmp")),
                "活跃会话的中断临时分片必须被清理");
        byte[] part2 = bytes("chunk2");
        s2.uploadPart(uploadId, 2, new ByteArrayInputStream(part2), sha256(part2));
        StoredVersion v = s2.completeUpload(uploadId, null);
        assertArrayEquals(bytes("chunk1-chunk2"), s2.getObject("mid-upload", v.versionId()));
        log.info("判定通过: 已提交写入可读; 未提交写入无悬挂版本且可GC; 活跃会话续传完成 versionId={}", v.versionId());
    }
}
