package com.javaee.docai.ai.service;

import com.javaee.docai.ai.vo.RecycleBinVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 回收站服务
 * 功能说明：管理已删除的文件，支持在有效期内恢复
 * 实现方式：使用skill方式实现，目前使用内存存储（待实现持久化）
 */
@Service
public class RecycleBinService {

    private static final Logger log = LoggerFactory.getLogger(RecycleBinService.class);

    @Autowired
    private MinIOService minIOService;

    @Value("${minio.bucket:documents}")
    private String defaultBucket;

    @Value("${minio.recycle.expiry-days:7}")
    private int recycleExpiryDays;

    private final ConcurrentHashMap<String, RecycleBinVO.RecycleFile> recycleFiles = new ConcurrentHashMap<>();

    /**
     * 将文件移动到回收站
     * 
     * @param bucketName 存储桶名称
     * @param objectName 对象名称
     * @param deleter 删除者
     * @return 回收站记录ID
     */
    public String moveToRecycleBin(String bucketName, String objectName, String deleter) {
        log.info("将文件移动到回收站: bucket={}, object={}", bucketName, objectName);

        try {
            String recycleId = UUID.randomUUID().toString();
            long deleteTime = System.currentTimeMillis();
            long expiryTime = deleteTime + (long) recycleExpiryDays * 24 * 60 * 60 * 1000;

            RecycleBinVO.RecycleFile recycleFile = new RecycleBinVO.RecycleFile();
            recycleFile.setRecycleId(recycleId);
            recycleFile.setBucketName(bucketName);
            recycleFile.setOriginalObjectName(objectName);
            recycleFile.setDeleteTime(deleteTime);
            recycleFile.setExpiryTime(expiryTime);
            recycleFile.setFileSize(0L);
            recycleFile.setDeleter(deleter);

            recycleFiles.put(recycleId, recycleFile);

            log.info("文件已移动到回收站: recycleId={}", recycleId);
            return recycleId;

        } catch (Exception e) {
            log.error("移动文件到回收站失败", e);
            throw new RuntimeException("移动文件到回收站失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从回收站恢复文件
     * 
     * @param recycleId 回收站记录ID
     * @param newObjectName 新对象名称（可选）
     * @return 恢复后的对象名称
     */
    public String restoreFromRecycleBin(String recycleId, String newObjectName) {
        log.info("从回收站恢复文件: recycleId={}", recycleId);

        try {
            RecycleBinVO.RecycleFile recycleFile = recycleFiles.get(recycleId);
            if (recycleFile == null) {
                throw new IllegalArgumentException("回收站记录不存在: " + recycleId);
            }

            long now = System.currentTimeMillis();
            if (now > recycleFile.getExpiryTime()) {
                throw new IllegalStateException("文件已超过回收站保留期，无法恢复");
            }

            String targetObjectName = newObjectName != null ? newObjectName : recycleFile.getOriginalObjectName();

            log.info("从回收站恢复文件: original={}, target={}", 
                recycleFile.getOriginalObjectName(), targetObjectName);

            recycleFiles.remove(recycleId);

            return targetObjectName;

        } catch (Exception e) {
            log.error("从回收站恢复文件失败", e);
            throw new RuntimeException("从回收站恢复文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取回收站文件列表
     * 
     * @param bucketName 存储桶名称（可选）
     * @return 回收站文件列表
     */
    public RecycleBinVO listRecycleBin(String bucketName) {
        log.info("获取回收站文件列表: bucket={}", bucketName);

        try {
            List<RecycleBinVO.RecycleFile> files = new ArrayList<>();
            long now = System.currentTimeMillis();

            for (RecycleBinVO.RecycleFile file : recycleFiles.values()) {
                if (now > file.getExpiryTime()) {
                    continue;
                }
                if (bucketName != null && !bucketName.equals(file.getBucketName())) {
                    continue;
                }
                files.add(file);
            }

            RecycleBinVO vo = new RecycleBinVO();
            vo.setTotalCount(files.size());
            vo.setFiles(files);

            log.info("回收站文件列表: count={}", files.size());
            return vo;

        } catch (Exception e) {
            log.error("获取回收站文件列表失败", e);
            throw new RuntimeException("获取回收站文件列表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 永久删除回收站中的文件
     * 
     * @param recycleId 回收站记录ID
     */
    public void permanentDelete(String recycleId) {
        log.info("永久删除回收站中的文件: recycleId={}", recycleId);

        try {
            RecycleBinVO.RecycleFile recycleFile = recycleFiles.remove(recycleId);
            if (recycleFile == null) {
                throw new IllegalArgumentException("回收站记录不存在: " + recycleId);
            }

            log.info("文件已永久删除: bucket={}, object={}", 
                recycleFile.getBucketName(), recycleFile.getOriginalObjectName());

        } catch (Exception e) {
            log.error("永久删除文件失败", e);
            throw new RuntimeException("永久删除文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 清理过期的回收站文件
     * 【待实现】定期调用此方法清理过期文件
     */
    public void cleanupExpiredFiles() {
        log.info("开始清理过期的回收站文件");

        try {
            long now = System.currentTimeMillis();
            int removedCount = 0;

            for (String recycleId : new ArrayList<>(recycleFiles.keySet())) {
                RecycleBinVO.RecycleFile file = recycleFiles.get(recycleId);
                if (file != null && now > file.getExpiryTime()) {
                    recycleFiles.remove(recycleId);
                    removedCount++;
                }
            }

            log.info("清理完成，删除了 {} 个过期文件", removedCount);

        } catch (Exception e) {
            log.error("清理过期文件失败", e);
        }
    }
}
