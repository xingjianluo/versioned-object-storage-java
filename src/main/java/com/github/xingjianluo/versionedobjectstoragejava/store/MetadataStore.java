package com.github.xingjianluo.versionedobjectstoragejava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.versionedobjectstoragejava.model.ObjectMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 按对象键持久化版本元数据。
 *
 * <p>布局：{@code meta/xx/<URL编码后的键>.json}，单键文件原子读写。
 * URL 编码保证任意键（含 "/"）都映射为单一安全文件名，不产生目录嵌套。
 */
public class MetadataStore {

    private static final Logger log = LoggerFactory.getLogger(MetadataStore.class);

    private final Path metaRoot;
    private final ObjectMapper mapper = new ObjectMapper();

    public MetadataStore(Path root) {
        this.metaRoot = root.resolve("meta");
    }

    public void init() throws IOException {
        Files.createDirectories(metaRoot);
    }

    private String encodeKey(String key) {
        return URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private Path metaPath(String key) {
        String encoded = encodeKey(key);
        String shard = encoded.substring(0, Math.min(2, encoded.length())).toLowerCase();
        return metaRoot.resolve(shard).resolve(encoded + ".json");
    }

    /** 读取一个键的元数据（不存在返回 empty）。 */
    public Optional<ObjectMeta> load(String key) throws IOException {
        Path target = metaPath(key);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        return Optional.of(mapper.readValue(target.toFile(), ObjectMeta.class));
    }

    /** 原子写入（临时文件 + move）。 */
    public void save(ObjectMeta meta) throws IOException {
        Path target = metaPath(meta.getKey());
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), meta);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 加载全部对象元数据（GC 扫描用）。 */
    public List<ObjectMeta> loadAll() throws IOException {
        List<ObjectMeta> result = new ArrayList<>();
        if (!Files.isDirectory(metaRoot)) {
            return result;
        }
        try (Stream<Path> walk = Files.walk(metaRoot)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            result.add(mapper.readValue(p.toFile(), ObjectMeta.class));
                        } catch (IOException e) {
                            log.warn("忽略无法解析的元数据文件: {}", p, e);
                        }
                    });
        }
        return result;
    }
}
