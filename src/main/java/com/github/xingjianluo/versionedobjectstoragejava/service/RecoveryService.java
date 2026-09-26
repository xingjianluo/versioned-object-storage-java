package com.github.xingjianluo.versionedobjectstoragejava.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

/**
 * Bean 初始化完成后（HTTP 端口对外服务之前）执行崩溃恢复，
 * 保证任何外部请求到达前，临时数据已清理、提交到一半的会话已重放、孤儿内容块已清扫。
 */
public class RecoveryService implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    private final StorageService storageService;

    public RecoveryService(StorageService storageService) {
        this.storageService = storageService;
    }

    @Override
    public void afterPropertiesSet() {
        storageService.recover();
    }
}
