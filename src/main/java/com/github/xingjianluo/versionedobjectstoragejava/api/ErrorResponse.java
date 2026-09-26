package com.github.xingjianluo.versionedobjectstoragejava.api;

/**
 * 统一错误响应体。
 */
public record ErrorResponse(String code, String message) {
}
