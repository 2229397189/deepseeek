package com.lq.deepseek.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 知识图谱节点。
 *
 * <p>id 为业务键（如 {@code resume:123}、{@code skill:Java}），前端与 graph_edge 据此连线；
 * 图谱在查询时由服务层动态推导，本表作为落库形态与来源去重使用。
 */
@Data
@TableName("graph_node")
public class GraphNode implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务键（非雪花主键），由应用侧写入 */
    @TableId(type = IdType.INPUT)
    private String id;

    private String label;

    /** CANDIDATE / RESUME / JOB / INTERVIEW / SKILL / PROJECT / POSITION */
    private String type;

    /** WEAK / NORMAL，可能为空 */
    private String status;

    private Integer layer;

    private OffsetDateTime createdAt;
}
