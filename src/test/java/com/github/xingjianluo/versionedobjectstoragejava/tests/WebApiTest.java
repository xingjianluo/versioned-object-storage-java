package com.github.xingjianluo.versionedobjectstoragejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP 接口端到端测试：覆盖对象/分片/条件更新/错误码可区分/管理接口。
 * 使用独立 WebApplicationContext，数据目录由 application-test.properties 指向临时目录。
 */
@org.springframework.boot.test.context.SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class WebApiTest {

    private final ObjectMapper json = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    private MockMvc mvc;

    @org.springframework.beans.factory.annotation.Value("${vostore.root}")
    private Path storageRoot;

    /** Spring 上下文跨用例复用、磁盘目录固定，因此每个用例前清空数据，保证可重复。 */
    @BeforeEach
    void cleanStorageRoot() throws Exception {
        if (storageRoot == null || !Files.exists(storageRoot)) {
            return;
        }
        try (var walk = Files.walk(storageRoot)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 最佳努力
                }
            });
        }
        // 存储组件只在 Bean 初始化时建目录，这里重建基础目录结构
        Files.createDirectories(storageRoot.resolve("blob"));
        Files.createDirectories(storageRoot.resolve("staging"));
        Files.createDirectories(storageRoot.resolve("sessions"));
        Files.createDirectories(storageRoot.resolve("meta"));
    }

    private static String sha(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("对象 PUT/GET 版本化、条件更新冲突与删除语义的完整 HTTP 链路")
    void objectLifecycleOverHttp() throws Exception {
        String key = "http/lifecycle.txt";
        String v1Body = "version-one";
        String v2Body = "version-two-longer";

        MvcResult r1 = mvc.perform(put("/api/objects/" + key)
                        .header("X-Content-SHA256", sha(v1Body))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(v1Body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionId").isNotEmpty())
                .andReturn();
        String v1 = json.readTree(r1.getResponse().getContentAsString()).get("versionId").asText();
        org.slf4j.LoggerFactory.getLogger(WebApiTest.class)
                .info("HTTP 断言依据: PUT v1 成功 versionId={}", v1);

        MvcResult r2 = mvc.perform(put("/api/objects/" + key)
                        .header("X-Content-SHA256", sha(v2Body))
                        .header("If-Match", v1)
                        .content(v2Body))
                .andExpect(status().isOk()).andReturn();
        String v2 = json.readTree(r2.getResponse().getContentAsString()).get("versionId").asText();

        // 条件冲突 -> 409 + VERSION_CONFLICT
        mvc.perform(put("/api/objects/" + key).header("If-Match", v1).content("x"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("VERSION_CONFLICT"));

        // 默认读最新，指定版本读历史
        mvc.perform(get("/api/objects/" + key))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Version-Id", v2));
        mvc.perform(get("/api/objects/" + key).param("versionId", v1))
                .andExpect(status().isOk())
                .andExpect(content().bytes(v1Body.getBytes(StandardCharsets.UTF_8)));

        // 版本列表
        mvc.perform(get("/api/versions/objects/" + key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions.length()").value(2));

        // 删除 -> 410；历史版本仍可读；重写 -> 新版本
        mvc.perform(delete("/api/objects/" + key).header("If-Match", v2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sha256").doesNotExist());
        mvc.perform(get("/api/objects/" + key))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.errorCode").value("OBJECT_DELETED"));
        mvc.perform(get("/api/objects/" + key).param("versionId", v1))
                .andExpect(status().isOk());

        mvc.perform(get("/api/objects/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("OBJECT_NOT_FOUND"));
    }

    @Test
    @DisplayName("分片上传 HTTP 链路：初始化、乱序分片、重复幂等、校验失败、缺失分片、合并读取")
    void multipartOverHttp() throws Exception {
        String key = "http/multi.bin";
        byte[] p1 = new byte[16];
        java.util.Arrays.fill(p1, (byte) 'A');
        byte[] p2 = new byte[16];
        java.util.Arrays.fill(p2, (byte) 'B');
        byte[] p3 = "tail".getBytes(StandardCharsets.UTF_8);

        MvcResult init = mvc.perform(post("/api/multipart/" + key).param("partSize", "16"))
                .andExpect(status().isOk()).andReturn();
        String uploadId = json.readTree(init.getResponse().getContentAsString()).get("uploadId").asText();

        // 乱序提交 3,1,2
        putPart(uploadId, 3, p3);
        putPart(uploadId, 1, p1);
        putPart(uploadId, 2, p2);

        // 重复提交 1 -> duplicate=true
        mvc.perform(put("/api/multipart/{id}/parts/{n}", uploadId, 1)
                        .header("X-Content-SHA256", sha(new String(p1, StandardCharsets.UTF_8)))
                        .content(p1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        // 错误校验和 -> 400 + CHECKSUM_MISMATCH
        mvc.perform(put("/api/multipart/{id}/parts/{n}", uploadId, 9)
                        .header("X-Content-SHA256", "deadbeef")
                        .content(p1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("CHECKSUM_MISMATCH"));

        // 缺失分片合并（显式要求 1..4） -> 400 + MISSING_PART
        mvc.perform(post("/api/multipart/{id}/complete", uploadId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(new CompleteBody(List.of(1, 2, 3, 4)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_PART"));

        // 正常合并
        MvcResult done = mvc.perform(post("/api/multipart/{id}/complete", uploadId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(new CompleteBody(List.of(1, 2, 3)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(36)).andReturn();
        String versionId = json.readTree(done.getResponse().getContentAsString()).get("versionId").asText();

        byte[] whole = concat(p1, p2, p3);
        mvc.perform(get("/api/objects/" + key).param("versionId", versionId))
                .andExpect(status().isOk())
                .andExpect(content().bytes(whole));

        // 未知会话 -> 404 + SESSION_NOT_FOUND
        mvc.perform(get("/api/multipart/unknown-upload"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SESSION_NOT_FOUND"));

        // 统计接口
        mvc.perform(get("/api/admin/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blobFiles").isNumber());
        mvc.perform(post("/api/admin/gc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sweptBlobs").isNumber());
    }

    private void putPart(String uploadId, int n, byte[] body) throws Exception {
        mvc.perform(put("/api/multipart/{id}/parts/{n}", uploadId, n)
                        .header("X-Content-SHA256", sha(new String(body, StandardCharsets.UTF_8)))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partNumber").value(n));
    }

    private static byte[] concat(byte[]... arrays) {
        int len = 0;
        for (byte[] a : arrays) {
            len += a.length;
        }
        byte[] out = new byte[len];
        int off = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, out, off, a.length);
            off += a.length;
        }
        return out;
    }

    public record CompleteBody(List<Integer> parts) {
    }
}
