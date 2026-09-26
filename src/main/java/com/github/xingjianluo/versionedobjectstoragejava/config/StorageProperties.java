package com.github.xingjianluo.versionedobjectstoragejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 本地存储相关配置。
 */
@ConfigurationProperties(prefix = "vostore")
public class StorageProperties {

    /** 数据根目录。 */
    private Path root = Path.of("data", "vostore");

    /** 上传会话空闲多少毫秒后视为失效。 */
    private long sessionTtlMillis = 24L * 60 * 60 * 1000;

    public Path getRoot() {
        return root;
    }

    public void setRoot(Path root) {
        this.root = root;
    }

    public long getSessionTtlMillis() {
        return sessionTtlMillis;
    }

    public void setSessionTtlMillis(long sessionTtlMillis) {
        this.sessionTtlMillis = sessionTtlMillis;
    }
}
