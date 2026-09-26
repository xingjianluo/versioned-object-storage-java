package com.github.xingjianluo.versionedobjectstoragejava.model;

/**
 * 对象的一个历史版本（或删除标记）。
 *
 * @param versionId   版本唯一标识
 * @param createdMillis 创建时间
 * @param size        对象字节数；删除标记为 0
 * @param sha256      内容 SHA-256；删除标记为 null
 * @param deleteMarker 是否为删除标记
 * @param blobReclaimed 引用的内容块是否已被显式回收
 */
public record VersionEntry(String versionId,
                           long createdMillis,
                           long size,
                           String sha256,
                           boolean deleteMarker,
                           boolean blobReclaimed) {
}
