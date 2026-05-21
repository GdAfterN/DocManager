package com.javaee.docmanager.file.service;

import com.javaee.docmanager.common.model.PageResult;
import com.javaee.docmanager.file.entity.FileMetadata;
import java.util.List;

public interface FileMetadataService {

    FileMetadata getMetadata(String fileId);

    void saveMetadata(FileMetadata fileMetadata);

    void updateMetadata(FileMetadata fileMetadata);

    void deleteMetadata(String fileId);

    PageResult<FileMetadata> getFileList(int page, int size, String sortBy, String direction);

    PageResult<FileMetadata> searchFiles(String keyword, int page, int size);

    PageResult<FileMetadata> getFileListByType(String fileType, int page, int size, String sortBy, String direction);

    Object getDirectoryStructure(String path);

} 
