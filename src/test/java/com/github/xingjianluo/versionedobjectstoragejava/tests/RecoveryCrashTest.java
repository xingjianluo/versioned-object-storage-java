package com.github.xingjianluo.versionedobjectstoragejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.versionedobjectstoragejava.model.ObjectMeta;
import com.github.xingjianluo.versionedobjectstoragejava.model.PartResult;
import com.github.xingjianluo.versionedobjectstoragejava.model.PutResult;
import com.github.xingjianluo.versionedobjectstoragejava.model.UploadSession;
import com.github.xingjianluo.versionedobjectstoragejava.model.VersionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 中断恢复：模拟写入/合并/回收中途进程退出后重启，验证元数据与存储数据始终一致。
 */
class RecoveryCrashTest extends StorageTestSupport {

    private static final Logger log = LoggerFactory.getLogger(RecoveryCrashTest.class);

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        setUpStorage(60_000);
    }

    @Test
    @DisplayName("简单写入中途崩溃：暂存文件被清理，不产生版本、不产生内容块")
    void crashDuringSimpleUpload() throws Exception {
        Path staging = blobStore.createStagingFile();
        Files.write(staging, "half-written".getBytes(StandardCharsets.UTF_8));
        // 同时伪造一个写入中的暂存文件
        Path junk = blobStore.createStagingFile();
        Files.write(junk, new byte[]{1, 2, 3});
        log.info("断言依据: 模拟崩溃时 staging 目录存在 {} 个暂存文件", 2);

        restart();

        assertEquals(0, countRegularFiles(root.resolve("staging")), "遗留暂存文件必须清空");
        assertEquals(0, service.stats().objectKeys());
        assertEquals(0, service.stats().blobFiles(), "未提交内容不得留下内容块");
    }

    @Test
    @DisplayName("分片写入中途崩溃：临时分片被清理，已提交分片保留，重启后可继续完成")
    void crashDuringPartUpload() throws Exception {
        byte[] p1 = repeatByte('a', 8);
        UploadSession s = service.initiateMultipart("crash/part", 8);
        putPartViaService(s, 1, p1);

        // 伪造一个写了一半的分片临时文件
        Path writing = root.resolve("sessions").resolve(s.getUploadId()).resolve("part-2.writing.tmp");
        Files.write(writing, "partial".getBytes(StandardCharsets.UTF_8));

        restart();
        UploadSession reloaded = service.getSession(s.getUploadId()).orElseThrow();
        assertEquals(1, reloaded.getParts().size());
        Path sessionDir = root.resolve("sessions").resolve(s.getUploadId());
        assertFalse(Files.exists(writing), "临时分片必须被清理");
        log.info("断言依据: 重启后会话保留 1 个已提交分片，临时文件已清除");

        // 继续完成
        PartResult r2 = putPartViaService(reloaded, 2, "tail".getBytes(StandardCharsets.UTF_8));
        assertFalse(r2.duplicate());
        PutResult done = service.completeMultipart(reloaded.getUploadId(), null);
        String expected = new String(p1, StandardCharsets.UTF_8) + "tail";
        assertEquals(expected,
                new String(Files.readAllBytes(service.getObject("crash/part", done.versionId()).path()),
                        StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("合并提交中途崩溃：会话为 COMMITTING，重启重放恰好补一个版本，内容与记录摘要一致")
    void crashDuringCommitIsReplayed() throws Exception {
        byte[] p1 = repeatByte('a', 8);
        byte[] p2 = "end".getBytes(StandardCharsets.UTF_8);
        UploadSession s = service.initiateMultipart("crash/commit", 8);
        putPartViaService(s, 1, p1);
        putPartViaService(s, 2, p2);
        byte[] whole = concat(p1, p2);
        String wholeSha = sha256Hex(whole);

        // 重新加载已包含分片记录的会话（putPart 持有的是另一份对象），再改写为 COMMITTING。
        // 分片文件由真实 putPart 落盘，模拟“分片已合并但元数据尚未追加”的崩溃点。
        s = sessionStore.load(s.getUploadId()).orElseThrow();
        String plannedVersion = UUID.randomUUID().toString();
        s.setState(UploadSession.State.COMMITTING);
        s.setFinalVersionId(plannedVersion);
        s.setFinalSha256(wholeSha);
        s.setFinalSize(whole.length);
        sessionStore.save(s);
        // 合并暂存文件模拟“已发布内容块但元数据未更新”：直接发布一个孤儿内容块
        Path stg = blobStore.createStagingFile();
        Files.write(stg, whole);
        String publishedSha = blobStore.publish(stg, wholeSha);
        log.info("断言依据: 模拟崩溃点 COMMITTING，孤儿内容块 sha={}, 计划版本={}", publishedSha, plannedVersion);

        log.info("断言依据: 崩溃前元数据尚不存在该对象的版本");
        restart();

        ObjectMeta meta = service.listVersions("crash/commit");
        List<String> ids = meta.getVersions().stream().map(VersionEntry::versionId).toList();
        assertEquals(List.of(plannedVersion), ids, "重放必须补且只补一个确定版本");
        VersionEntry v = meta.getVersions().get(0);
        assertEquals(wholeSha, v.sha256());
        assertEquals(whole.length, v.size());
        assertEquals(1, service.stats().blobFiles(), "孤儿内容块被同一版本引用后保留，不重复复制");
        assertTrue(service.getSession(s.getUploadId()).isEmpty(), "重放完成后会话被清理");

        // 再重启：结果保持稳定（不会重复追加版本）
        restart();
        assertEquals(1, service.listVersions("crash/commit").getVersions().size());
        assertEquals(1, service.stats().blobFiles());
    }

    @Test
    @DisplayName("崩溃在元数据已更新之后：重放不重复追加版本，内容块保持单副本")
    void crashAfterMetadataCommit() throws Exception {
        byte[] p1 = repeatByte('z', 8);
        byte[] p2 = "!".getBytes(StandardCharsets.UTF_8);
        byte[] whole = concat(p1, p2);
        UploadSession s = service.initiateMultipart("crash/after", 8);
        putPartViaService(s, 1, p1);
        putPartViaService(s, 2, p2);

        s = sessionStore.load(s.getUploadId()).orElseThrow();
        s.setState(UploadSession.State.COMMITTING);
        s.setFinalVersionId(UUID.randomUUID().toString());
        s.setFinalSha256(sha256Hex(whole));
        s.setFinalSize(whole.length);
        sessionStore.save(s);
        Path stg = blobStore.createStagingFile();
        Files.write(stg, whole);
        blobStore.publish(stg, s.getFinalSha256());
        // 模拟元数据已经追加（与服务端正常提交流程相同的 JSON 结构）
        ObjectMeta meta = new ObjectMeta("crash/after");
        meta.getVersions().add(new VersionEntry(s.getFinalVersionId(), s.getCreatedAtMillis(),
                whole.length, s.getFinalSha256(), false, false));
        metadataStore.save(meta);

        restart();
        ObjectMeta after = service.listVersions("crash/after");
        assertEquals(1, after.getVersions().size());
        assertEquals(s.getFinalVersionId(), after.getVersions().get(0).versionId());
        assertEquals(1, service.stats().blobFiles());
    }

    @Test
    @DisplayName("回收中途崩溃后重启：无引用内容块被清扫，任何可见版本都不指向缺失内容")
    void crashDuringReclaim() throws Exception {
        byte[] payload = "disposable".getBytes(StandardCharsets.UTF_8);
        PutResult v1 = service.putObject("crash/reclaim",
                new ByteArrayInputStream(payload), (long) payload.length, sha256Hex(payload), null);
        assertEquals(1, service.stats().blobFiles());

        // 模拟“回收已落盘（元数据标记 blobReclaimed）但尚未清扫”即崩溃
        ObjectMeta meta = service.listVersions("crash/reclaim");
        VersionEntry e = meta.getVersions().get(0);
        meta.getVersions().set(0, new VersionEntry(e.versionId(), e.createdMillis(), e.size(),
                e.sha256(), false, true));
        metadataStore.save(meta);
        assertTrue(Files.exists(blobStore.blobPath(v1.sha256())));
        log.info("断言依据: 版本已标记回收但内容块仍在，等待重启清扫");

        restart();
        assertFalse(Files.exists(blobStore.blobPath(v1.sha256())), "已回收且无引用的内容块必须被清扫");
        assertEquals(0, service.stats().blobFiles());
        // 被回收版本读取得到明确错误，而不是读到缺失文件
        assertEquals(com.github.xingjianluo.versionedobjectstoragejava.error.ErrorCode.VERSION_RECLAIMED,
                assertThrows(com.github.xingjianluo.versionedobjectstoragejava.error.StorageException.class,
                        () -> service.getObject("crash/reclaim", v1.versionId())).getErrorCode());

        // 同时存在另一个存活版本引用同内容块时，重启清扫不得删除它
        byte[] payload2 = "keep-me".getBytes(StandardCharsets.UTF_8);
        PutResult keep = service.putObject("crash/keep",
                new ByteArrayInputStream(payload2), (long) payload2.length, sha256Hex(payload2), null);
        ObjectMeta m2 = service.listVersions("crash/keep");
        VersionEntry k = m2.getVersions().get(0);
        m2.getVersions().set(0, new VersionEntry(k.versionId(), k.createdMillis(), k.size(),
                k.sha256(), false, true));
        // 再制造一个引用相同 sha 的存活版本
        service.putObject("crash/keep2", new ByteArrayInputStream(payload2),
                (long) payload2.length, sha256Hex(payload2), null);
        metadataStore.save(m2);
        restart();
        assertTrue(Files.exists(blobStore.blobPath(keep.sha256())),
                "仍有存活版本引用的内容块不得被回收");
        assertEquals(1, service.stats().blobFiles());
    }

    private PartResult putPartViaService(UploadSession s, int n, byte[] body) {
        return service.putPart(s.getUploadId(), n, new ByteArrayInputStream(body),
                (long) body.length, sha256Hex(body));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
