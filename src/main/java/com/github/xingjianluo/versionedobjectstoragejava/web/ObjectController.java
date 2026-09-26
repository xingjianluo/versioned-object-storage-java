package com.github.xingjianluo.versionedobjectstoragejava.web;

import com.github.xingjianluo.versionedobjectstoragejava.error.ErrorCode;
import com.github.xingjianluo.versionedobjectstoragejava.error.StorageException;
import com.github.xingjianluo.versionedobjectstoragejava.model.ObjectContent;
import com.github.xingjianluo.versionedobjectstoragejava.model.ObjectMeta;
import com.github.xingjianluo.versionedobjectstoragejava.model.PartResult;
import com.github.xingjianluo.versionedobjectstoragejava.model.PutResult;
import com.github.xingjianluo.versionedobjectstoragejava.model.StorageStats;
import com.github.xingjianluo.versionedobjectstoragejava.model.UploadSession;
import com.github.xingjianluo.versionedobjectstoragejava.service.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 对象存储对外 HTTP 接口。
 */
@RestController
@RequestMapping("/api")
public class ObjectController {

    private static final Logger log = LoggerFactory.getLogger(ObjectController.class);

    private final StorageService storageService;

    public ObjectController(StorageService storageService) {
        this.storageService = storageService;
    }

    // ---------------- 对象 ----------------

    @PutMapping("/objects/{*key}")
    public PutResult putObject(@PathVariable("key") String rawKey,
                               @RequestHeader(value = "X-Content-SHA256", required = false) String sha256,
                               @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                               @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
                               HttpServletRequest request) {
        String key = normalizeKey(rawKey);
        try {
            return storageService.putObject(key, request.getInputStream(), contentLength, sha256, ifMatch);
        } catch (java.io.IOException e) {
            throw new StorageException(ErrorCode.INTERNAL_ERROR, "读取请求体失败: " + e.getMessage());
        }
    }

    @GetMapping("/objects/{*key}")
    public ResponseEntity<FileSystemResource> getObject(
            @PathVariable("key") String rawKey,
            @RequestParam(value = "versionId", required = false) String versionId) {
        String key = normalizeKey(rawKey);
        ObjectContent content = storageService.getObject(key, versionId);
        FileSystemResource body = new FileSystemResource(content.path());
        return ResponseEntity.ok()
                .header("X-Version-Id", content.entry().versionId())
                .header("X-Content-SHA256", content.entry().sha256())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(content.entry().size())
                .body(body);
    }

    @DeleteMapping("/objects/{*key}")
    public PutResult deleteObject(@PathVariable("key") String rawKey,
                                  @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return storageService.deleteObject(normalizeKey(rawKey), ifMatch);
    }

    @GetMapping("/versions/objects/{*key}")
    public ObjectMeta listVersions(@PathVariable("key") String rawKey) {
        return storageService.listVersions(normalizeKey(rawKey));
    }

    @DeleteMapping("/versions/objects/{*key}")
    public PutResult reclaimVersion(@PathVariable("key") String rawKey,
                                    @RequestParam("versionId") String versionId) {
        return storageService.reclaimVersion(normalizeKey(rawKey), versionId);
    }

    // ---------------- 分片上传 ----------------

    @PostMapping("/multipart/{*key}")
    public UploadSession initiate(@PathVariable("key") String rawKey,
                                  @RequestParam("partSize") long partSize) {
        return storageService.initiateMultipart(normalizeKey(rawKey), partSize);
    }

    @PutMapping("/multipart/{uploadId}/parts/{partNumber}")
    public PartResult putPart(@PathVariable String uploadId,
                              @PathVariable int partNumber,
                              @RequestHeader(value = "X-Content-SHA256", required = false) String sha256,
                              HttpServletRequest request) {
        try {
            return storageService.putPart(uploadId, partNumber, request.getInputStream(),
                    request.getContentLengthLong(), sha256);
        } catch (java.io.IOException e) {
            throw new StorageException(ErrorCode.INTERNAL_ERROR, "读取请求体失败: " + e.getMessage());
        }
    }

    @PostMapping("/multipart/{uploadId}/complete")
    public PutResult complete(@PathVariable String uploadId,
                              @RequestBody(required = false) CompleteRequest request) {
        List<Integer> parts = request == null ? null : request.parts();
        return storageService.completeMultipart(uploadId, parts);
    }

    @DeleteMapping("/multipart/{uploadId}")
    public Map<String, Object> abort(@PathVariable String uploadId) {
        storageService.abortMultipart(uploadId);
        return Map.of("uploadId", uploadId, "aborted", true);
    }

    @GetMapping("/multipart/{uploadId}")
    public UploadSession status(@PathVariable String uploadId) {
        return storageService.getSession(uploadId)
                .orElseThrow(() -> new StorageException(ErrorCode.SESSION_NOT_FOUND,
                        "上传会话不存在: uploadId=" + uploadId));
    }

    // ---------------- 管理 ----------------

    @PostMapping("/admin/gc")
    public Map<String, Object> gc() {
        int swept = storageService.garbageCollect();
        return Map.of("sweptBlobs", swept);
    }

    @GetMapping("/admin/stats")
    public StorageStats stats() {
        return storageService.stats();
    }

    /** {*key} 捕获的路径带一个前导 "/"，统一去掉，使对象键与用户传入一致。 */
    private static String normalizeKey(String rawKey) {
        return rawKey.startsWith("/") ? rawKey.substring(1) : rawKey;
    }

    /** 合并请求体：可选的分片号有序列表；为空则按已收到的全部分片合并。 */
    public record CompleteRequest(List<Integer> parts) {
    }
}
