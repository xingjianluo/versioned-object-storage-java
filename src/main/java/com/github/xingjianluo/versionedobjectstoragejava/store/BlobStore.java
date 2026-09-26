package com.github.xingjianluo.versionedobjectstoragejava.store;

import com.github.xingjianluo.versionedobjectstoragejava.error.ErrorCode;
import com.github.xingjianluo.versionedobjectstoragejava.error.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 内容寻址（SHA-256）的不可变内容块存储。
 *
 * <p>布局：
 * <ul>
 *   <li>{@code blob/xx/yy/<完整哈希>} —— 已发布内容块，按哈希前四位分两级散列目录；</li>
 *   <li>{@code staging/} —— 写入中的暂存文件，崩溃后可整体清理。</li>
 * </ul>
 * 发布时若相同哈希内容块已存在则直接丢弃暂存文件，因此相同内容在磁盘上永远只有一份副本。
 */
public class BlobStore {

    private static final Logger log = LoggerFactory.getLogger(BlobStore.class);

    private final Path blobRoot;
    private final Path stagingRoot;

    public BlobStore(Path root) {
        this.blobRoot = root.resolve("blob");
        this.stagingRoot = root.resolve("staging");
    }

    /** 准备目录结构。 */
    public void init() throws IOException {
        Files.createDirectories(blobRoot);
        Files.createDirectories(stagingRoot);
    }

    /** 新建一个暂存文件，返回可写入的路径。 */
    public Path createStagingFile() throws IOException {
        return Files.createTempFile(stagingRoot, "stg-", ".tmp");
    }

    /** 计算文件的 SHA-256（小写十六进制）。 */
    public String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream dis = new DigestInputStream(in, digest)) {
            dis.transferTo(OutputStreamNull.INSTANCE);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 校验文件摘要，不匹配抛出 {@link ErrorCode#CHECKSUM_MISMATCH}。 */
    public void verifyChecksum(Path file, String expectedSha256) throws IOException {
        if (expectedSha256 == null || expectedSha256.isBlank()) {
            throw new StorageException(ErrorCode.BAD_REQUEST, "缺少 X-Content-SHA256 校验和");
        }
        String actual = sha256(file);
        if (!actual.equalsIgnoreCase(expectedSha256.trim())) {
            log.warn("校验和不匹配: expected={}, actual={}", expectedSha256, actual);
            throw new StorageException(ErrorCode.CHECKSUM_MISMATCH,
                    "校验和不匹配: expected=" + expectedSha256.trim() + ", actual=" + actual);
        }
    }

    /**
     * 若内容块不存在则把暂存文件原子移入内容块目录；已存在则删除暂存文件（内容去重）。
     *
     * @return 内容块 SHA-256（规范小写形式）
     */
    public String publish(Path stagingFile, String sha256) throws IOException {
        String digest = sha256.toLowerCase();
        Path target = blobPath(digest);
        Files.createDirectories(target.getParent());
        synchronized (blobLock(digest)) {
            if (Files.exists(target)) {
                Files.deleteIfExists(stagingFile);
                log.debug("内容块已存在，丢弃重复暂存文件: {}", digest);
            } else {
                Path tmp = target.resolveSibling(target.getFileName() + ".pub.tmp");
                Files.move(stagingFile, tmp, StandardCopyOption.ATOMIC_MOVE);
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                log.debug("发布新内容块: {}", digest);
            }
        }
        return digest;
    }

    /** 同哈希发布串行化用的内部锁键（进程内即可，跨进程由“已存在则丢弃”兜底）。 */
    private Object blobLock(String digest) {
        return ("blob:" + digest).intern();
    }

    /** 返回内容块文件路径（可能不存在）。 */
    public Path blobPath(String sha256) {
        String d = sha256.toLowerCase();
        return blobRoot.resolve(d.substring(0, 2)).resolve(d.substring(2, 4)).resolve(d);
    }

    /** 打开内容块输入流；不存在时返回 null。 */
    public InputStream openBlob(String sha256) throws IOException {
        Path p = blobPath(sha256);
        return Files.exists(p) ? Files.newInputStream(p) : null;
    }

    /** 删除未被任何存活版本引用的内容块。返回删除数量。 */
    public int sweepUnreferenced(Set<String> liveSha256) throws IOException {
        java.util.Set<String> live = new java.util.HashSet<>();
        for (String s : liveSha256) {
            if (s != null) {
                live.add(s.toLowerCase());
            }
        }
        int[] removed = {0};
        if (!Files.isDirectory(blobRoot)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(blobRoot)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                String hash = name.endsWith(".pub.tmp") ? null : name;
                if (hash == null || !live.contains(hash)) {
                    try {
                        Files.deleteIfExists(p);
                        removed[0]++;
                    } catch (IOException e) {
                        log.warn("删除孤儿内容块失败: {}", p, e);
                    }
                }
            });
        }
        // 清理空的散列目录
        try (Stream<Path> walk = Files.walk(blobRoot)) {
            walk.filter(Files::isDirectory)
                    .filter(p -> !p.equals(blobRoot))
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try (Stream<Path> s = Files.list(p)) {
                            if (s.findAny().isEmpty()) {
                                Files.deleteIfExists(p);
                            }
                        } catch (IOException ignored) {
                            // 最佳努力
                        }
                    });
        }
        log.info("内容块清扫完成: 删除 {} 个无引用内容块，存活引用数={}", removed[0], live.size());
        return removed[0];
    }

    /** 删除整个暂存目录内的所有文件（启动恢复用）。 */
    public int purgeStaging() throws IOException {
        int[] removed = {0};
        if (!Files.isDirectory(stagingRoot)) {
            return 0;
        }
        try (Stream<Path> walk = Files.list(stagingRoot)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                    removed[0]++;
                } catch (IOException e) {
                    log.warn("清理暂存文件失败: {}", p, e);
                }
            });
        }
        log.info("启动清理暂存文件: {} 个", removed[0]);
        return removed[0];
    }

    /** 当前内容块数量。 */
    public int blobCount() {
        if (!Files.isDirectory(blobRoot)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(blobRoot)) {
            return (int) walk.filter(p -> Files.isRegularFile(p)
                    && !p.getFileName().toString().endsWith(".pub.tmp")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 空输出流，仅用于驱动摘要计算。 */
    private static final class OutputStreamNull extends java.io.OutputStream {
        static final OutputStreamNull INSTANCE = new OutputStreamNull();

        @Override
        public void write(int b) {
            // discard
        }

        @Override
        public void write(byte[] b, int off, int len) {
            // discard
        }
    }
}
