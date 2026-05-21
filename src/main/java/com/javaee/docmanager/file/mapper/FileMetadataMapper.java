package com.javaee.docmanager.file.mapper;

import com.javaee.docmanager.file.entity.FileMetadata;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface FileMetadataMapper {

    int insert(FileMetadata fileMetadata);

    FileMetadata selectById(@Param("id") String id);

    FileMetadata selectByFileId(@Param("fileId") String fileId);

    int updateById(FileMetadata fileMetadata);

    int updateByFileId(FileMetadata fileMetadata);

    int deleteByFileId(@Param("fileId") String fileId);

    List<FileMetadata> selectList(@Param("offset") int offset, @Param("size") int size, @Param("orderBy") String orderBy, @Param("direction") String direction);

    List<FileMetadata> searchByKeyword(@Param("keyword") String keyword, @Param("offset") int offset, @Param("size") int size);

    int countAll();

    int countByKeyword(@Param("keyword") String keyword);

    List<FileMetadata> selectByFileType(@Param("fileType") String fileType, @Param("offset") int offset, @Param("size") int size, @Param("orderBy") String orderBy, @Param("direction") String direction);

    int countByFileType(@Param("fileType") String fileType);
}
