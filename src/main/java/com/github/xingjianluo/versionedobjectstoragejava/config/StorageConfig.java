package com.github.xingjianluo.versionedobjectstoragejava.config;

import com.github.xingjianluo.versionedobjectstoragejava.service.RecoveryService;
import com.github.xingjianluo.versionedobjectstoragejava.service.StorageService;
import com.github.xingjianluo.versionedobjectstoragejava.store.BlobStore;
import com.github.xingjianluo.versionedobjectstoragejava.store.MetadataStore;
import com.github.xingjianluo.versionedobjectstoragejava.store.SessionStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 存储组件装配。
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    @Bean
    public BlobStore blobStore(StorageProperties props) throws Exception {
        Path root = props.getRoot();
        Files.createDirectories(root);
        BlobStore store = new BlobStore(root);
        store.init();
        return store;
    }

    @Bean
    public SessionStore sessionStore(StorageProperties props) throws Exception {
        Path root = props.getRoot();
        Files.createDirectories(root);
        SessionStore store = new SessionStore(root);
        store.init();
        return store;
    }

    @Bean
    public MetadataStore metadataStore(StorageProperties props) throws Exception {
        Path root = props.getRoot();
        Files.createDirectories(root);
        MetadataStore store = new MetadataStore(root);
        store.init();
        return store;
    }

    @Bean
    public StorageService storageService(StorageProperties props,
                                         BlobStore blobStore,
                                         SessionStore sessionStore,
                                         MetadataStore metadataStore) {
        return new StorageService(props, blobStore, sessionStore, metadataStore);
    }

    @Bean
    public RecoveryService recoveryService(StorageService storageService) {
        return new RecoveryService(storageService);
    }
}
