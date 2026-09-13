package com.lq.deepseek.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 用户。
 */
@Data
@TableName("users")
public class User implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String username;

    private String email;

    /** BCrypt 摘要，任何出口都不允许序列化 */
    @JsonIgnore
    private String passwordHash;

    private String nickname;

    private String avatarUrl;

    /** 1=正常 0=禁用 */
    private Integer status;

    /** user 普通用户 / admin 管理员（/admin/** 需要 admin） */
    private String role;

    private OffsetDateTime lastLoginAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    @JsonIgnore
    private Integer deleted;

    public boolean isEnabled() {
        return status != null && status == 1;
    }
}
