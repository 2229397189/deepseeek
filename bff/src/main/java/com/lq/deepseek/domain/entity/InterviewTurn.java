package com.lq.deepseek.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 面试轮次消息（出题 / 作答 / 系统提示）。
 *
 * <p>seq 由服务层取 MAX(seq)+1 并受 uk_turn_session_seq 兜底，保证顺序幂等。
 */
@Data
@TableName(value = "interview_turns", autoResultMap = true)
public class InterviewTurn implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long sessionId;

    private Integer seq;

    /** INTERVIEWER / CANDIDATE / SYSTEM */
    private String role;

    private String stage;

    private String content;

    /** 本轮评分（可选，由 agent 在 ANSWER/FINISH 阶段回填） */
    private BigDecimal score;

    /** 本题考察的技能（用于 questionPlan 推进与终评聚合；P0-6 新增） */
    private String skill;

    /** 逐题评估元数据：questionIndex / isFollowUp / matchedKeywords / missingKeywords（P0-6 新增） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> evalMeta;

    private Integer latencyMs;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> tokenUsage;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
