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
 * 简历正文版本快照。
 *
 * <p>每次保存正文落一条 version 记录，version_no 递增。
 * 回滚 = 用历史 version 的 body 创建新 version。
 */
@Data
@TableName("resume_version")
public class ResumeVersion implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long assetId;

    private Long userId;

    private Integer versionNo;

    private String body;

    private String changeDesc;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
