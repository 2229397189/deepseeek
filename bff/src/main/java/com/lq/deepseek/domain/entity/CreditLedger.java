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
 * 额度流水。每次变动都落一条不可变明细，idempotency_key 保证重复请求不会二次扣费。
 */
@Data
@TableName("credit_ledger")
public class CreditLedger implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long walletId;

    /** RECHARGE / GRANT / CONSUME / REFUND / FREEZE / UNFREEZE */
    private String changeType;

    /** 正数入账、负数出账 */
    private Long amount;

    private Long balanceAfter;

    private String bizType;

    private Long bizId;

    private String runId;

    /** 幂等键，唯一索引兜底 */
    private String idempotencyKey;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
