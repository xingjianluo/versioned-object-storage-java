package com.github.xingjianluo.versionedobjectstoragejava.storage;

import java.util.List;

/**
 * 只读容量视图：当前内容存储的占用情况。本对象仅用于查看，不触发任何删除。
 *
 * @param totalBytes        磁盘上全部持久化内容（blob）的总字节数
 * @param totalBlobCount    持久化内容文件数量
 * @param referencedBytes   仍被至少一个可读（非墓碑、未回收）版本引用的内容字节数
 * @param reclaimableBytes  不被任何版本引用、可安全释放的内容字节数
 * @param reclaimableBlobs  不被任何版本引用、可安全释放的内容哈希列表
 */
public record CapacityView(long totalBytes,
                           long totalBlobCount,
                           long referencedBytes,
                           long reclaimableBytes,
                           List<String> reclaimableBlobs) {
}
