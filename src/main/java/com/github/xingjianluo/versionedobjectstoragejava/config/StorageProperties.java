package com.github.xingjianluo.versionedobjectstoragejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 本地对象存储的持久化目录配置。
 */
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    /** 数据根目录，所有元数据与内容均持久化在该目录下。 */
    private Path dataDir = Path.of("data");

    public Path getDataDir() {
        return dataDir;
    }

    public void setDataDir(Path dataDir) {
        this.dataDir = dataDir;
    }
}
