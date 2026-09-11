package com.lq.deepseek.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lq.deepseek.domain.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Select("SELECT * FROM users WHERE username = #{username} AND deleted = 0 LIMIT 1")
    User selectByUsername(String username);

    @Select("SELECT * FROM users WHERE email = #{email} AND deleted = 0 LIMIT 1")
    User selectByEmail(String email);
}
