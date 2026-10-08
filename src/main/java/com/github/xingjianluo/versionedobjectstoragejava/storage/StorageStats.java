package com.github.xingjianluo.versionedobjectstoragejava.storage;

/**
 * 容量视图（只读）：内容存储的占用情况。
 * totalXxx 为磁盘上全部内容；referencedXxx 为仍被至少一个非删除版本引用的内容；
 * reclaimableXxx 为不再被任何版本引用、可被垃圾回收安全释放的内容。
 */
public record StorageStats(
        long totalBlobs,
        long totalBytes,
        long referencedBlobs,
        long referencedBytes,
        long reclaimableBlobs,
        long reclaimableBytes) {
}
