package com.javaee.docmanager.doc.mapper;

import com.javaee.docmanager.doc.entity.Document;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DocumentMapper {

    int insert(Document document);

    Document selectById(@Param("id") String id);

    int updateById(Document document);

    int deleteById(@Param("id") String id);

    List<Document> selectByUserId(@Param("userId") Long userId);

    List<Document> selectByCategory(@Param("category") String category);

    List<Document> searchByKeyword(@Param("keyword") String keyword);

    List<Document> selectByStatus(@Param("status") String status);
}
