package com.github.xingjianluo.versionedobjectstoragejava.model;

/**
 * 写入（简单上传或分片合并）成功结果。
 */
public record PutResult(String key,
                        String versionId,
                        long size,
                        String sha256,
                        long createdMillis) {
}
