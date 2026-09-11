package com.lq.deepseek.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 面试终态报告（每场面试结束后落一份，分数永远可追溯到具体 AI 调用）。
 */
@Data
@TableName(value = "interview_reports", autoResultMap = true)
public class InterviewReport implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long sessionId;

    private Integer score;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> dimensions;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> weakPoints;

    private String suggestion;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
