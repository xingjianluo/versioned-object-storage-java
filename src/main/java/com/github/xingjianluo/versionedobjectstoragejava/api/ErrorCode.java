package com.github.xingjianluo.versionedobjectstoragejava.api;

/**
 * 可区分的失败原因编码。
 */
public enum ErrorCode {
    OBJECT_NOT_FOUND,
    VERSION_NOT_FOUND,
    VERSION_CONFLICT,
    SESSION_NOT_FOUND,
    SESSION_EXPIRED,
    CHECKSUM_MISMATCH,
    MISSING_PARTS,
    INVALID_PART,
    BAD_REQUEST
}
