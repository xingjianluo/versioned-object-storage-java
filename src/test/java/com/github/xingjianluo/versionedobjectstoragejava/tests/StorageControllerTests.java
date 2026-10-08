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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        MvcResult completed = mvc.perform(post("/uploads/{u}/complete", uploadId).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn();
        String completedVersion = mapper.readTree(completed.getResponse().getContentAsString()).get("versionId").asText();
        mvc.perform(get("/objects/file"))
                .andExpect(status().isOk())
                .andExpect(result -> assertEquals("hello-world", result.getResponse().getContentAsString()));

        // 已完成会话再次 complete：幂等重试，返回同一个版本（200），不产生新版本、不报 SESSION_EXPIRED。
        MvcResult retried = mvc.perform(post("/uploads/{u}/complete", uploadId).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionId").value(completedVersion))
                .andReturn();
        log.info("REST complete 幂等重试: uploadId={} 首次={} 重试={} 相同={}",
                uploadId, completedVersion,
                mapper.readTree(retried.getResponse().getContentAsString()).get("versionId").asText(),
                completedVersion.equals(mapper.readTree(retried.getResponse().getContentAsString()).get("versionId").asText()));
        mvc.perform(get("/objects/file/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
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
        log.info("REST GC: removedCount={} removed={}", gcBody.get("removedCount").asInt(), gcBody.get("removed"));
        // 判定依据：v1 内容必须出现在回收列表中（不依赖其他用例残留的精确计数）。
        String v1Hash = sha256("v1-content".getBytes());
        assertTrue(gcBody.get("removed").toString().contains(v1Hash), "v1 内容已无任何版本引用，必须被回收");
    }

    @Test
    void restCapacityView_isReadOnlyAndReportsReclaimable() throws Exception {
        // 写入两份不同内容，随后删除其中一个对象（墓碑仍引用内容，暂不可释放）。
        byte[] keep = "keep-me-content".getBytes();
        byte[] drop = "drop-me-content".getBytes();
        MvcResult putKeep = mvc.perform(put("/objects/keep").content(keep)).andExpect(status().isOk()).andReturn();
        MvcResult putDrop = mvc.perform(put("/objects/drop").content(drop)).andExpect(status().isOk()).andReturn();
        String dropVersion = mapper.readTree(putDrop.getResponse().getContentAsString()).get("versionId").asText();
        log.info("REST 容量视图参数: keep={}字节 drop={}字节", keep.length, drop.length);

        // 只读查询：字段齐全，调用前后对象都还在、内容没被删。
        MvcResult cap1 = mvc.perform(get("/admin/capacity")).andExpect(status().isOk()).andReturn();
        JsonNode body1 = mapper.readTree(cap1.getResponse().getContentAsString());
        log.info("REST capacity(删除前): totalBytes={} referencedBytes={} reclaimableBytes={} totalBlobs={}",
                body1.get("totalBytes").asLong(), body1.get("referencedBytes").asLong(),
                body1.get("reclaimableBytes").asLong(), body1.get("totalBlobCount").asInt());
        assertEquals(keep.length + drop.length, body1.get("totalBytes").asLong());
        assertEquals(0, body1.get("reclaimableBytes").asLong(), "墓碑仍引用内容，删除后不可立即释放");
        // 关键：容量视图不得产生删除——drop 内容仍可读取。
        mvc.perform(get("/objects/drop")).andExpect(status().isOk());

        // 删除 drop（写入墓碑，历史版本仍引用内容），再显式回收其历史版本，之后内容才计入可释放。
        mvc.perform(delete("/objects/drop")).andExpect(status().isOk());
        mvc.perform(get("/objects/drop")).andExpect(status().isNotFound());
        MvcResult cap1b = mvc.perform(get("/admin/capacity")).andExpect(status().isOk()).andReturn();
        JsonNode body1b = mapper.readTree(cap1b.getResponse().getContentAsString());
        assertEquals(0, body1b.get("reclaimableBytes").asLong(), "删除仅写墓碑，历史版本仍引用内容，不可释放");
        mvc.perform(post("/objects/drop/reclaim").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionIds\":[\"" + dropVersion + "\"]}"))
                .andExpect(status().isOk());
        MvcResult cap2 = mvc.perform(get("/admin/capacity")).andExpect(status().isOk()).andReturn();
        JsonNode body2 = mapper.readTree(cap2.getResponse().getContentAsString());
        log.info("REST capacity(回收后): totalBytes={} referencedBytes={} reclaimableBytes={} reclaimableCount={}",
                body2.get("totalBytes").asLong(), body2.get("referencedBytes").asLong(),
                body2.get("reclaimableBytes").asLong(), body2.get("reclaimableCount").asInt());
        assertEquals(drop.length, body2.get("reclaimableBytes").asLong(), "仅被回收版本引用的内容计入可释放");
        assertEquals(keep.length, body2.get("referencedBytes").asLong());
        assertEquals(1, body2.get("reclaimableCount").asInt());
        // 视图依旧没删：总占用与查询前相同。
        assertEquals(keep.length + drop.length, body2.get("totalBytes").asLong(), "只读视图不得真正释放空间");
        // keep 始终可读。
        mvc.perform(get("/objects/keep")).andExpect(status().isOk())
                .andExpect(result -> assertEquals(new String(keep), result.getResponse().getContentAsString()));
        log.info("REST 判定通过: 两次 capacity 调用后 totalBytes 不变、drop 内容仍在盘上（只读）");
    }
}
