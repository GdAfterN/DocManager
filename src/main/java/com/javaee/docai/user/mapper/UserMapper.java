package com.javaee.docai.user.mapper;

import com.javaee.docai.user.entity.User;
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

    int deleteById(@Param("id") Long id);
}
