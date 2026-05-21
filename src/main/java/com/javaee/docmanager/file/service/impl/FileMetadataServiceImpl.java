package com.javaee.docmanager.file.service.impl;

import com.javaee.docmanager.common.model.PageResult;
import com.javaee.docmanager.file.entity.FileMetadata;
import com.javaee.docmanager.file.mapper.FileMetadataMapper;
import com.javaee.docmanager.file.service.FileMetadataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class FileMetadataServiceImpl implements FileMetadataService {

    @Autowired
    private FileMetadataMapper fileMetadataMapper;

    @Override
    public FileMetadata getMetadata(String fileId) {
        try {
            return fileMetadataMapper.selectByFileId(fileId);
        } catch (Exception e) {
            System.out.println("获取元数据失败: " + e.getMessage());
            return null;
        }
    }

    @Override
    public void saveMetadata(FileMetadata fileMetadata) {
        try {
            fileMetadata.setCreateTime(LocalDateTime.now());
            fileMetadata.setUpdateTime(LocalDateTime.now());
            fileMetadata.setStatus("ACTIVE");
            fileMetadataMapper.insert(fileMetadata);
        } catch (Exception e) {
            System.out.println("保存元数据失败: " + e.getMessage());
        }
    }

    @Override
    public void updateMetadata(FileMetadata fileMetadata) {
        try {
            fileMetadata.setUpdateTime(LocalDateTime.now());
            fileMetadataMapper.updateByFileId(fileMetadata);
        } catch (Exception e) {
            System.out.println("更新元数据失败: " + e.getMessage());
        }
    }

    @Override
    public void deleteMetadata(String fileId) {
        try {
            fileMetadataMapper.deleteByFileId(fileId);
        } catch (Exception e) {
            System.out.println("删除元数据失败: " + e.getMessage());
        }
    }

    @Override
    public PageResult<FileMetadata> getFileList(int page, int size, String sortBy, String direction) {
        try {
            int offset = (page - 1) * size;
            String dbColumn = (sortBy != null && !sortBy.isEmpty()) ? camelToSnake(sortBy) : "create_time";
            String dir = "asc".equals(direction) ? "asc" : "desc";
            List<FileMetadata> list = fileMetadataMapper.selectList(offset, size, dbColumn, dir);
            int total = fileMetadataMapper.countAll();
            return PageResult.of(list, total, page, size);
        } catch (Exception e) {
            System.out.println("获取文件列表失败: " + e.getMessage());
            return PageResult.of(new ArrayList<>(), 0, page, size);
        }
    }

    private String camelToSnake(String str) {
        return str.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    @Override
    public PageResult<FileMetadata> searchFiles(String keyword, int page, int size) {
        try {
            int offset = (page - 1) * size;
            List<FileMetadata> list = fileMetadataMapper.searchByKeyword(keyword, offset, size);
            int total = fileMetadataMapper.countByKeyword(keyword);
            return PageResult.of(list, total, page, size);
        } catch (Exception e) {
            System.out.println("搜索文件失败: " + e.getMessage());
            return PageResult.of(new ArrayList<>(), 0, page, size);
        }
    }

    @Override
    public PageResult<FileMetadata> getFileListByType(String fileType, int page, int size, String sortBy, String direction) {
        try {
            int offset = (page - 1) * size;
            String dbColumn = (sortBy != null && !sortBy.isEmpty()) ? camelToSnake(sortBy) : "create_time";
            String dir = "asc".equals(direction) ? "asc" : "desc";
            List<FileMetadata> list = fileMetadataMapper.selectByFileType(fileType, offset, size, dbColumn, dir);
            int total = fileMetadataMapper.countByFileType(fileType);
            return PageResult.of(list, total, page, size);
        } catch (Exception e) {
            System.out.println("按类型获取文件列表失败: " + e.getMessage());
            return PageResult.of(new ArrayList<>(), 0, page, size);
        }
    }

    @Override
    public Object getDirectoryStructure(String path) {
        try {
            Map<String, Object> structure = new HashMap<>();
            List<Map<String, Object>> files = new ArrayList<>();
            List<Map<String, Object>> directories = new ArrayList<>();

            Map<String, Object> dir1 = new HashMap<>();
            dir1.put("name", "documents");
            dir1.put("type", "directory");
            dir1.put("path", path + (path.endsWith("/") ? "" : "/") + "documents");
            directories.add(dir1);

            Map<String, Object> dir2 = new HashMap<>();
            dir2.put("name", "images");
            dir2.put("type", "directory");
            dir2.put("path", path + (path.endsWith("/") ? "" : "/") + "images");
            directories.add(dir2);

            structure.put("directories", directories);
            structure.put("files", files);
            structure.put("currentPath", path);

            return structure;
        } catch (Exception e) {
            System.out.println("获取目录结构失败: " + e.getMessage());
            Map<String, Object> structure = new HashMap<>();
            structure.put("directories", new ArrayList<>());
            structure.put("files", new ArrayList<>());
            structure.put("currentPath", path);
            return structure;
        }
    }
}
