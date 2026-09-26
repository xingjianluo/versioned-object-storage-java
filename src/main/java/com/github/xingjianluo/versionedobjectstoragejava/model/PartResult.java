package com.github.xingjianluo.versionedobjectstoragejava.model;

/**
 * 分片提交结果。{@code duplicate=true} 表示该分片此前已接收，本次为幂等重放。
 */
public record PartResult(String uploadId,
                         int partNumber,
                         String etag,
                         long size,
                         boolean duplicate) {
}
