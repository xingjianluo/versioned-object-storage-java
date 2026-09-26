package com.github.xingjianluo.versionedobjectstoragejava.tests;

import com.github.xingjianluo.versionedobjectstoragejava.error.ErrorCode;
import com.github.xingjianluo.versionedobjectstoragejava.error.StorageException;
import com.github.xingjianluo.versionedobjectstoragejava.model.ObjectMeta;
import com.github.xingjianluo.versionedobjectstoragejava.model.PartResult;
import com.github.xingjianluo.versionedobjectstoragejava.model.UploadSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发场景：无条件写全部串行成功；同号分片并发提交相同内容只有一份数据。
 */
class ConcurrencyTest extends StorageTestSupport {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    @BeforeEach
    void setUp() throws Exception {
        setUpStorage(60_000);
    }

    @Test
    @DisplayName("并发无条件写入同一键：全部成功且版本数等于写入次数，最终读取与某版本一致")
    void concurrentUnconditionalWrites() throws Exception {
        String key = "hot/key";
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger ok = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            tasks.add(() -> {
                byte[] body = ("body-" + idx).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                service.putObject(key, new ByteArrayInputStream(body),
                        (long) body.length, sha256Hex(body), null);
                ok.incrementAndGet();
                return null;
            });
        }
        List<Future<Void>> futures = pool.invokeAll(tasks);
        for (Future<Void> f : futures) {
            f.get();
        }
        pool.shutdownNow();

        ObjectMeta meta = service.listVersions(key);
        log.info("断言依据: 并发写入成功 {} 次，版本数 {}（应相等）", ok.get(), meta.getVersions().size());
        assertEquals(threads, ok.get());
        assertEquals(threads, meta.getVersions().size());
        // 版本号互不相同
        long distinct = meta.getVersions().stream().map(v -> v.versionId()).distinct().count();
        assertEquals(threads, distinct);
        // 每个版本都能按号取回其对应内容
        for (var v : meta.getVersions()) {
            byte[] actual = java.nio.file.Files.readAllBytes(service.getObject(key, v.versionId()).path());
            assertEquals(sha256Hex(actual), v.sha256(), "每个版本内容必须与其摘要一致");
        }
    }

    @Test
    @DisplayName("并发提交同一分片（相同内容）：只有一份分片数据，最终合并确定")
    void concurrentIdenticalPartSubmission() throws Exception {
        UploadSession session = service.initiateMultipart("hot/parts", 8);
        byte[] p1 = repeatByte('a', 8);
        byte[] p2 = "final".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<PartResult>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> service.putPart(session.getUploadId(), 1,
                    new ByteArrayInputStream(p1), (long) p1.length, sha256Hex(p1)));
        }
        List<Future<PartResult>> futures = pool.invokeAll(tasks);
        int firstCount = 0;
        int duplicateCount = 0;
        for (Future<PartResult> f : futures) {
            PartResult r = f.get();
            if (r.duplicate()) {
                duplicateCount++;
            } else {
                firstCount++;
            }
        }
        pool.shutdownNow();
        log.info("断言依据: 相同分片并发提交 first={}, duplicate={}", firstCount, duplicateCount);
        assertEquals(1, firstCount, "恰好一个请求首次写入");
        assertEquals(threads - 1, duplicateCount, "其余全部幂等重放");

        service.putPart(session.getUploadId(), 2, new ByteArrayInputStream(p2),
                (long) p2.length, sha256Hex(p2));
        var done = service.completeMultipart(session.getUploadId(), null);
        String expected = new String(p1, java.nio.charset.StandardCharsets.UTF_8)
                + new String(p2, java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(expected, new String(
                java.nio.file.Files.readAllBytes(service.getObject("hot/parts", done.versionId()).path()),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("分片已存在时并发提交同号不同内容：全部得到可区分冲突错误，登记表不被破坏")
    void concurrentConflictingPartSubmission() throws Exception {
        UploadSession session = service.initiateMultipart("hot/conflict", 8);
        byte[] bodyA = repeatByte('a', 8);
        byte[] bodyB = repeatByte('b', 8);
        // 先由 bodyA 占据 1 号分片
        PartResult first = service.putPart(session.getUploadId(), 1,
                new ByteArrayInputStream(bodyA), (long) bodyA.length, sha256Hex(bodyA));
        assertFalse(first.duplicate());

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<PartResult>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> service.putPart(session.getUploadId(), 1,
                    new ByteArrayInputStream(bodyB), (long) bodyB.length, sha256Hex(bodyB)));
        }
        List<Future<PartResult>> futures = pool.invokeAll(tasks);
        int success = 0;
        int conflicts = 0;
        for (Future<PartResult> f : futures) {
            try {
                f.get();
                success++;
            } catch (Exception e) {
                StorageException cause = (StorageException) e.getCause();
                assertEquals(ErrorCode.PART_NUMBER_CONFLICT, cause.getErrorCode());
                conflicts++;
            }
        }
        pool.shutdownNow();
        log.info("断言依据: 异内容并发重放既有分片 success={}, conflict={}", success, conflicts);
        assertEquals(0, success);
        assertEquals(threads, conflicts);

        // 既有登记保持为 bodyA，且仍可幂等重放
        PartResult replayA = service.putPart(session.getUploadId(), 1,
                new ByteArrayInputStream(bodyA), (long) bodyA.length, sha256Hex(bodyA));
        assertTrue(replayA.duplicate());
        assertEquals(sha256Hex(bodyA), replayA.etag());
    }
}
