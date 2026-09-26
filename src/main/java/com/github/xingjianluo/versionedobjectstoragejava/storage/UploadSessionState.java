package com.github.xingjianluo.versionedobjectstoragejava.storage;

import java.util.Map;
import java.util.TreeMap;

/**
 * 分片上传会话的可持久化状态。
 */
public class UploadSessionState {

    public enum Status { ACTIVE, COMPLETED, ABORTED }

    private String uploadId;
    private String objectKey;
    private Status status = Status.ACTIVE;
    private long createdAtEpochMs;
    /** partNumber -> sha256 校验和（十六进制）。 */
    private Map<Integer, String> parts = new TreeMap<>();

    public String getUploadId() { return uploadId; }
    public void setUploadId(String uploadId) { this.uploadId = uploadId; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public long getCreatedAtEpochMs() { return createdAtEpochMs; }
    public void setCreatedAtEpochMs(long createdAtEpochMs) { this.createdAtEpochMs = createdAtEpochMs; }
    public Map<Integer, String> getParts() { return parts; }
    public void setParts(Map<Integer, String> parts) { this.parts = parts; }
}
