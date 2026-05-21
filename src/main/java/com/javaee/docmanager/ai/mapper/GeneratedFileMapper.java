package com.javaee.docmanager.ai.mapper;

import com.javaee.docmanager.ai.entity.GeneratedFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface GeneratedFileMapper {
    int insert(GeneratedFile file);
    GeneratedFile selectById(@Param("id") String id);
    List<GeneratedFile> selectRecent(@Param("limit") int limit);
    int deleteById(@Param("id") String id);
}
