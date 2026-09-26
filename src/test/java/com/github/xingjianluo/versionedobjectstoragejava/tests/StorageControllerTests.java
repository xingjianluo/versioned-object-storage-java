package com.github.xingjianluo.versionedobjectstoragejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 接口层测试：验证对外接口与可区分的错误响应。
 */
@SpringBootTest(properties = "storage.data-dir=${java.io.tmpdir}/vos-controller-tests-" + "${random.uuid}")
@AutoConfigureMockMvc
class StorageControllerTests {

    private static final Logger log = LoggerFactory.getLogger(StorageControllerTests.class);

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

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

    @Test
    void restApi_endToEnd_andDistinguishableErrors() throws Exception {
        // 写入 v1。
        MvcResult put1 = mvc.perform(put("/objects/doc").content("v1-content".getBytes()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionId").exists())
                .andReturn();
        String v1 = mapper.readTree(put1.getResponse().getContentAsString()).get("versionId").asText();
        log.info("REST 写入: key=doc -> versionId={}", v1);

        // 条件更新冲突 -> 409 VERSION_CONFLICT。
        mvc.perform(put("/objects/doc").content("v2".getBytes()).header("X-Expected-Version", "stale"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        log.info("REST 条件冲突: expectedVersion=stale -> 409 VERSION_CONFLICT");

        // 正确条件更新 -> 成功。
        MvcResult put2 = mvc.perform(put("/objects/doc").content("v2-content".getBytes())
                        .header("X-Expected-Version", v1))
                .andExpect(status().isOk()).andReturn();
        String v2 = mapper.readTree(put2.getResponse().getContentAsString()).get("versionId").asText();

        // 读取历史版本与最新版本。
        mvc.perform(get("/objects/doc").param("versionId", v1))
                .andExpect(status().isOk())
                .andExpect(result -> assertEquals("v1-content", result.getResponse().getContentAsString()));
        mvc.perform(get("/objects/doc"))
                .andExpect(status().isOk())
                .andExpect(result -> assertEquals("v2-content", result.getResponse().getContentAsString()));

        // 版本列表。
        mvc.perform(get("/objects/doc/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // 分片上传：创建会话 -> 传分片 -> 校验和错误可区分 -> 完成。
        MvcResult upload = mvc.perform(post("/uploads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"file\"}"))
                .andExpect(status().isCreated()).andReturn();
        String uploadId = mapper.readTree(upload.getResponse().getContentAsString()).get("uploadId").asText();
        byte[] part1 = "hello-".getBytes();
        byte[] part2 = "world".getBytes();

        mvc.perform(put("/uploads/{u}/parts/1", uploadId).content(part1).header("X-Checksum-Sha256", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHECKSUM_MISMATCH"));
        log.info("REST 分片校验和错误: uploadId={} -> 400 CHECKSUM_MISMATCH", uploadId);

        mvc.perform(put("/uploads/{u}/parts/1", uploadId).content(part1)
                        .header("X-Checksum-Sha256", sha256(part1)))
                .andExpect(status().isOk());
        mvc.perform(put("/uploads/{u}/parts/2", uploadId).content(part2)
                        .header("X-Checksum-Sha256", sha256(part2)))
                .andExpect(status().isOk());
        mvc.perform(post("/uploads/{u}/complete", uploadId).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mvc.perform(get("/objects/file"))
                .andExpect(status().isOk())
                .andExpect(result -> assertEquals("hello-world", result.getResponse().getContentAsString()));

        // 已完成会话再操作 -> 409 SESSION_EXPIRED；不存在会话 -> 404 SESSION_NOT_FOUND。
        mvc.perform(post("/uploads/{u}/complete", uploadId).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        mvc.perform(post("/uploads/{u}/complete", "missing").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));

        // 删除 -> 墓碑；再读 -> 404 OBJECT_NOT_FOUND；历史版本仍可读。
        mvc.perform(delete("/objects/doc").header("X-Expected-Version", v2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
        mvc.perform(get("/objects/doc"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OBJECT_NOT_FOUND"));
        mvc.perform(get("/objects/doc").param("versionId", v1))
                .andExpect(status().isOk());

        // 显式回收历史版本 + GC。
        mvc.perform(post("/objects/doc/reclaim").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionIds\":[\"" + v1 + "\"]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/objects/doc").param("versionId", v1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VERSION_NOT_FOUND"));
        MvcResult gc = mvc.perform(post("/admin/gc")).andExpect(status().isOk()).andReturn();
        JsonNode gcBody = mapper.readTree(gc.getResponse().getContentAsString());
        log.info("REST GC: removedCount={}", gcBody.get("removedCount").asInt());
        assertEquals(1, gcBody.get("removedCount").asInt(), "v1 内容已无任何版本引用，必须被回收");
    }
}
