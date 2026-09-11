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
 * 邀请码。used_count 与 max_uses 的边界由 DB 检查约束兜底，防止超发。
 */
@Data
@TableName("invite_codes")
public class InviteCode implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String code;

    private Long inviterUserId;

    private Integer maxUses;

    private Integer usedCount;

    private Long rewardCredit;

    /** ACTIVE / EXHAUSTED / EXPIRED / DISABLED */
    private String status;

    private OffsetDateTime expireAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    public boolean usable() {
        if (!"ACTIVE".equals(status)) {
            return false;
        }
        if (expireAt != null && expireAt.isBefore(OffsetDateTime.now())) {
            return false;
        }
        return usedCount != null && maxUses != null && usedCount < maxUses;
    }
}
