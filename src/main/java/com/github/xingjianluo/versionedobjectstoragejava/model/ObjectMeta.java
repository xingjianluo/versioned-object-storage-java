package com.github.xingjianluo.versionedobjectstoragejava.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个对象键的全部版本元数据。
 */
public class ObjectMeta {

    private String key;
    /** 按时间先后排列的版本（旧 -> 新）。 */
    private List<VersionEntry> versions = new ArrayList<>();

    public ObjectMeta() {
    }

    public ObjectMeta(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public List<VersionEntry> getVersions() {
        return versions;
    }

    public void setVersions(List<VersionEntry> versions) {
        this.versions = versions;
    }
}
