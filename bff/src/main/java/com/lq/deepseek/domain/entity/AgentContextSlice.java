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
import java.util.Map;

/**
 * Agent Context Slice：快照内的一个证据切片。
 *
 * <p>切片分型（resume / jd / analysis / retrieval / memory 等）并带证据等级
 * （HIGH = 用户真实输入，MEDIUM = 历史结论，LOW = 检索召回），预算紧张时低等级优先裁剪。
 */
@Data
@TableName(value = "agent_context_slices", autoResultMap = true)
public class AgentContextSlice implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long snapshotId;

    /** resume / jd / analysis / retrieval / memory */
    private String sliceType;

    /** HIGH / MEDIUM / LOW */
    private String evidenceLevel;

    private String content;

    private Integer tokenCount;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
