package com.lq.deepseek.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * JD 匹配分析明细。
 *
 * <p>字段按"前端要分几块展示"来切：分数看板（score/dimensions）、缺口（missingSkills/weakPointsHit）、
 * 可执行建议（advice/todos）、风控提示（risks/hardRequirements）。
 * 保留 {@code runId} 是为了让界面上那个分数永远能追到确切的 AI 调用记录。
 */
@Data
@TableName(value = "decision_analyses", autoResultMap = true)
public class DecisionAnalysis implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long sessionId;

    private Long userId;

    /** FULL / PREVIEW */
    private String mode;

    private String runId;

    private Long resumeAssetId;

    private Long jdAssetId;

    private String jdExcerpt;

    private Integer score;

    private String scoreBand;

    private String conclusion;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> dimensions;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> requiredSkills;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> coveredSkills;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> missingSkills;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> weakPointsHit;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> hardRequirements;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> risks;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> todos;

    private String advice;

    private Long costCredit;

    private Integer latencyMs;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
