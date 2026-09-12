package com.lq.deepseek.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lq.deepseek.domain.entity.ResumeVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ResumeVersionMapper extends BaseMapper<ResumeVersion> {

    @Select("""
            SELECT COALESCE(MAX(version_no), 0)
              FROM resume_version
             WHERE asset_id = #{assetId}
            """)
    int maxVersionNo(@Param("assetId") Long assetId);

    @Select("""
            SELECT *
              FROM resume_version
             WHERE asset_id = #{assetId}
             ORDER BY version_no DESC
            """)
    List<ResumeVersion> listByAsset(@Param("assetId") Long assetId);

    @Select("""
            SELECT *
              FROM resume_version
             WHERE asset_id = #{assetId} AND version_no = #{versionNo}
            """)
    ResumeVersion selectByVersion(@Param("assetId") Long assetId, @Param("versionNo") int versionNo);
}
