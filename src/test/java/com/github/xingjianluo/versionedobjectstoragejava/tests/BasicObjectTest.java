package com.github.xingjianluo.versionedobjectstoragejava.tests;

import com.github.xingjianluo.versionedobjectstoragejava.error.ErrorCode;
import com.github.xingjianluo.versionedobjectstoragejava.error.StorageException;
import com.github.xingjianluo.versionedobjectstoragejava.model.ObjectContent;
import com.github.xingjianluo.versionedobjectstoragejava.model.ObjectMeta;
import com.github.xingjianluo.versionedobjectstoragejava.model.PutResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基础语义：每次写入产生新版本、历史版本可读、删除标记、条件更新、并发冲突、内容去重。
 */
class BasicObjectTest extends StorageTestSupport {

    private static final Logger log = LoggerFactory.getLogger(BasicObjectTest.class);

    @BeforeEach
    void setUp() throws IOException {
        setUpStorage(60_000);
    }

    @Test
    @DisplayName("每次写入都产生新版本，历史版本始终可读，删除后重写不覆盖历史")
    void versionsAreAppendOnlyAndHistoryRemainsReadable() throws IOException {
        String key = "docs/readme.txt";

        PutResult v1 = service.putObject(key, bytes("v1-content"), null, null, null);
        PutResult v2 = service.putObject(key, bytes("v2-content-longer"), null, null, null);
        log.info("断言依据: 两次写入版本不同 v1={}, v2={}", v1.versionId(), v2.versionId());
        assertNotEquals(v1.versionId(), v2.versionId());

        // 默认读最新，按版本号读历史
        assertBody("v2-content-longer", service.getObject(key, null));
        assertBody("v1-content", service.getObject(key, v1.versionId()));
        assertBody("v2-content-longer", service.getObject(key, v2.versionId()));

        // 删除：追加删除标记，历史版本仍可读
        PutResult del = service.deleteObject(key, null);
        log.info("断言依据: 删除产生新标记版本 del={}", del.versionId());
        StorageException deleted = assertThrows(StorageException.class,
                () -> service.getObject(key, null));
        assertEquals(ErrorCode.OBJECT_DELETED, deleted.getErrorCode());
        assertBody("v1-content", service.getObject(key, v1.versionId()));
        assertBody("v2-content-longer", service.getObject(key, v2.versionId()));

        // 删除后重新写入：再追加新版本，历史四个版本均保留
        PutResult v3 = service.putObject(key, bytes("v3-content"), null, null, null);
        assertBody("v3-content", service.getObject(key, null));

        ObjectMeta meta = service.listVersions(key);
        assertEquals(List.of(v1.versionId(), v2.versionId(), del.versionId(), v3.versionId()),
                meta.getVersions().stream().map(v -> v.versionId()).toList());
        log.info("断言依据: 版本序列保留删除标记，共 {} 个版本", meta.getVersions().size());

        // 重启后状态完全一致
        restart();
        assertBody("v1-content", service.getObject(key, v1.versionId()));
        assertBody("v3-content", service.getObject(key, null));
    }

