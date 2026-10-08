package com.github.xingjianluo.versionedobjectstoragejava.storage;

import java.util.Map;
import java.util.TreeMap;

/**
 * 分片上传会话的可持久化状态。
 */
public class UploadSessionState {

    /**
     * ACTIVE：可继续传分片；
     * COMMITTING：完成请求已受理，提交意图（含预定版本）已落盘，版本可能已生效也可能未生效，
     *             重启恢复时收敛为 COMPLETED（补完）或 ACTIVE（内容缺失时回滚，可重试）；
     * COMPLETED：已完成，重试完成请求幂等返回 committedVersion；
     * ABORTED：已中止。
     */
    public enum Status { ACTIVE, COMMITTING, COMPLETED, ABORTED }

    private String uploadId;
    private String objectKey;
    private Status status = Status.ACTIVE;
    private long createdAtEpochMs;
    /** partNumber -> sha256 校验和（十六进制）。 */
    private Map<Integer, String> parts = new TreeMap<>();
    /**
     * 完成请求预定的版本结果。进入 COMMITTING 时落盘，此后该会话的版本标识固定不变，
     * 无论重试还是重启恢复，客户端拿到的都是这同一个版本。
     */
    private StoredVersion committedVersion;

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
    public StoredVersion getCommittedVersion() { return committedVersion; }
    public void setCommittedVersion(StoredVersion committedVersion) { this.committedVersion = committedVersion; }
}
