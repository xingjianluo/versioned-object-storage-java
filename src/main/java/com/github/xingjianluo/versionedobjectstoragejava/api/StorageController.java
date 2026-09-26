package com.github.xingjianluo.versionedobjectstoragejava.api;

import com.github.xingjianluo.versionedobjectstoragejava.storage.ObjectStorageService;
import com.github.xingjianluo.versionedobjectstoragejava.storage.StoredVersion;
import com.github.xingjianluo.versionedobjectstoragejava.storage.UploadSessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 对象存储对外 REST 接口。
 */
@RestController
public class StorageController {

    private static final Logger log = LoggerFactory.getLogger(StorageController.class);

    private final ObjectStorageService service;

    public StorageController(ObjectStorageService service) {
        this.service = service;
    }

    public record VersionResponse(String versionId, String contentHash, long size, boolean deleted, long createdAtEpochMs) {
        static VersionResponse of(StoredVersion v) {
            return new VersionResponse(v.versionId(), v.contentHash(), v.size(), v.deleted(), v.createdAtEpochMs());
        }
    }

    public record CreateUploadRequest(String key) {}

    public record CreateUploadResponse(String uploadId, String key) {}

    public record CompleteUploadRequest(String expectedVersionId) {}

    public record ReclaimRequest(List<String> versionIds) {}

    /** 直接写入对象；可选请求头 X-Expected-Version 做条件更新。 */
    @PutMapping("/objects/{key}")
    public VersionResponse putObject(@PathVariable String key,
                                     @RequestBody byte[] body,
                                     @RequestHeader(value = "X-Expected-Version", required = false) String expectedVersion) throws IOException {
        log.info("PUT /objects/{} expectedVersion={} size={}", key, expectedVersion, body.length);
        return VersionResponse.of(service.putObject(key, new ByteArrayInputStream(body), expectedVersion));
    }

    /** 读取对象；可选 versionId 指定历史版本。 */
    @GetMapping("/objects/{key}")
    public ResponseEntity<byte[]> getObject(@PathVariable String key,
                                            @RequestParam(value = "versionId", required = false) String versionId) throws IOException {
        log.info("GET /objects/{} versionId={}", key, versionId);
        byte[] data = service.getObject(key, versionId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(data);
    }

    /** 删除对象（写入墓碑版本，历史版本仍可读）。 */
    @DeleteMapping("/objects/{key}")
    public VersionResponse deleteObject(@PathVariable String key,
                                        @RequestHeader(value = "X-Expected-Version", required = false) String expectedVersion) throws IOException {
        log.info("DELETE /objects/{} expectedVersion={}", key, expectedVersion);
        return VersionResponse.of(service.deleteObject(key, expectedVersion));
    }

    /** 列出对象全部版本（含墓碑）。 */
    @GetMapping("/objects/{key}/versions")
    public List<VersionResponse> listVersions(@PathVariable String key) {
        return service.listVersions(key).stream().map(VersionResponse::of).toList();
    }

    /** 创建分片上传会话。 */
    @PostMapping("/uploads")
    public ResponseEntity<CreateUploadResponse> createUpload(@RequestBody CreateUploadRequest request) throws IOException {
        if (request == null || request.key() == null || request.key().isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "key is required");
        }
        UploadSessionState session = service.createUpload(request.key());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateUploadResponse(session.getUploadId(), session.getObjectKey()));
    }

    /** 上传分片；请求头 X-Checksum-Sha256 必填。 */
    @PutMapping("/uploads/{uploadId}/parts/{partNumber}")
    public Map<String, Object> uploadPart(@PathVariable String uploadId,
                                          @PathVariable int partNumber,
                                          @RequestBody byte[] body,
                                          @RequestHeader("X-Checksum-Sha256") String checksum) throws IOException {
        log.info("PUT /uploads/{}/parts/{} checksum={} size={}", uploadId, partNumber, checksum, body.length);
        service.uploadPart(uploadId, partNumber, new ByteArrayInputStream(body), checksum);
        return Map.of("uploadId", uploadId, "partNumber", partNumber, "checksum", checksum);
    }

    /** 完成分片上传并产生新版本。 */
    @PostMapping("/uploads/{uploadId}/complete")
    public VersionResponse completeUpload(@PathVariable String uploadId,
                                          @RequestBody(required = false) CompleteUploadRequest request) throws IOException {
        String expected = request == null ? null : request.expectedVersionId();
        log.info("POST /uploads/{}/complete expectedVersion={}", uploadId, expected);
        return VersionResponse.of(service.completeUpload(uploadId, expected));
    }

    /** 中止分片上传并清理分片。 */
    @DeleteMapping("/uploads/{uploadId}")
    public Map<String, Object> abortUpload(@PathVariable String uploadId) throws IOException {
        service.abortUpload(uploadId);
        return Map.of("uploadId", uploadId, "status", "ABORTED");
    }

    /** 显式回收历史版本（最新版本不允许回收）。 */
    @PostMapping("/objects/{key}/reclaim")
    public List<VersionResponse> reclaim(@PathVariable String key,
                                         @RequestBody ReclaimRequest request) throws IOException {
        if (request == null || request.versionIds() == null || request.versionIds().isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "versionIds is required");
        }
        log.info("POST /objects/{}/reclaim versionIds={}", key, request.versionIds());
        return service.reclaimVersions(key, request.versionIds()).stream().map(VersionResponse::of).toList();
    }

    /** 手动触发垃圾回收。 */
    @PostMapping("/admin/gc")
    public Map<String, Object> gc() throws IOException {
        List<String> removed = service.garbageCollect();
        return Map.of("removed", removed, "removedCount", removed.size());
    }
}
