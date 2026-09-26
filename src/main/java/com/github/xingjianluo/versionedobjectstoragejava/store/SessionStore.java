package com.github.xingjianluo.versionedobjectstoragejava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.versionedobjectstoragejava.error.ErrorCode;
import com.github.xingjianluo.versionedobjectstoragejava.error.StorageException;
import com.github.xingjianluo.versionedobjectstoragejava.model.PartRecord;
import com.github.xingjianluo.versionedobjectstoragejava.model.UploadSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 分片上传会话与分片文件的持久化。
 *
 * <p>布局：
 * <ul>
 *   <li>{@code sessions/<uploadId>.json} —— 会话元数据，临时文件 + 原子 move 写入；</li>
 *   <li>{@code sessions/<uploadId>/part-<n>} —— 各分片数据，原子替换写入。</li>
 * </ul>
 */
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

    private final Path sessionsRoot;
    private final ObjectMapper mapper = new ObjectMapper();

    public SessionStore(Path root) {
        this.sessionsRoot = root.resolve("sessions");
    }

    public void init() throws IOException {
        Files.createDirectories(sessionsRoot);
    }

    private Path jsonPath(String uploadId) {
        return sessionsRoot.resolve(uploadId + ".json");
    }

    /** 持久化会话（原子写）。 */
    public void save(UploadSession session) throws IOException {
        Path target = jsonPath(session.getUploadId());
        Path tmp = target.resolveSibling(session.getUploadId() + ".json.tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), session);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public Optional<UploadSession> load(String uploadId) throws IOException {
        Path target = jsonPath(uploadId);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        return Optional.of(mapper.readValue(target.toFile(), UploadSession.class));
    }

    /** 列出所有会话文件。 */
    public List<UploadSession> loadAll() throws IOException {
        List<UploadSession> result = new ArrayList<>();
        if (!Files.isDirectory(sessionsRoot)) {
            return result;
        }
        try (Stream<Path> files = Files.list(sessionsRoot)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            result.add(mapper.readValue(p.toFile(), UploadSession.class));
                        } catch (IOException e) {
                            log.warn("忽略无法解析的会话文件: {}", p, e);
                        }
                    });
        }
        return result;
    }

    public void delete(String uploadId) throws IOException {
        Files.deleteIfExists(jsonPath(uploadId));
    }

    private Path sessionDir(String uploadId) {
        return sessionsRoot.resolve(uploadId);
    }

    /** 分片数据文件路径（按会话 + 分片号）。 */
    public Path partFilePath(String uploadId, int partNumber) {
        return sessionDir(uploadId).resolve("part-" + partNumber);
    }

    /** 将分片输入流写入分片文件（同目录临时文件 + 原子替换），返回字节数。 */
    public long writePartFile(String uploadId, int partNumber, InputStream in) throws IOException {
        Path dir = sessionDir(uploadId);
        Files.createDirectories(dir);
        Path target = partFilePath(uploadId, partNumber);
        Path tmp = dir.resolve("part-" + partNumber + ".writing.tmp");
        long size;
        try (InputStream input = in) {
            size = Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        log.debug("分片已落盘: uploadId={}, partNumber={}, size={}", uploadId, partNumber, size);
        return size;
    }

    /** 读取已提交分片（用于合并）。 */
    public InputStream openPart(String uploadId, PartRecord part) throws IOException {
        Path p = partFilePath(uploadId, part.partNumber());
        if (!Files.exists(p)) {
            throw new StorageException(ErrorCode.MISSING_PART,
                    "分片数据文件丢失: uploadId=" + uploadId + ", partNumber=" + part.partNumber());
        }
        return Files.newInputStream(p);
    }

    /** 删除单个分片文件。 */
    public void deletePart(String uploadId, int partNumber) throws IOException {
        Files.deleteIfExists(partFilePath(uploadId, partNumber));
    }

    /** 清理全部会话目录中写入中途遗留的 *.writing.tmp 临时分片（启动恢复用）。 */
    public int purgeWritingTempFiles() throws IOException {
        int[] removed = {0};
        if (!Files.isDirectory(sessionsRoot)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(sessionsRoot)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".writing.tmp"))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                            removed[0]++;
                        } catch (IOException e) {
                            log.warn("清理临时分片失败: {}", p, e);
                        }
                    });
        }
        if (removed[0] > 0) {
            log.info("启动清理未完成的临时分片: {} 个", removed[0]);
        }
        return removed[0];
    }

    /** 删除整个会话目录（含全部分片）。 */
    public void deleteSessionDir(String uploadId) throws IOException {
        Path dir = sessionDir(uploadId);
        if (!Files.exists(dir)) {
            return;
        }
        // 先删会话 JSON 之外的内容；会话 JSON 由 delete() 单独处理
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("删除会话文件失败: {}", p, e);
                        }
                    });
        }
    }
}
