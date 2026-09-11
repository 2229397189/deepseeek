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
 * 能力标签（聚合自简历技能 + 决策分析 + 面试报告）。
 */
@Data
@TableName("capability_tag")
public class CapabilityTag implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** 标签名（如 Java、高并发） */
    private String tag;

    /** SKILL / STRENGTH / GAP / WEAK */
    private String category;

    /** 掌握层级（高 / 中 / 低），可能为空 */
    private String level;

    /** RESUME / DECISION / INTERVIEW */
    private String source;

    /** 置信度 0~1 */
    private Double confidence;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
