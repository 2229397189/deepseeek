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
 * 用户长期记忆（来自决策/面试产出的可确认记忆条目）。
 *
 * <p>状态机：PENDING（待确认） → CONFIRMED（已确认）/ CORRECTED（已修正）。
 * 用户可在画像页确认或修正，确认后的记忆进入长期记忆库，驱动后续个性化。
 */
@Data
@TableName("long_term_memory")
public class LongTermMemory implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private String content;

    private String status;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
