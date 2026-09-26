package com.github.xingjianluo.versionedobjectstoragejava.config;

import com.github.xingjianluo.versionedobjectstoragejava.storage.BlobStore;
import com.github.xingjianluo.versionedobjectstoragejava.storage.MetadataStore;
import com.github.xingjianluo.versionedobjectstoragejava.storage.ObjectStorageService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 存储组件装配。
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfiguration {

    @Bean
    public ObjectStorageService objectStorageService(StorageProperties props) throws IOException {
        Path dataDir = props.getDataDir();
        Files.createDirectories(dataDir.resolve("blobs"));
        Files.createDirectories(dataDir.resolve("uploads"));
        BlobStore blobStore = new BlobStore(dataDir.resolve("blobs"));
        MetadataStore metadataStore = new MetadataStore(dataDir.resolve("metadata.json"));
        metadataStore.load();
        ObjectStorageService service = new ObjectStorageService(blobStore, metadataStore, dataDir.resolve("uploads"));
        service.recover();
        return service;
    }
}
