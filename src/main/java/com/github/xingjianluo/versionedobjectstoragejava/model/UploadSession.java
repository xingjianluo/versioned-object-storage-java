package com.github.xingjianluo.versionedobjectstoragejava.model;

import java.util.HashMap;
import java.util.Map;

/**
 * 分片上传会话。
 */
public class UploadSession {

    public enum State {
        /** 接收分片中。 */
        OPEN,
        /** 全部分片已合并、内容块与新版本正在提交（崩溃后启动时重放）。 */
        COMMITTING,
        /** 提交完成（正常情况下会话随即被删除）。 */
        COMPLETED,
        /** 已中止。 */
        ABORTED
    }

    private String uploadId;
    private String key;
    private long createdAtMillis;
    private long lastTouchMillis;
    private long partSize;
    private State state = State.OPEN;
    /** partNumber -> 分片记录 */
    private Map<Integer, PartRecord> parts = new HashMap<>();

    public UploadSession() {
    }

    public String getUploadId() {
        return uploadId;
    }

    public void setUploadId(String uploadId) {
        this.uploadId = uploadId;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public void setCreatedAtMillis(long createdAtMillis) {
        this.createdAtMillis = createdAtMillis;
    }

    public long getLastTouchMillis() {
        return lastTouchMillis;
    }

    public void setLastTouchMillis(long lastTouchMillis) {
        this.lastTouchMillis = lastTouchMillis;
    }

    public long getPartSize() {
        return partSize;
    }

    public void setPartSize(long partSize) {
        this.partSize = partSize;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public Map<Integer, PartRecord> getParts() {
        return parts;
    }

    public void setParts(Map<Integer, PartRecord> parts) {
        this.parts = parts;
    }

    private String finalVersionId;
    private String finalSha256;
    private long finalSize;

    public String getFinalVersionId() {
        return finalVersionId;
    }

    public void setFinalVersionId(String finalVersionId) {
        this.finalVersionId = finalVersionId;
    }

    public String getFinalSha256() {
        return finalSha256;
    }

    public void setFinalSha256(String finalSha256) {
        this.finalSha256 = finalSha256;
    }

    public long getFinalSize() {
        return finalSize;
    }

    public void setFinalSize(long finalSize) {
        this.finalSize = finalSize;
    }
}
