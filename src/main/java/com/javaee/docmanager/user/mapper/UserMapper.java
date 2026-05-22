package com.javaee.docmanager.user.mapper;

import com.javaee.docmanager.user.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {

    User selectByUsername(@Param("username") String username);

    User selectByEmail(@Param("email") String email);

    User selectByPhone(@Param("phone") String phone);

    User selectById(@Param("id") Long id);

    int insert(User user);

    int updateById(User user);

    int incrementField(@Param("id") Long id, @Param("field") String field, @Param("delta") long delta);

    int batchUpdateCounters(@Param("id") Long id,
                            @Param("ragTokensInput") long ragTokensInput,
                            @Param("ragTokensOutput") long ragTokensOutput,
                            @Param("pptTokensInput") long pptTokensInput,
                            @Param("pptTokensOutput") long pptTokensOutput,
                            @Param("ragDocCount") long ragDocCount,
                            @Param("ragSliceCount") long ragSliceCount,
                            @Param("pptCount") long pptCount);

    int resetCounters(@Param("id") Long id);

    int deleteById(@Param("id") Long id);
}
