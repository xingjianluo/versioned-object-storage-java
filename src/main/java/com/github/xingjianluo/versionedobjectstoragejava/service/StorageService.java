package com.github.xingjianluo.versionedobjectstoragejava.service;

import com.github.xingjianluo.versionedobjectstoragejava.config.StorageProperties;
import com.github.xingjianluo.versionedobjectstoragejava.error.ErrorCode;
import com.github.xingjianluo.versionedobjectstoragejava.error.StorageException;
import com.github.xingjianluo.versionedobjectstoragejava.model.ObjectContent;
import com.github.xingjianluo.versionedobjectstoragejava.model.ObjectMeta;
import com.github.xingjianluo.versionedobjectstoragejava.model.PartRecord;
import com.github.xingjianluo.versionedobjectstoragejava.model.PartResult;
import com.github.xingjianluo.versionedobjectstoragejava.model.PutResult;
import com.github.xingjianluo.versionedobjectstoragejava.model.StorageStats;
import com.github.xingjianluo.versionedobjectstoragejava.model.UploadSession;
import com.github.xingjianluo.versionedobjectstoragejava.model.VersionEntry;
import com.github.xingjianluo.versionedobjectstoragejava.store.BlobStore;
import com.github.xingjianluo.versionedobjectstoragejava.store.MetadataStore;
import com.github.xingjianluo.versionedobjectstoragejava.store.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 对象存储核心服务。
 *
 * <p>关键不变量：
 * <ul>
 *   <li>每次写入（简单上传/分片合并/删除）都在元数据中追加一个新版本，历史版本永不覆盖；</li>
 *   <li>元数据仅在内容块成功发布后才更新，因此任何可见版本必然指向存在的内容块；</li>
 *   <li>相同 SHA-256 的内容共享同一个内容块，不产生额外持久化副本；</li>
 *   <li>每键一把互斥锁保证元数据变更串行化，全局读写锁保证回收与提交不会交错；</li>
 *   <li>条件更新（If-Match）基于“当前最新版本号”乐观校验，冲突返回 VERSION_CONFLICT。</li>
 * </ul>
 */
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private static final int MIN_PART_NUMBER = 1;
    private static final int MAX_PART_NUMBER = 10000;

    private final StorageProperties properties;
    private final BlobStore blobStore;
    private final SessionStore sessionStore;
    private final MetadataStore metadataStore;

    private final ConcurrentHashMap<String, ReentrantLock> keyLocks = new ConcurrentHashMap<>();
    /** 分片提交按会话串行化，保证“查重—写文件—登记会话”的原子性。 */
    private final ConcurrentHashMap<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();
    /** 提交（写/合并）持读锁；GC 持写锁，二者互斥。 */
    private final ReentrantReadWriteLock gcLock = new ReentrantReadWriteLock();

    public StorageService(StorageProperties properties,
                          BlobStore blobStore,
                          SessionStore sessionStore,
                          MetadataStore metadataStore) {
        this.properties = properties;
        this.blobStore = blobStore;
        this.sessionStore = sessionStore;
        this.metadataStore = metadataStore;
    }

    private ReentrantLock lockFor(String key) {
        return keyLocks.computeIfAbsent(key, k -> new ReentrantLock());
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    // ==================== 简单对象操作 ====================

    /**
     * 简单上传（一次 PUT）。
     *
     * @param contentSha256 可选的声明摘要；非空时必须与实际内容一致
     * @param ifMatch       条件更新：非空时必须等于当前最新版本号
     */
    public PutResult putObject(String key, InputStream content, Long contentLength,
                               String contentSha256, String ifMatch) {
        validateKey(key);
        long start = now();
        log.info("PUT object: key={}, declaredSize={}, declaredSha256={}, ifMatch={}",
                key, contentLength, contentSha256, ifMatch);
        ReentrantLock keyLock = lockFor(key);
        keyLock.lock();
        gcLock.readLock().lock();
        try {
            checkIfMatch(key, ifMatch);

            Path staging = blobStore.createStagingFile();
            MessageDigest digest = newDigest();
            long size;
            try (OutputStream out = Files.newOutputStream(staging);
                 DigestInputStream dis = new DigestInputStream(content, digest)) {
                size = dis.transferTo(out);
            } catch (IOException e) {
                deleteQuietly(staging);
                throw new StorageException(ErrorCode.INTERNAL_ERROR, "写入暂存文件失败: " + e.getMessage());
            }
            String actualSha = toHex(digest.digest());
            if (contentSha256 != null && !contentSha256.isBlank()
                    && !actualSha.equalsIgnoreCase(contentSha256.trim())) {
                deleteQuietly(staging);
                log.warn("PUT 校验和不匹配: key={}, expected={}, actual={}",
                        key, contentSha256.trim(), actualSha);
                throw new StorageException(ErrorCode.CHECKSUM_MISMATCH,
                        "校验和不匹配: expected=" + contentSha256.trim() + ", actual=" + actualSha);
            }
            String publishedSha = blobStore.publish(staging, actualSha);
            PutResult result = appendVersion(key, publishedSha, size);
            log.info("PUT 成功: key={}, versionId={}, size={}, sha256={}, elapsedMs={}",
                    key, result.versionId(), size, publishedSha, now() - start);
            return result;
        } catch (IOException e) {
            throw new StorageException(ErrorCode.INTERNAL_ERROR, "持久化失败: " + e.getMessage());
        } finally {
            gcLock.readLock().unlock();
            keyLock.unlock();
        }
    }

    /** 读取对象；versionId 为空取最新版本。 */
    public ObjectContent getObject(String key, String versionId) {
        validateKey(key);
        log.info("GET object: key={}, versionId={}", key, versionId);
        try {
            ObjectMeta meta = metadataStore.load(key)
                    .orElseThrow(() -> new StorageException(ErrorCode.OBJECT_NOT_FOUND,
                            "对象不存在: key=" + key));
            VersionEntry entry;
            if (versionId == null || versionId.isBlank()) {
                entry = latest(meta);
            } else {
                entry = meta.getVersions().stream()
                        .filter(v -> v.versionId().equals(versionId))
                        .findFirst()
                        .orElseThrow(() -> new StorageException(ErrorCode.VERSION_NOT_FOUND,
                                "版本不存在: key=" + key + ", versionId=" + versionId));
            }
            checkReadable(entry, key);
            Path path = blobStore.blobPath(entry.sha256());
            if (!Files.exists(path)) {
                // 不变量被破坏（理论上不可达）：显式失败而非返回错误内容
                log.error("可见版本指向缺失内容块: key={}, versionId={}, sha256={}",
                        key, entry.versionId(), entry.sha256());
                throw new StorageException(ErrorCode.INTERNAL_ERROR,
                        "内容块缺失: sha256=" + entry.sha256());
            }
            log.info("GET 命中: key={}, versionId={}, size={}", key, entry.versionId(), entry.size());
            return new ObjectContent(entry, path);
        } catch (IOException e) {
            throw new StorageException(ErrorCode.INTERNAL_ERROR, "读取元数据失败: " + e.getMessage());
        }
    }

    /**
     * 删除对象：追加一个删除标记版本（历史版本不被覆盖，仍可按 versionId 读取）。
     */
    public PutResult deleteObject(String key, String ifMatch) {
        validateKey(key);
        log.info("DELETE object: key={}, ifMatch={}", key, ifMatch);
        ReentrantLock keyLock = lockFor(key);
        keyLock.lock();
        try {
            ObjectMeta meta = metadataStore.load(key)
                    .orElseThrow(() -> new StorageException(ErrorCode.OBJECT_NOT_FOUND,
                            "对象不存在: key=" + key));
            VersionEntry latest = latest(meta);
            if (ifMatch != null && !ifMatch.isBlank()) {
                if (!ifMatch.equals(latest.versionId())) {
                    log.warn("条件删除冲突: key={}, ifMatch={}, currentVersion={}",
                            key, ifMatch, latest.versionId());
                    throw new StorageException(ErrorCode.VERSION_CONFLICT,
                            "If-Match=" + ifMatch + " 与当前版本 " + latest.versionId() + " 不匹配");
                }
            }
            if (latest.deleteMarker()) {
                throw new StorageException(ErrorCode.OBJECT_DELETED,
                        "对象已删除（最新版本是删除标记）: key=" + key);
            }
            return appendDeleteMarker(meta);
        } catch (IOException e) {
            throw new StorageException(ErrorCode.INTERNAL_ERROR, "删除失败: " + e.getMessage());
        } finally {
            keyLock.unlock();
        }
    }

    /** 列出一个键的全部版本（含删除标记与已回收版本），不存在则 404。 */
    public ObjectMeta listVersions(String key) {
        validateKey(key);
        log.info("LIST versions: key={}", key);
        try {
            return metadataStore.load(key)
                    .orElseThrow(() -> new StorageException(ErrorCode.OBJECT_NOT_FOUND,
                            "对象不存在: key=" + key));
        } catch (IOException e) {
            throw new StorageException(ErrorCode.INTERNAL_ERROR, "读取元数据失败: " + e.getMessage());
        }
    }

    /**
     * 显式回收指定版本的内容块引用（立即尝试清扫）。回收后该版本仍在历史中可见，
     * 但读取内容返回 VERSION_RECLAIMED；删除标记不可回收；重复回收幂等。
     */
    public PutResult reclaimVersion(String key, String versionId) {
        validateKey(key);
        log.info("RECLAIM version: key={}, versionId={}", key, versionId);
        ReentrantLock keyLock = lockFor(key);
        keyLock.lock();
        gcLock.writeLock().lock();
        try {
            ObjectMeta meta = metadataStore.load(key)
                    .orElseThrow(() -> new StorageException(ErrorCode.OBJECT_NOT_FOUND,
                            "对象不存在: key=" + key));
            int idx = -1;
            for (int i = 0; i < meta.getVersions().size(); i++) {
                if (meta.getVersions().get(i).versionId().equals(versionId)) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                throw new StorageException(ErrorCode.VERSION_NOT_FOUND,
                        "版本不存在: key=" + key + ", versionId=" + versionId);
            }
            VersionEntry entry = meta.getVersions().get(idx);
            if (entry.deleteMarker()) {
                throw new StorageException(ErrorCode.BAD_REQUEST, "删除标记不支持回收");
            }
            if (entry.blobReclaimed()) {
                log.info("版本已回收，幂等返回: key={}, versionId={}", key, versionId);
                return new PutResult(key, entry.versionId(), entry.size(), entry.sha256(), entry.createdMillis());
            }
            VersionEntry marked = new VersionEntry(entry.versionId(), entry.createdMillis(),
                    entry.size(), entry.sha256(), false, true);
            meta.getVersions().set(idx, marked);
            metadataStore.save(meta);
            int swept = sweepAll();
            log.info("RECLAIM 完成: key={}, versionId={}, 本次清扫孤儿内容块={}", key, versionId, swept);
            return new PutResult(key, marked.versionId(), marked.size(), marked.sha256(), marked.createdMillis());
        } catch (IOException e) {
            throw new StorageException(ErrorCode.INTERNAL_ERROR, "回收失败: " + e.getMessage());
        } finally {
            gcLock.writeLock().unlock();
            keyLock.unlock();
        }
    }

    // ==================== 分片上传 ====================

    /** 初始化分片上传会话。partSize 为除最后一片外每片应有的字节数。 */
    public UploadSession initiateMultipart(String key, long partSize) {
        validateKey(key);
        if (partSize <= 0) {
            throw new StorageException(ErrorCode.BAD_REQUEST, "partSize 必须为正数: " + partSize);
        }
        long t = now();
        UploadSession session = new UploadSession();
        session.setUploadId(UUID.randomUUID().toString());
        session.setKey(key);
        session.setPartSize(partSize);
        session.setCreatedAtMillis(t);
        session.setLastTouchMillis(t);
        session.setState(UploadSession.State.OPEN);
        try {
            sessionStore.save(session);
        } catch (IOException e) {
            throw new StorageException(ErrorCode.INTERNAL_ERROR, "创建会话失败: " + e.getMessage());
        }
        log.info("INIT multipart: key={}, uploadId={}, partSize={}", key, session.getUploadId(), partSize);
        return session;
    }

    /**
     * 提交一个分片。
     * <ul>
     *   <li>必须携带 X-Content-SHA256，且与实际内容一致，否则 CHECKSUM_MISMATCH；</li>
     *   <li>相同分片号 + 相同摘要重复提交为幂等成功（duplicate=true），不产生重复数据；</li>
     *   <li>相同分片号但摘要不同返回 PART_NUMBER_CONFLICT；</li>
     *   <li>会话不存在/已失效/非开放状态分别返回可区分错误。</li>
     * </ul>
     */
    public PartResult putPart(String uploadId, int partNumber, InputStream content,
                              Long contentLength, String contentSha256) {
        long start = now();
        log.info("PUT part: uploadId={}, partNumber={}, declaredSize={}, declaredSha256={}",
                uploadId, partNumber, contentLength, contentSha256);
        if (partNumber < MIN_PART_NUMBER || partNumber > MAX_PART_NUMBER) {
            throw new StorageException(ErrorCode.INVALID_PART_NUMBER,
                    "分片序号超出范围 [" + MIN_PART_NUMBER + "," + MAX_PART_NUMBER + "]: " + partNumber);
        }
        if (contentSha256 == null || contentSha256.isBlank()) {
            throw new StorageException(ErrorCode.BAD_REQUEST,
                    "分片上传必须携带 X-Content-SHA256 校验和");
        }
        UploadSession session = requireOpenSession(uploadId);
        ReentrantLock sessionLock = sessionLocks.computeIfAbsent(uploadId, k -> new ReentrantLock());
        sessionLock.lock();
        try {
            return doPutPart(uploadId, partNumber, content, contentLength, contentSha256, session, start);
        } finally {
            sessionLock.unlock();
        }
    }

    private PartResult doPutPart(String uploadId, int partNumber, InputStream content,
                                 Long contentLength, String contentSha256, UploadSession session,
                                 long start) {
        // 锁内重新加载，拿到最新分片登记表，避免并发下使用过期快照
        try {
            session = sessionStore.load(uploadId)
                    .orElseThrow(() -> new StorageException(ErrorCode.SESSION_NOT_FOUND,
                            "上传会话不存在: uploadId=" + uploadId));
        } catch (IOException e) {
            throw new StorageException(ErrorCode.INTERNAL_ERROR, "读取会话失败: " + e.getMessage());
        }
        if (session.getState() != UploadSession.State.OPEN) {
            throw new StorageException(ErrorCode.SESSION_NOT_OPEN,
                    "会话状态为 " + session.getState() + ": uploadId=" + uploadId);
        }
        if (isExpired(session)) {
            throw new StorageException(ErrorCode.SESSION_EXPIRED,
                    "上传会话已超时失效: uploadId=" + uploadId);
        }
        Path target = sessionStore.partFilePath(uploadId, partNumber);
        PartRecord existing = session.getParts().get(partNumber);
        if (existing != null) {
            if (existing.sha256().equalsIgnoreCase(contentSha256.trim())) {
                log.info("分片重复提交，幂等接收: uploadId={}, partNumber={}, sha256={}",
                        uploadId, partNumber, existing.sha256());
                return new PartResult(uploadId, partNumber, existing.sha256(), existing.size(), true);
            }
            log.warn("分片号冲突: uploadId={}, partNumber={}, existingSha={}, newSha={}",
                    uploadId, partNumber, existing.sha256(), contentSha256.trim());
            throw new StorageException(ErrorCode.PART_NUMBER_CONFLICT,
                    "分片 " + partNumber + " 已以不同内容提交: existing=" + existing.sha256()
                            + ", submitted=" + contentSha256.trim());
        }

        long size;
        try {
            size = sessionStore.writePartFile(uploadId, partNumber, content);
        } catch (IOException e) {
            throw new StorageException(ErrorCode.INTERNAL_ERROR, "分片落盘失败: " + e.getMessage());
        }
        String actualSha;
        try {
            actualSha = blobStore.sha256(target);
        } catch (IOException e) {
            throw new StorageException(ErrorCode.INTERNAL_ERROR, "分片摘要计算失败: " + e.getMessage());
        }
        if (!actualSha.equalsIgnoreCase(contentSha256.trim())) {
            deleteQuietly(target);
            log.warn("分片校验和不匹配: uploadId={}, partNumber={}, expected={}, actual={}",
                    uploadId, partNumber, contentSha256.trim(), actualSha);
            throw new StorageException(ErrorCode.CHECKSUM_MISMATCH,
                    "分片 " + partNumber + " 校验和不匹配: expected=" + contentSha256.trim()
                            + ", actual=" + actualSha);
        }

        session.getParts().put(partNumber, new PartRecord(partNumber, actualSha, size, now()));
        session.setLastTouchMillis(now());
        persistSession(session);
        log.info("PUT part 成功: uploadId={}, partNumber={}, size={}, sha256={}, elapsedMs={}",
                uploadId, partNumber, size, actualSha, now() - start);
        return new PartResult(uploadId, partNumber, actualSha, size, false);
    }

    /**
     * 合并分片并生成新版本。
     * <ul>
     *   <li>partNumbers 为空时使用全部已收分片；给定列表必须 1..n 连续且无缺失；</li>
     *   <li>除最后一片外，其余分片大小必须等于会话约定的 partSize；</li>
     *   <li>乱序提交的分片按分片号排序合并，结果确定；</li>
     *   <li>崩溃重启后再次调用 complete 为重放：直接返回已提交版本。</li>
     * </ul>
     */
    public PutResult completeMultipart(String uploadId, List<Integer> partNumbers) {
        long start = now();
        log.info("COMPLETE multipart: uploadId={}, requestedParts={}", uploadId, partNumbers);
        UploadSession session;
        try {
            session = sessionStore.load(uploadId)
                    .orElseThrow(() -> new StorageException(ErrorCode.SESSION_NOT_FOUND,
                            "上传会话不存在: uploadId=" + uploadId));
        } catch (IOException e) {
            throw new StorageException(ErrorCode.INTERNAL_ERROR, "读取会话失败: " + e.getMessage());
        }

        if (session.getState() == UploadSession.State.COMPLETED) {
            log.info("会话已完成，幂等返回既有版本: uploadId={}, versionId={}",
                    uploadId, session.getFinalVersionId());
            return new PutResult(session.getKey(), session.getFinalVersionId(),
                    session.getFinalSize(), session.getFinalSha256(), 0L);
        }
        if (session.getState() == UploadSession.State.ABORTED) {
            throw new StorageException(ErrorCode.SESSION_NOT_OPEN, "会话已中止: uploadId=" + uploadId);
        }
        if (isExpired(session)) {
            throw new StorageException(ErrorCode.SESSION_EXPIRED,
                    "上传会话已超时失效: uploadId=" + uploadId + ", ttlMillis=" + properties.getSessionTtlMillis());
        }
        // COMMITTING 说明上次提交中途崩溃，由启动恢复负责重放；这里也允许同步重放
        if (session.getState() == UploadSession.State.COMMITTING) {
            return replayCommitting(session);
        }

        ReentrantLock sessionLock = sessionLocks.computeIfAbsent(uploadId, k -> new ReentrantLock());
        sessionLock.lock();
        try {
            return doCompleteMultipart(session, partNumbers, start);
        } finally {
            sessionLock.unlock();
        }
    }

    private PutResult doCompleteMultipart(UploadSession session, List<Integer> partNumbers, long start) {
        String uploadId = session.getUploadId();
        try {
            session = sessionStore.load(uploadId)
                    .orElseThrow(() -> new StorageException(ErrorCode.SESSION_NOT_FOUND,
                            "上传会话不存在: uploadId=" + uploadId));
        } catch (IOException e) {
            throw new StorageException(ErrorCode.INTERNAL_ERROR, "读取会话失败: " + e.getMessage());
        }
        List<Integer> order = resolvePartOrder(session, partNumbers);

        long partSize = session.getPartSize();
        long total = 0;
        for (int i = 0; i < order.size(); i++) {
            PartRecord part = session.getParts().get(order.get(i));
            boolean last = i == order.size() - 1;
            if (!last && part.size() != partSize) {
                log.warn("分片大小不符: uploadId={}, partNumber={}, size={}, expectedPartSize={}",
                        uploadId, part.partNumber(), part.size(), partSize);
                throw new StorageException(ErrorCode.PART_SIZE_MISMATCH,
                        "分片 " + part.partNumber() + " 大小 " + part.size()
                                + " 与约定 partSize=" + partSize + " 不符（仅最后一片允许较短）");
            }
            total += part.size();
        }

        // 1) 合并到暂存文件并校验
        Path staging;
        try {
            staging = blobStore.createStagingFile();
        } catch (IOException e) {
            throw new StorageException(ErrorCode.INTERNAL_ERROR, "创建暂存文件失败: " + e.getMessage());
        }
        MessageDigest digest = newDigest();
        try (OutputStream out = Files.newOutputStream(staging)) {
            for (Integer n : order) {
                PartRecord part = session.getParts().get(n);
                try (InputStream in = sessionStore.openPart(uploadId, part);
                     DigestInputStream dis = new DigestInputStream(in, digest)) {
                    dis.transferTo(out);
                }
            }
        } catch (IOException e) {
            deleteQuietly(staging);
            throw new StorageException(ErrorCode.INTERNAL_ERROR, "合并分片失败: " + e.getMessage());
        }
        String mergedSha = toHex(digest.digest());

        // 2) 会话进入 COMMITTING 并记录最终结果（重放所需）
        ReentrantLock keyLock = lockFor(session.getKey());
        keyLock.lock();
        gcLock.readLock().lock();
        try {
            String versionId = UUID.randomUUID().toString();
            session.setState(UploadSession.State.COMMITTING);
            session.setFinalVersionId(versionId);
            session.setFinalSha256(mergedSha);
            session.setFinalSize(total);
            session.setLastTouchMillis(now());
            persistSession(session);

            // 3) 发布内容块（相同内容去重）
            String publishedSha = blobStore.publish(staging, mergedSha);

            // 4) 提交元数据（必然指向已存在的内容块）
            VersionEntry entry = new VersionEntry(versionId, now(), total, publishedSha, false, false);
            appendEntry(session.getKey(), entry);

            // 5) 标记 COMPLETED 后清理会话
            session.setState(UploadSession.State.COMPLETED);
            persistSession(session);
        } catch (IOException e) {
            throw new StorageException(ErrorCode.INTERNAL_ERROR, "提交合并结果失败: " + e.getMessage());
        } finally {
            gcLock.readLock().unlock();
            keyLock.unlock();
        }

        cleanupSession(uploadId);
        log.info("COMPLETE 成功: uploadId={}, key={}, versionId={}, parts={}, size={}, sha256={}, elapsedMs={}",
                uploadId, session.getKey(), session.getFinalVersionId(), order.size(),
                total, mergedSha, now() - start);
        return new PutResult(session.getKey(), session.getFinalVersionId(), total, mergedSha, 0L);
    }

    /** 中止会话：幂等，删除分片文件与会话。 */
    public void abortMultipart(String uploadId) {
        log.info("ABORT multipart: uploadId={}", uploadId);
        ReentrantLock sessionLock = sessionLocks.computeIfAbsent(uploadId, k -> new ReentrantLock());
        sessionLock.lock();
        try {
            UploadSession session;
            try {
                session = sessionStore.load(uploadId).orElse(null);
                sessionStore.deleteSessionDir(uploadId);
                sessionStore.delete(uploadId);
            } catch (IOException e) {
                throw new StorageException(ErrorCode.INTERNAL_ERROR, "中止会话失败: " + e.getMessage());
            }
            if (session != null) {
                log.info("会话已中止并清理: uploadId={}, key={}, receivedParts={}",
                        uploadId, session.getKey(), session.getParts().size());
            }
        } finally {
            sessionLock.unlock();
        }
    }

    public Optional<UploadSession> getSession(String uploadId) {
        try {
            return sessionStore.load(uploadId);
        } catch (IOException e) {
            throw new StorageException(ErrorCode.INTERNAL_ERROR, "读取会话失败: " + e.getMessage());
        }
    }

    // ==================== 回收与统计 ====================

    /** 标记清扫：以全部未回收、非删除标记版本引用的 SHA-256 为存活集，删除其余内容块。 */
    public int garbageCollect() {
        log.info("MANUAL GC 开始");
        gcLock.writeLock().lock();
        try {
            int swept;
            try {
                swept = sweepAll();
            } catch (IOException e) {
                throw new StorageException(ErrorCode.INTERNAL_ERROR, "GC 失败: " + e.getMessage());
            }
            log.info("MANUAL GC 完成，删除孤儿内容块 {} 个", swept);
            return swept;
        } finally {
            gcLock.writeLock().unlock();
        }
    }

    public StorageStats stats() {
        try {
            List<ObjectMeta> all = metadataStore.loadAll();
            int totalVersions = 0;
            int liveVersions = 0;
            int deleteMarkers = 0;
            for (ObjectMeta m : all) {
                for (VersionEntry v : m.getVersions()) {
                    totalVersions++;
                    if (v.deleteMarker()) {
                        deleteMarkers++;
                    } else if (!v.blobReclaimed()) {
                        liveVersions++;
                    }
                }
            }
            int openSessions = 0;
            for (UploadSession s : sessionStore.loadAll()) {
                if (s.getState() == UploadSession.State.OPEN) {
                    openSessions++;
                }
            }
            return new StorageStats(all.size(), totalVersions, liveVersions, deleteMarkers,
                    blobStore.blobCount(), openSessions);
        } catch (IOException e) {
            throw new StorageException(ErrorCode.INTERNAL_ERROR, "统计失败: " + e.getMessage());
        }
    }

    // ==================== 崩溃恢复（供 RecoveryService 调用） ====================

    /**
     * 启动恢复：
     * <ol>
     *   <li>清理所有写入中途的临时分片与暂存文件；</li>
     *   <li>COMMITTING 会话：重放内容块发布与元数据追加（幂等），再清理；</li>
     *   <li>COMPLETED 会话：直接清理；OPEN 但已超时：清理；未超时：保留可续传；</li>
     *   <li>清扫无任何版本引用的孤儿内容块。</li>
     * </ol>
     */
    public void recover() {
        log.info("=== 启动恢复开始，root={} ===", properties.getRoot());
        try {
            blobStore.purgeStaging();
            sessionStore.purgeWritingTempFiles();

            List<UploadSession> sessions = sessionStore.loadAll();
            for (UploadSession s : sessions) {
                switch (s.getState()) {
                    case COMMITTING -> {
                        log.info("恢复: 重放提交到一半的会话 uploadId={}, key={}, versionId={}",
                                s.getUploadId(), s.getKey(), s.getFinalVersionId());
                        replayCommitting(s);
                    }
                    case COMPLETED -> {
                        log.info("恢复: 清理已完成会话 uploadId={}", s.getUploadId());
                        cleanupSession(s.getUploadId());
                    }
                    case ABORTED -> {
                        log.info("恢复: 清理已中止会话 uploadId={}", s.getUploadId());
                        cleanupSession(s.getUploadId());
                    }
                    case OPEN -> {
                        if (isExpired(s)) {
                            log.info("恢复: 清理超时会话 uploadId={}, key={}, lastTouch={}",
                                    s.getUploadId(), s.getKey(), s.getLastTouchMillis());
                            cleanupSession(s.getUploadId());
                        } else {
                            log.info("恢复: 保留可续传会话 uploadId={}, key={}, parts={}",
                                    s.getUploadId(), s.getKey(), s.getParts().size());
                        }
                    }
                }
            }

            int swept = garbageCollect();
            log.info("=== 启动恢复完成，清扫孤儿内容块 {} 个 ===", swept);
        } catch (IOException e) {
            throw new StorageException(ErrorCode.INTERNAL_ERROR, "启动恢复失败: " + e.getMessage());
        }
    }

    // ==================== 内部方法 ====================

    private PutResult replayCommitting(UploadSession session) {
        String uploadId = session.getUploadId();
        ReentrantLock keyLock = lockFor(session.getKey());
        keyLock.lock();
        gcLock.readLock().lock();
        try {
            ObjectMeta meta = metadataStore.load(session.getKey()).orElse(null);
            boolean alreadyCommitted = meta != null && meta.getVersions().stream()
                    .anyMatch(v -> v.versionId().equals(session.getFinalVersionId()));
            if (!alreadyCommitted) {
                // 内容块可能已发布也可能未发布：重新合并发布一次（内容寻址保证幂等去重）
                List<Integer> order = session.getParts().keySet().stream().sorted().toList();
                Path staging;
                try {
                    staging = blobStore.createStagingFile();
                } catch (IOException e) {
                    throw new StorageException(ErrorCode.INTERNAL_ERROR, "创建暂存文件失败: " + e.getMessage());
                }
                MessageDigest digest = newDigest();
                try (OutputStream out = Files.newOutputStream(staging)) {
                    for (Integer n : order) {
                        PartRecord part = session.getParts().get(n);
                        try (InputStream in = sessionStore.openPart(uploadId, part);
                             DigestInputStream dis = new DigestInputStream(in, digest)) {
                            dis.transferTo(out);
                        }
                    }
                }
                String mergedSha = toHex(digest.digest());
                if (!mergedSha.equalsIgnoreCase(session.getFinalSha256())) {
                    deleteQuietly(staging);
                    throw new StorageException(ErrorCode.INTERNAL_ERROR,
                            "恢复时重算摘要与记录不符: uploadId=" + uploadId
                                    + ", recorded=" + session.getFinalSha256() + ", recomputed=" + mergedSha);
                }
                String publishedSha = blobStore.publish(staging, mergedSha);
                VersionEntry entry = new VersionEntry(session.getFinalVersionId(),
                        session.getCreatedAtMillis(), session.getFinalSize(), publishedSha, false, false);
                appendEntry(session.getKey(), entry);
                log.info("恢复: 元数据已补提交 key={}, versionId={}", session.getKey(), publishedSha);
            } else {
                log.info("恢复: 版本已存在于元数据，仅补完清理: versionId={}", session.getFinalVersionId());
            }
            session.setState(UploadSession.State.COMPLETED);
            persistSession(session);
        } catch (IOException e) {
            throw new StorageException(ErrorCode.INTERNAL_ERROR, "重放提交失败: " + e.getMessage());
        } finally {
            gcLock.readLock().unlock();
            keyLock.unlock();
        }
        PutResult result = new PutResult(session.getKey(), session.getFinalVersionId(),
                session.getFinalSize(), session.getFinalSha256(), 0L);
        cleanupSession(uploadId);
        return result;
    }

    private int sweepAll() throws IOException {
        Set<String> live = collectLiveDigests();
        return blobStore.sweepUnreferenced(live);
    }

    private Set<String> collectLiveDigests() throws IOException {
        Set<String> live = new HashSet<>();
        // 未结束会话的分片本身尚未成为 blob（分片独立存放），无需计入；
        // 但 COMMITTING 会话的最终摘要必须视为存活，防止重放前被清扫。
        for (UploadSession s : sessionStore.loadAll()) {
            if (s.getState() == UploadSession.State.COMMITTING && s.getFinalSha256() != null) {
                live.add(s.getFinalSha256().toLowerCase());
            }
        }
        for (ObjectMeta m : metadataStore.loadAll()) {
            for (VersionEntry v : m.getVersions()) {
                if (!v.deleteMarker() && !v.blobReclaimed() && v.sha256() != null) {
                    live.add(v.sha256().toLowerCase());
                }
            }
        }
        return live;
    }

    private List<Integer> resolvePartOrder(UploadSession session, List<Integer> requested) {
        List<Integer> order = new ArrayList<>();
        if (requested == null || requested.isEmpty()) {
            order.addAll(session.getParts().keySet());
            order.sort(Integer::compareTo);
        } else {
            order.addAll(requested);
        }
        if (order.isEmpty()) {
            throw new StorageException(ErrorCode.MISSING_PART, "没有任何已接收的分片可合并");
        }
        // 必须为 1..n 连续，且每个分片都已接收
        for (int i = 0; i < order.size(); i++) {
            int expected = i + 1;
            Integer n = order.get(i);
            if (n == null || n != expected) {
                throw new StorageException(ErrorCode.MISSING_PART,
                        "分片序列必须为从 1 开始的连续序列，位置 " + expected + " 实际为 " + n);
            }
            if (!session.getParts().containsKey(n)) {
                throw new StorageException(ErrorCode.MISSING_PART,
                        "缺失分片: partNumber=" + n + ", uploadId=" + session.getUploadId());
            }
        }
        return order;
    }

    private UploadSession requireOpenSession(String uploadId) {
        UploadSession session;
        try {
            session = sessionStore.load(uploadId)
                    .orElseThrow(() -> new StorageException(ErrorCode.SESSION_NOT_FOUND,
                            "上传会话不存在: uploadId=" + uploadId));
        } catch (IOException e) {
            throw new StorageException(ErrorCode.INTERNAL_ERROR, "读取会话失败: " + e.getMessage());
        }
        if (session.getState() != UploadSession.State.OPEN) {
            throw new StorageException(ErrorCode.SESSION_NOT_OPEN,
                    "会话状态为 " + session.getState() + ": uploadId=" + uploadId);
        }
        if (isExpired(session)) {
            throw new StorageException(ErrorCode.SESSION_EXPIRED,
                    "上传会话已超时失效: uploadId=" + uploadId + ", ttlMillis="
                            + properties.getSessionTtlMillis());
        }
        return session;
    }

    private boolean isExpired(UploadSession session) {
        return now() - session.getLastTouchMillis() > properties.getSessionTtlMillis();
    }

    private void persistSession(UploadSession session) {
        try {
            sessionStore.save(session);
        } catch (IOException e) {
            throw new StorageException(ErrorCode.INTERNAL_ERROR, "会话持久化失败: " + e.getMessage());
        }
    }

    private void cleanupSession(String uploadId) {
        try {
            sessionStore.deleteSessionDir(uploadId);
            sessionStore.delete(uploadId);
        } catch (IOException e) {
            throw new StorageException(ErrorCode.INTERNAL_ERROR, "清理会话失败: " + e.getMessage());
        }
    }

    private void checkIfMatch(String key, String ifMatch) throws IOException {
        if (ifMatch == null || ifMatch.isBlank()) {
            return;
        }
        ObjectMeta meta = metadataStore.load(key).orElse(null);
        String current = meta == null ? null : latest(meta).versionId();
        if (!ifMatch.equals(current)) {
            log.warn("条件更新冲突: key={}, ifMatch={}, currentVersion={}", key, ifMatch, current);
            throw new StorageException(ErrorCode.VERSION_CONFLICT,
                    "If-Match=" + ifMatch + " 与当前版本 " + current + " 不匹配");
        }
    }

    private PutResult appendVersion(String key, String sha256, long size) throws IOException {
        VersionEntry entry = new VersionEntry(UUID.randomUUID().toString(), now(), size, sha256, false, false);
        appendEntry(key, entry);
        return new PutResult(key, entry.versionId(), size, sha256, entry.createdMillis());
    }

    private PutResult appendDeleteMarker(ObjectMeta meta) throws IOException {
        VersionEntry entry = new VersionEntry(UUID.randomUUID().toString(), now(), 0, null, true, false);
        meta.getVersions().add(entry);
        metadataStore.save(meta);
        log.info("追加删除标记: key={}, versionId={}, 历史版本数={}",
                meta.getKey(), entry.versionId(), meta.getVersions().size());
        return new PutResult(meta.getKey(), entry.versionId(), 0, null, entry.createdMillis());
    }

    private void appendEntry(String key, VersionEntry entry) throws IOException {
        ObjectMeta meta = metadataStore.load(key).orElseGet(() -> new ObjectMeta(key));
        meta.getVersions().add(entry);
        metadataStore.save(meta);
    }

    private VersionEntry latest(ObjectMeta meta) {
        return meta.getVersions().get(meta.getVersions().size() - 1);
    }

    private void checkReadable(VersionEntry entry, String key) {
        if (entry.deleteMarker()) {
            throw new StorageException(ErrorCode.OBJECT_DELETED,
                    "该版本是删除标记: key=" + key + ", versionId=" + entry.versionId());
        }
        if (entry.blobReclaimed()) {
            throw new StorageException(ErrorCode.VERSION_RECLAIMED,
                    "该版本内容已被回收: key=" + key + ", versionId=" + entry.versionId());
        }
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new StorageException(ErrorCode.BAD_REQUEST, "对象键不能为空");
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 最佳努力
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String toHex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
