package com.lq.deepseek.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lq.deepseek.domain.entity.FileAsset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FileAssetMapper extends BaseMapper<FileAsset> {

    /**
     * 同一用户 + 同一业务中心下按内容指纹取资产，用于"重复上传不重复解析"判定。
     *
     * <p>必须带 bizType：简历画像与 JD 全文是两套结构，若跨中心复用同一行，
     * 后一次解析会覆盖前一次的 parse_result。
     */
    @Select("""
            SELECT *
              FROM file_assets
             WHERE user_id = #{userId}
               AND biz_type = #{bizType}
               AND sha256 = #{sha256}
               AND deleted = 0
             LIMIT 1
            """)
    FileAsset selectActiveBySha(@Param("userId") Long userId,
                                @Param("sha256") String sha256,
                                @Param("bizType") String bizType);

    /**
     * 推进解析状态。parse_result 以字符串传入并显式转 jsonb，
     * 避免驱动把 jsonb 列当 varchar 处理（与 agent_runs.finish 同一处理方式）。
     */
    @Update("""
            UPDATE file_assets
               SET parse_status = #{parseStatus},
                   parse_result = #{parseResultJson}::jsonb,
                   error_msg    = #{errorMsg},
                   updated_at   = now()
             WHERE id = #{id}
            """)
    int updateParseState(@Param("id") Long id,
                         @Param("parseStatus") String parseStatus,
                         @Param("parseResultJson") String parseResultJson,
                         @Param("errorMsg") String errorMsg);
}
