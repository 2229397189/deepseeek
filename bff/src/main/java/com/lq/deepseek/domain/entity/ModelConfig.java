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
 * 模型配置（多模型接入：DeepSeek / OpenAI 兼容）。
 *
 * <p>api_key 为敏感字段，落库原文，但所有读取接口一律脱敏（仅保留尾部掩码），且禁止写日志。
 */
@Data
@TableName("model_config")
public class ModelConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    private String provider;

    private String baseUrl;

    private String model;

    private String apiKey;

    private Boolean enabled;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
