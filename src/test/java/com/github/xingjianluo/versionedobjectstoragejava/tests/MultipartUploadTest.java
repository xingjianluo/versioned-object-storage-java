package com.github.xingjianluo.versionedobjectstoragejava.tests;

import com.github.xingjianluo.versionedobjectstoragejava.error.ErrorCode;
import com.github.xingjianluo.versionedobjectstoragejava.error.StorageException;
import com.github.xingjianluo.versionedobjectstoragejava.model.ObjectContent;
import com.github.xingjianluo.versionedobjectstoragejava.model.PartResult;
import com.github.xingjianluo.versionedobjectstoragejava.model.PutResult;
import com.github.xingjianluo.versionedobjectstoragejava.model.UploadSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分片上传与续传：乱序合并、重复幂等、非法分片拒绝原因可区分、会话失效、重启续传。
 */
class MultipartUploadTest extends StorageTestSupport {

    private static final Logger log = LoggerFactory.getLogger(MultipartUploadTest.class);

    @BeforeEach
    void setUp() throws Exception {
        setUpStorage(60_000);
    }

    private PartResult putPart(UploadSession s, int n, byte[] body, String declaredSha) {
        return service.putPart(s.getUploadId(), n, new ByteArrayInputStream(body),
                (long) body.length, declaredSha);
    }

    private PartResult putPart(UploadSession s, int n, byte[] body) {
        return putPart(s, n, body, sha256Hex(body));
    }

