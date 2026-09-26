package com.github.xingjianluo.versionedobjectstoragejava.api;

import org.springframework.http.HttpStatus;

/**
 * 携带可区分失败原因的业务异常。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final HttpStatus status;

    public ApiException(ErrorCode code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public ErrorCode getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
