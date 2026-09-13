package com.adarosatas.vocabtrim.user;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    @Select("""
            SELECT id, username, password_hash, enabled, created_at, updated_at
            FROM users
            WHERE username = #{username}
            LIMIT 1
            """)
    User findByUsername(@Param("username") String username);

    @Select("""
            SELECT id, username, password_hash, enabled, created_at, updated_at
            FROM users
            WHERE id = #{id}
            LIMIT 1
            """)
    User findById(@Param("id") long id);

    @Select("SELECT id FROM users WHERE id = #{id} FOR UPDATE")
    Long lockById(@Param("id") long id);

    @Insert("""
            INSERT INTO users (username, password_hash, enabled)
            VALUES (#{username}, #{passwordHash}, #{enabled})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);
}
