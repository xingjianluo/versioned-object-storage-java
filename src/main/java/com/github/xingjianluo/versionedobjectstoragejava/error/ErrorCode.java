package com.github.xingjianluo.versionedobjectstoragejava.error;

import org.springframework.http.HttpStatus;

/**
 * 可区分的失败原因。每个错误码绑定确定的 HTTP 状态码。
 */
public enum ErrorCode {

    BAD_REQUEST(HttpStatus.BAD_REQUEST, "请求参数不合法"),
    INVALID_PART_NUMBER(HttpStatus.BAD_REQUEST, "分片序号非法"),
    PART_SIZE_MISMATCH(HttpStatus.BAD_REQUEST, "分片大小与约定不符"),
    CHECKSUM_MISMATCH(HttpStatus.BAD_REQUEST, "内容校验和与声明不一致"),
    MISSING_PART(HttpStatus.BAD_REQUEST, "合并时存在缺失的分片"),
    SESSION_EXPIRED(HttpStatus.CONFLICT, "上传会话已失效（超时）"),
    SESSION_NOT_OPEN(HttpStatus.CONFLICT, "上传会话已结束（完成或中止）"),
    PART_NUMBER_CONFLICT(HttpStatus.CONFLICT, "同一分片号已提交不同内容"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "条件更新版本不匹配"),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "上传会话不存在"),
    OBJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "对象不存在"),
    VERSION_NOT_FOUND(HttpStatus.NOT_FOUND, "指定版本不存在"),
    OBJECT_DELETED(HttpStatus.GONE, "对象已被删除（删除标记）"),
    VERSION_RECLAIMED(HttpStatus.GONE, "该版本内容已被显式回收"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "内部错误");

    private final HttpStatus status;
    private final String reason;

    ErrorCode(HttpStatus status, String reason) {
        this.status = status;
        this.reason = reason;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }
}
