package com.github.xingjianluo.versionedobjectstoragejava.model;

import java.nio.file.Path;

/**
 * 一次读取命中的版本及其本地内容文件。
 */
public record ObjectContent(VersionEntry entry, Path path) {
}
