package com.github.xingjianluo.versionedobjectstoragejava.model;

/**
 * 上传会话中已接收的一个分片。
 *
 * @param partNumber    分片序号（从 1 开始）
 * @param sha256        分片内容 SHA-256
 * @param size          分片字节数
 * @param receivedMillis 接收时间
 */
public record PartRecord(int partNumber,
                         String sha256,
                         long size,
                         long receivedMillis) {
}