    @Test
    @DisplayName("条件更新：If-Match 为当前版本才允许写，并发冲突必须被拒绝")
    void conditionalUpdateRejectsConflict() throws Exception {
        String key = "config/app.json";
        PutResult v1 = service.putObject(key, bytes("{}"), null, null, null);

        // 正确的前提版本 -> 成功
        PutResult v2 = service.putObject(key, bytes("{\"a\":1}"), null, null, v1.versionId());
        log.info("断言依据: If-Match={} 与当前版本一致，写入得到 {}", v1.versionId(), v2.versionId());

        // 过期前提 -> VERSION_CONFLICT
        StorageException conflict = assertThrows(StorageException.class,
                () -> service.putObject(key, bytes("{\"a\":2}"), null, null, v1.versionId()));
        assertEquals(ErrorCode.VERSION_CONFLICT, conflict.getErrorCode());
        log.info("断言依据: 过期前提被拒绝 errorCode={}", conflict.getErrorCode());

        // 条件删除冲突
        StorageException delConflict = assertThrows(StorageException.class,
                () -> service.deleteObject(key, v1.versionId()));
        assertEquals(ErrorCode.VERSION_CONFLICT, delConflict.getErrorCode());

        // 并发：两个线程基于同一前提版本，恰好一个成功、一个冲突，无静默覆盖
        PutResult current = service.putObject(key, bytes("base"), null, null, v2.versionId());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> task = () -> service.putObject(key,
                    bytes(Thread.currentThread().getName()), null, null, current.versionId());
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(task));
            }
            int success = 0;
            int conflictCount = 0;
            for (Future<Object> f : futures) {
                try {
                    f.get();
                    success++;
                } catch (Exception e) {
                    StorageException cause = (StorageException) e.getCause();
                    assertEquals(ErrorCode.VERSION_CONFLICT, cause.getErrorCode());
                    conflictCount++;
                }
            }
            log.info("断言依据: 并发条件写 success={}, conflict={}", success, conflictCount);
            assertEquals(1, success);
            assertEquals(1, conflictCount);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("相同内容重复写入只保留一个内容块副本，且各自版本独立可读")
    void identicalContentIsDeduplicated() throws IOException {
        byte[] payload = "repeatable-body-内容".getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(payload);

        PutResult a = service.putObject("k/a", bytes(payload), null, sha, null);
        PutResult b = service.putObject("k/b", bytes(payload), null, sha, null);
        PutResult c = service.putObject("k/a", bytes(payload), null, sha, null);
        log.info("断言依据: 三次写入 {} 字节相同内容，sha={}", payload.length, sha);

        assertEquals(sha, a.sha256());
        assertEquals(sha, b.sha256());
        assertEquals(sha, c.sha256());
        assertEquals(1, service.stats().blobFiles(), "相同 SHA-256 只能有一个内容块文件");
        assertEquals(2, service.stats().objectKeys());
        assertEquals(3, service.stats().liveVersions());
        assertBodyContent(payload, service.getObject("k/b", null));

        // 回收其中一个版本后，其余版本仍然可读；底层依旧只有 0/1 份物理副本
        service.reclaimVersion("k/a", a.versionId());
        assertBodyContent(payload, service.getObject("k/b", null));
        assertEquals(1, service.stats().blobFiles());

        StorageException gone = assertThrows(StorageException.class,
                () -> service.getObject("k/a", a.versionId()));
        assertEquals(ErrorCode.VERSION_RECLAIMED, gone.getErrorCode());
        log.info("断言依据: 已回收版本读取返回 {}", gone.getErrorCode());

        // 再回收最后两个引用（k/a 的第二版 c、k/b 的 b）-> 物理副本被清扫
        service.reclaimVersion("k/b", b.versionId());
        assertEquals(1, service.stats().blobFiles());
        service.reclaimVersion("k/a", c.versionId());
        assertEquals(0, service.stats().blobFiles());
    }

    @Test
    @DisplayName("校验和不匹配拒绝写入；对不存在对象读取返回 OBJECT_NOT_FOUND")
    void checksumMismatchAndNotFound() {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        StorageException ex = assertThrows(StorageException.class,
                () -> service.putObject("k/bad", bytes(payload), null,
                        "0000000000000000000000000000000000000000000000000000000000000000", null));
        assertEquals(ErrorCode.CHECKSUM_MISMATCH, ex.getErrorCode());
        log.info("断言依据: 错误校验和被拒绝 errorCode={}", ex.getErrorCode());

        assertThrows(StorageException.class,
                () -> service.getObject("missing", null));
        assertEquals(ErrorCode.OBJECT_NOT_FOUND,
                assertThrows(StorageException.class, () -> service.getObject("missing", null)).getErrorCode());

        // 失败写入不得留下任何版本或内容块
        assertEquals(0, service.stats().blobFiles());
    }

    private void assertBody(String expected, ObjectContent content) throws IOException {
        assertEquals(expected, new String(readAll(java.nio.file.Files.newInputStream(content.path())),
                StandardCharsets.UTF_8));
    }

    private void assertBodyContent(byte[] expected, ObjectContent content) throws IOException {
        assertArrayEquals(expected, readAll(java.nio.file.Files.newInputStream(content.path())));
    }
}
