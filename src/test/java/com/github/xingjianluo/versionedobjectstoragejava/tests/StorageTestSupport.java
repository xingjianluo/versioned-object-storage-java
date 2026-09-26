package com.github.xingjianluo.versionedobjectstoragejava.tests;

import com.github.xingjianluo.versionedobjectstoragejava.config.StorageProperties;
import com.github.xingjianluo.versionedobjectstoragejava.service.StorageService;
import com.github.xingjianluo.versionedobjectstoragejava.store.BlobStore;
import com.github.xingjianluo.versionedobjectstoragejava.store.MetadataStore;
import com.github.xingjianluo.versionedobjectstoragejava.store.SessionStore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 测试支撑：为每个用例创建独立临时数据目录，可随时“重启”得到全新服务实例。
 */
public abstract class StorageTestSupport {

    protected Path root;
    protected StorageProperties properties;
    protected BlobStore blobStore;
    protected SessionStore sessionStore;
    protected MetadataStore metadataStore;
    protected StorageService service;

    protected void setUpStorage(long sessionTtlMillis) throws IOException {
        root = Files.createTempDirectory("vostore-test-");
        properties = new StorageProperties();
        properties.setRoot(root);
        properties.setSessionTtlMillis(sessionTtlMillis);
        blobStore = new BlobStore(root);
        sessionStore = new SessionStore(root);
        metadataStore = new MetadataStore(root);
        blobStore.init();
        sessionStore.init();
        metadataStore.init();
        service = newStorageService();
    }

    /** 模拟进程重启：新建全部存储组件与服务，并执行启动恢复。 */
    protected StorageService restart() throws IOException {
        blobStore = new BlobStore(root);
        sessionStore = new SessionStore(root);
        metadataStore = new MetadataStore(root);
        blobStore.init();
        sessionStore.init();
        metadataStore.init();
        service = newStorageService();
        service.recover();
        return service;
    }

    private StorageService newStorageService() {
        return new StorageService(properties, blobStore, sessionStore, metadataStore);
    }

    protected static ByteArrayInputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    protected static ByteArrayInputStream bytes(byte[] b) {
        return new ByteArrayInputStream(b);
    }

    protected static byte[] repeatByte(int value, int count) {
        byte[] b = new byte[count];
        java.util.Arrays.fill(b, (byte) value);
        return b;
    }

    protected static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected static byte[] readAll(java.io.InputStream in) throws IOException {
        return in.readAllBytes();
    }

    protected int countRegularFiles(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (var walk = Files.walk(dir)) {
            return (int) walk.filter(Files::isRegularFile).count();
        }
    }
}
