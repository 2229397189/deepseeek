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
 * 知识图谱边（source / target 引用 graph_node.id）。
 */
@Data
@TableName("graph_edge")
public class GraphEdge implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String source;

    private String target;

    private String relation;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
