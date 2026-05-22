package com.javaee.docmanager.doc.mapper;

import com.javaee.docmanager.doc.entity.DocumentFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DocumentFileMapper {
    int insert(DocumentFile documentFile);
    DocumentFile selectById(@Param("id") String id);
    List<DocumentFile> selectAll();
    List<DocumentFile> selectAllMainBranches();
    int updateCurrentFile(@Param("id") String id, @Param("fileId") String fileId,
                          @Param("version") String version, @Param("updateTime") String updateTime);
    int deleteById(@Param("id") String id);
}
