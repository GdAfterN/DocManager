package com.javaee.docmanager.doc.mapper;

import com.javaee.docmanager.doc.entity.DocumentFileVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DocumentFileVersionMapper {
    int insert(DocumentFileVersion version);
    List<DocumentFileVersion> selectByDocumentId(@Param("documentId") String documentId);
    List<DocumentFileVersion> selectByBranchId(@Param("branchId") String branchId);
    DocumentFileVersion selectById(@Param("id") String id);
    int deleteOldestVersion(@Param("documentId") String documentId);
    int deleteOldestVersionByBranch(@Param("branchId") String branchId);
    int countByDocumentId(@Param("documentId") String documentId);
    int countByBranchId(@Param("branchId") String branchId);
}
