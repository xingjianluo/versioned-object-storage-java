package com.github.xingjianluo.versionedobjectstoragejava.error;

/**
 * 携带可区分错误码的业务异常。
 */
public class StorageException extends RuntimeException {

    private final ErrorCode errorCode;

    public StorageException(ErrorCode errorCode) {
        super(errorCode.getReason());
        this.errorCode = errorCode;
    }

    public StorageException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