    private String body(ObjectContent c) throws Exception {
        return new String(Files.readAllBytes(c.path()), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("乱序提交分片可正确合并，重复提交相同分片幂等且不产生重复数据")
    void outOfOrderAndDuplicateParts() throws Exception {
        byte[] p1 = repeatByte('a', 16);
        byte[] p2 = repeatByte('b', 16);
        byte[] p3 = "tail".getBytes(StandardCharsets.UTF_8);

        UploadSession s = service.initiateMultipart("big/file.bin", 16);
        // 乱序：先 3 再 1 再 2
        PartResult r3 = putPart(s, 3, p3);
        PartResult r1 = putPart(s, 1, p1);
        PartResult r2 = putPart(s, 2, p2);
        assertFalse(r1.duplicate());

        // 完全相同的分片重放（序号+摘要一致）
        PartResult r1Again = putPart(s, 1, p1);
        PartResult r3Again = putPart(s, 3, p3);
        assertTrue(r1Again.duplicate(), "重复分片必须标记为 duplicate");
        assertTrue(r3Again.duplicate());
        log.info("断言依据: 重复分片重放 duplicate=true etag={}", r1Again.etag());

        // 分片数据文件仍然只有 3 个
        int partFiles = countRegularFiles(root.resolve("sessions").resolve(s.getUploadId()));
        assertEquals(3, partFiles, "重复分片不得产生额外文件，实际=" + partFiles);

        // 显式乱序的合并列表也能正确拼接
        PutResult done = service.completeMultipart(s.getUploadId(), List.of(1, 2, 3));
        String expected = new String(p1, StandardCharsets.UTF_8)
                + new String(p2, StandardCharsets.UTF_8)
                + new String(p3, StandardCharsets.UTF_8);
        assertEquals(expected, body(service.getObject("big/file.bin", done.versionId())));
        assertEquals(36, done.size());
        assertEquals(sha256Hex(expected.getBytes(StandardCharsets.UTF_8)), done.sha256());
        log.info("断言依据: 合并结果 size={} sha256={} 与整体摘要一致", done.size(), done.sha256());

        // 完成后会话被清理，再次提交分片被拒绝
        StorageException closed = assertThrows(StorageException.class, () -> putPart(s, 1, p1));
        assertEquals(ErrorCode.SESSION_NOT_FOUND, closed.getErrorCode());
    }

    @Test
    @DisplayName("非法分片场景返回可区分的失败原因")
    void invalidPartsAreRejectedWithDistinctReasons() {
        byte[] p1 = repeatByte('x', 16);
        UploadSession s = service.initiateMultipart("k/x", 16);
        putPart(s, 1, p1);

        // 1) 分片校验和不匹配
        StorageException badChecksum = assertThrows(StorageException.class,
                () -> putPart(s, 2, repeatByte('y', 16), sha256Hex(p1)));
        assertEquals(ErrorCode.CHECKSUM_MISMATCH, badChecksum.getErrorCode());
        log.info("断言依据: 错误分片摘要 -> {}", badChecksum.getErrorCode());

        // 校验失败后该分片不被登记：用正确内容重提 2 仍然可以成功
        PartResult retry = putPart(s, 2, repeatByte('y', 16));
        assertFalse(retry.duplicate());

        // 2) 同分片号不同内容冲突
        StorageException conflict = assertThrows(StorageException.class,
                () -> putPart(s, 1, repeatByte('z', 16)));
        assertEquals(ErrorCode.PART_NUMBER_CONFLICT, conflict.getErrorCode());
        log.info("断言依据: 同号异内容 -> {}", conflict.getErrorCode());

        // 3) 缺少分片合并：显式列表要求不存在的第 3 片；空会话合并同样拒绝
        StorageException missing = assertThrows(StorageException.class,
                () -> service.completeMultipart(s.getUploadId(), List.of(1, 2, 3)));
        assertEquals(ErrorCode.MISSING_PART, missing.getErrorCode());
        UploadSession empty = service.initiateMultipart("k/empty", 16);
        assertEquals(ErrorCode.MISSING_PART, assertThrows(StorageException.class,
                () -> service.completeMultipart(empty.getUploadId(), null)).getErrorCode());
        log.info("断言依据: 缺失分片合并 -> {}", missing.getErrorCode());

        // 4) 非最后一片大小与 partSize 不符
        UploadSession s2 = service.initiateMultipart("k/y", 16);
        putPart(s2, 1, "short".getBytes(StandardCharsets.UTF_8));
        putPart(s2, 2, repeatByte('q', 16));
        StorageException sizeMismatch = assertThrows(StorageException.class,
                () -> service.completeMultipart(s2.getUploadId(), List.of(1, 2)));
        assertEquals(ErrorCode.PART_SIZE_MISMATCH, sizeMismatch.getErrorCode());
        log.info("断言依据: 非末片大小不符 -> {}", sizeMismatch.getErrorCode());

        // 5) 分片序号越界
        StorageException badNumber = assertThrows(StorageException.class,
                () -> putPart(s2, 0, p1));
        assertEquals(ErrorCode.INVALID_PART_NUMBER, badNumber.getErrorCode());

        // 6) 缺少校验和头
        StorageException noChecksum = assertThrows(StorageException.class,
                () -> service.putPart(s2.getUploadId(), 3, new ByteArrayInputStream(p1),
                        (long) p1.length, null));
        assertEquals(ErrorCode.BAD_REQUEST, noChecksum.getErrorCode());
        log.info("断言依据: 缺少校验和 -> {}", noChecksum.getErrorCode());
    }

    @Test
    @DisplayName("会话不存在、已中止、已超时分别返回可区分错误")
    void sessionLifecycleErrors() throws Exception {
        byte[] p1 = repeatByte('a', 8);

        // 不存在
        assertEquals(ErrorCode.SESSION_NOT_FOUND,
                assertThrows(StorageException.class,
                        () -> service.putPart("no-such-upload", 1, new ByteArrayInputStream(p1),
                                (long) p1.length, sha256Hex(p1))).getErrorCode());

        // 已中止
        UploadSession aborted = service.initiateMultipart("k/a", 8);
        service.abortMultipart(aborted.getUploadId());
        StorageException abortErr = assertThrows(StorageException.class,
                () -> putPart(aborted, 1, p1));
        assertEquals(ErrorCode.SESSION_NOT_FOUND, abortErr.getErrorCode(),
                "中止后会话文件已删除，应表现为不存在");
        // 中止幂等
        assertDoesNotThrow(() -> service.abortMultipart(aborted.getUploadId()));

        // 已超时：短 TTL + 等待
        setUpStorage(80);
        UploadSession expired = service.initiateMultipart("k/e", 8);
        Thread.sleep(150);
        StorageException expiredErr = assertThrows(StorageException.class,
                () -> putPart(expired, 1, p1));
        assertEquals(ErrorCode.SESSION_EXPIRED, expiredErr.getErrorCode());
        StorageException expiredComplete = assertThrows(StorageException.class,
                () -> service.completeMultipart(expired.getUploadId(), null));
        assertEquals(ErrorCode.SESSION_EXPIRED, expiredComplete.getErrorCode());
        log.info("断言依据: 超时会话 -> {}", expiredErr.getErrorCode());

        // 重启后超时会话被自动清理，而未超时会话保留可续传
        UploadSession fresh = service.initiateMultipart("k/f", 8);
        restart();
        assertNotNull(service.getSession(fresh.getUploadId()).orElse(null),
                "未超时会话重启后应保留以支持续传");
        assertTrue(service.getSession(expired.getUploadId()).isEmpty(),
                "超时会话重启恢复后应被清理");
    }

    @Test
    @DisplayName("分片上传中断（进程重启）后可继续上传并完成")
    void resumeAcrossRestart() throws Exception {
        byte[] p1 = repeatByte('a', 8);
        byte[] p2 = repeatByte('b', 8);
        byte[] p3 = "end".getBytes(StandardCharsets.UTF_8);

        UploadSession s = service.initiateMultipart("resume/data", 8);
        putPart(s, 1, p1);
        putPart(s, 2, p2);
        log.info("断言依据: 重启前已收分片 [1,2]，模拟进程退出");

        // 重启：分片与会话状态从磁盘恢复
        restart();
        UploadSession reloaded = service.getSession(s.getUploadId()).orElseThrow();
        assertEquals(2, reloaded.getParts().size());
        // 已传分片重放为幂等，再补齐第 3 片
        PartResult again = putPart(s, 1, p1);
        assertTrue(again.duplicate());
        PartResult r3 = putPart(s, 3, p3);
        assertFalse(r3.duplicate());

        PutResult done = service.completeMultipart(s.getUploadId(), null);
        String expected = new String(p1, StandardCharsets.UTF_8)
                + new String(p2, StandardCharsets.UTF_8)
                + new String(p3, StandardCharsets.UTF_8);
        assertEquals(expected, body(service.getObject("resume/data", done.versionId())));

        // 再重启：内容与元数据依旧一致
        restart();
        assertEquals(expected, body(service.getObject("resume/data", null)));
        log.info("断言依据: 重启续传合并成功，最终 sha256={}", done.sha256());
    }

    @Test
    @DisplayName("分片合并内容与简单上传相同内容共享同一内容块")
    void multipartSharesDeduplicatedBlob() throws Exception {
        byte[] p1 = repeatByte('m', 10);
        byte[] p2 = repeatByte('n', 6);
        byte[] whole = concat(p1, p2);

        PutResult simple = service.putObject("k/simple", new ByteArrayInputStream(whole),
                (long) whole.length, sha256Hex(whole), null);

        UploadSession s = service.initiateMultipart("k/multi", 10);
        putPart(s, 1, p1);
        putPart(s, 2, p2);
        PutResult multi = service.completeMultipart(s.getUploadId(), null);

        assertEquals(simple.sha256(), multi.sha256());
        assertEquals(1, service.stats().blobFiles(), "不同上传路径的相同内容必须去重为一个内容块");
        log.info("断言依据: 简单上传与分片上传相同内容 blobFiles=1, sha256={}", simple.sha256());
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
