package com.lq.deepseek.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * JD 分析会话。
 *
 * <p>一个会话 = 一份 JD 的一次持续分析主题。把 JD 原文挂在会话上（{@code jdText}），
 * 用户在简历中心改完简历回来"一键重算"时，不需要再粘贴一次 JD——这是这个表存在的唯一理由。
 */
@Data
@TableName("decision_sessions")
public class DecisionSession implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private String title;

    /** OFFER / JD_MATCH / ...，本模块固定 JD_MATCH */
    private String scene;

    /** CREATED / RUNNING / FINISHED / FAILED */
    private String status;

    private Long snapshotId;

    /** 网关生成的字符串 run_id（"run_<snowflake>"），与 agent_runs.run_id 对齐，非数字主键。 */
    private String latestRunId;

    private Long latestAnalysisId;

    private Long resumeAssetId;

    private Long jdAssetId;

    private String jobTitle;

    private String jdText;

    private Integer latestScore;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    private Integer deleted;
}
