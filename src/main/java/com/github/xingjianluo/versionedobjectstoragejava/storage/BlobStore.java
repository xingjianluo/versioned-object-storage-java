package com.github.xingjianluo.versionedobjectstoragejava.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 基于内容寻址（SHA-256）的本地 Blob 存储：相同内容只保留一份持久化副本。
 * 写入先落到临时文件再原子改名，进程中途退出不会留下半个可见内容文件。
 */
public class BlobStore {

    private final Path blobDir;

    public BlobStore(Path blobDir) {
        this.blobDir = blobDir;
    }

    /** 写入内容并返回其 SHA-256 十六进制摘要；相同内容重复写入不会产生额外副本。 */
    public synchronized String put(InputStream in) throws IOException {
        Files.createDirectories(blobDir);
        Path tmp = blobDir.resolve("tmp-" + UUID.randomUUID());
        MessageDigest digest = newDigest();
        try (OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        }
        String hash = toHex(digest.digest());
        Path target = pathFor(hash);
        if (Files.exists(target)) {
            // 内容已存在：去重，丢弃临时副本。
            Files.deleteIfExists(tmp);
        } else {
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
        }
        return hash;
    }

    /** 按内容哈希读取内容；不存在时返回 null。 */
    public byte[] get(String contentHash) throws IOException {
        Path p = pathFor(contentHash);
        if (!Files.exists(p)) {
            return null;
        }
        return Files.readAllBytes(p);
    }

    public boolean exists(String contentHash) {
        return contentHash != null && Files.exists(pathFor(contentHash));
    }

    /** 删除指定内容；返回是否实际删除。 */
    public synchronized boolean delete(String contentHash) throws IOException {
        return Files.deleteIfExists(pathFor(contentHash));
    }

    /** 列出当前磁盘上全部内容哈希（忽略临时文件）。 */
    public Set<String> listAll() throws IOException {
        Set<String> hashes = new HashSet<>();
        if (!Files.isDirectory(blobDir)) {
            return hashes;
        }
        try (Stream<Path> stream = Files.list(blobDir)) {
            stream.map(p -> p.getFileName().toString())
                    .filter(name -> !name.startsWith("tmp-"))
                    .forEach(hashes::add);
        }
        return hashes;
    }

    /** 单个持久化内容的只读信息：哈希与字节大小。 */
    public record BlobInfo(String hash, long size) {
    }

    /** 列出当前磁盘上全部持久化内容（忽略临时文件）及其大小；只读，不删除任何东西。 */
    public List<BlobInfo> listBlobInfos() throws IOException {
        List<BlobInfo> infos = new java.util.ArrayList<>();
        if (!Files.isDirectory(blobDir)) {
            return infos;
        }
        try (Stream<Path> stream = Files.list(blobDir)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString();
                if (name.startsWith("tmp-")) {
                    continue;
                }
                infos.add(new BlobInfo(name, Files.size(p)));
            }
        }
        return infos;
    }

    /** 清理写入中断遗留的临时文件。 */
    public int cleanTempFiles() throws IOException {
        int removed = 0;
        if (!Files.isDirectory(blobDir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(blobDir)) {
            for (Path p : stream.filter(p -> p.getFileName().toString().startsWith("tmp-")).toList()) {
                Files.deleteIfExists(p);
                removed++;
            }
        }
        return removed;
    }

    private Path pathFor(String hash) {
        return blobDir.resolve(hash);
    }

    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
