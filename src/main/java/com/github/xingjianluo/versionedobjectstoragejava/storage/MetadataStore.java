package com.github.xingjianluo.versionedobjectstoragejava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 对象版本与上传会话的持久化元数据存储。
 * 内存索引 + JSON 文件落盘；落盘先写临时文件再原子改名，
 * 进程在落盘中途退出不会损坏既有元数据。
 */
public class MetadataStore {

    /** 落盘用的顶层结构。 */
    public static class State {
        public Map<String, List<StoredVersion>> objects = new LinkedHashMap<>();
        public Map<String, UploadSessionState> sessions = new LinkedHashMap<>();
    }

    private final Path metadataFile;
    private final ObjectMapper mapper = new ObjectMapper();
    private final State state = new State();

    public MetadataStore(Path metadataFile) {
        this.metadataFile = metadataFile;
    }

    /** 启动时加载元数据；文件不存在时初始化为空。 */
    public synchronized void load() throws IOException {
        if (Files.exists(metadataFile)) {
            State loaded = mapper.readValue(metadataFile.toFile(), State.class);
            state.objects.clear();
            state.objects.putAll(loaded.objects);
            state.sessions.clear();
            state.sessions.putAll(loaded.sessions);
        }
    }

    /** 将当前内存状态原子落盘。 */
    public synchronized void flush() throws IOException {
        Files.createDirectories(metadataFile.getParent());
        Path tmp = metadataFile.resolveSibling(metadataFile.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), state);
        Files.move(tmp, metadataFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public synchronized List<StoredVersion> listVersions(String key) {
        return List.copyOf(state.objects.getOrDefault(key, List.of()));
    }

    /** 最新版本（含墓碑）；无版本时为空。 */
    public synchronized Optional<StoredVersion> latestVersion(String key) {
        List<StoredVersion> versions = state.objects.get(key);
        if (versions == null || versions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(versions.get(versions.size() - 1));
    }

    public synchronized Optional<StoredVersion> findVersion(String key, String versionId) {
        return state.objects.getOrDefault(key, List.of()).stream()
                .filter(v -> v.versionId().equals(versionId))
                .findFirst();
    }

    /** 追加一个新版本并落盘，返回该版本。 */
    public synchronized StoredVersion appendVersion(String key, StoredVersion version) throws IOException {
        state.objects.computeIfAbsent(key, k -> new ArrayList<>()).add(version);
        flush();
        return version;
    }

    /** 显式回收指定历史版本（从元数据中移除并落盘），返回实际移除的版本。 */
    public synchronized List<StoredVersion> removeVersions(String key, java.util.Collection<String> versionIds) throws IOException {
        List<StoredVersion> versions = state.objects.get(key);
        if (versions == null) {
            return List.of();
        }
        List<StoredVersion> removed = versions.stream()
                .filter(v -> versionIds.contains(v.versionId()))
                .toList();
        versions.removeIf(v -> versionIds.contains(v.versionId()));
        if (versions.isEmpty()) {
            state.objects.remove(key);
        }
        flush();
        return removed;
    }

    public synchronized Map<String, List<StoredVersion>> allObjects() {
        Map<String, List<StoredVersion>> copy = new LinkedHashMap<>();
        state.objects.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return copy;
    }

    public synchronized void putSession(UploadSessionState session) throws IOException {
        state.sessions.put(session.getUploadId(), session);
        flush();
    }

    public synchronized Optional<UploadSessionState> getSession(String uploadId) {
        return Optional.ofNullable(state.sessions.get(uploadId));
    }

    public synchronized Map<String, UploadSessionState> allSessions() {
        return new LinkedHashMap<>(state.sessions);
    }

    /** 生成全局唯一的版本标识。 */
    public static String newVersionId() {
        return UUID.randomUUID().toString();
    }
}
