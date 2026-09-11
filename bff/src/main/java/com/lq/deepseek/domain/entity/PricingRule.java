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
 * 计费规则（各业务类型的单次调用基准额度）。
 */
@Data
@TableName("pricing_rule")
public class PricingRule implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String bizType;

    private Long unitCredit;

    private String description;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
