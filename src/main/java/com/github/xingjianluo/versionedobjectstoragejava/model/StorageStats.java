package com.github.xingjianluo.versionedobjectstoragejava.model;

/**
 * 存储状态统计。
 */
public record StorageStats(int objectKeys,
                           int totalVersions,
                           int liveVersions,
                           int deleteMarkers,
                           int blobFiles,
                           int openSessions) {
}
