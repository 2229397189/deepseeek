package com.lq.deepseek.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lq.deepseek.domain.entity.InviteCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InviteCodeMapper extends BaseMapper<InviteCode> {

    @Select("SELECT * FROM invite_codes WHERE code = #{code} LIMIT 1")
    InviteCode selectByCode(String code);

    /**
     * 原子占用一次使用次数：并发下只有一个请求能把 used_count 从 n 推到 n+1。
     */
    @Update("""
            UPDATE invite_codes
               SET used_count = used_count + 1,
                   status     = CASE WHEN used_count + 1 >= max_uses THEN 'EXHAUSTED' ELSE status END,
                   updated_at = now()
             WHERE id = #{id}
               AND status = 'ACTIVE'
               AND used_count < max_uses
            """)
    int consumeOnce(@Param("id") Long id);
}
