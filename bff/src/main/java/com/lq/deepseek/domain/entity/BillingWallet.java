package com.lq.deepseek.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 计费钱包。额度以整数分值存储，余额与冻结额分列，避免并发下的超发。
 */
@Data
@TableName("billing_wallets")
public class BillingWallet implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** 可用余额 */
    private Long balanceCredit;

    /** 预授权冻结额度 */
    private Long frozenCredit;

    private Long totalGranted;

    private Long totalConsumed;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    /** 可用余额 = 余额 - 冻结 */
    public long available() {
        return (balanceCredit == null ? 0L : balanceCredit) - (frozenCredit == null ? 0L : frozenCredit);
    }
}
