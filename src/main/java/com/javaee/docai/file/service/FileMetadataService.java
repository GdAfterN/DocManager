package com.javaee.docai.file.service;

import com.javaee.docai.common.model.PageResult;
import com.javaee.docai.file.entity.FileMetadata;
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
