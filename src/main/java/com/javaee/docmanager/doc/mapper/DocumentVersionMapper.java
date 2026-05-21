package com.javaee.docmanager.doc.mapper;

import com.javaee.docmanager.doc.entity.DocumentVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DocumentVersionMapper {

    int insert(DocumentVersion version);

    List<DocumentVersion> selectByDocumentId(@Param("documentId") String documentId);

    DocumentVersion selectLatestVersion(@Param("documentId") String documentId);

    Integer selectMaxVersionNumber(@Param("documentId") String documentId);

    DocumentVersion selectByDocumentIdAndVersion(@Param("documentId") String documentId, @Param("versionNumber") Integer versionNumber);
}
