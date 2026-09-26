package com.github.xingjianluo.versionedobjectstoragejava.storage;

/**
 * 一个对象版本的元数据记录。deleted=true 表示删除墓碑版本。
 */
public record StoredVersion(
        String versionId,
        String contentHash,
        long size,
        boolean deleted,
        long createdAtEpochMs) {
}
