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
 * JD 分析会话消息（分析结论 / 用户追问 / AI 回答）。
 *
 * <p>{@code seq} 由服务层在事务内取 MAX(seq)+1 并受 uk_decision_msg_seq 兜底：
 * 并发下宁可直接失败重试，也不能出现两条 seq 相同的消息导致前端顺序错乱。
 */
@Data
@TableName("decision_messages")
public class DecisionMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long sessionId;

    private Integer seq;

    /** SYSTEM / USER / ASSISTANT */
    private String role;

    private String content;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
