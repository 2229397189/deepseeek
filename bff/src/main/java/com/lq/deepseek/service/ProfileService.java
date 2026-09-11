package com.lq.deepseek.service;

import com.lq.deepseek.dto.ProfileDtos;

import java.util.List;

/**
 * 用户画像 / 长期记忆服务。
 */
public interface ProfileService {

    /** 聚合能力标签（简历技能 + 决策分析 + 面试报告）。 */
    List<ProfileDtos.CapabilityTag> tags(Long userId);

    /** 长期记忆列表（首次为空时由决策产出播种 PENDING 记忆）。 */
    List<ProfileDtos.LongTermMemory> memories(Long userId);

    /** 确认一条记忆。 */
    void confirmMemory(Long userId, Long memoryId);

    /** 修正一条记忆的内容。 */
    void correctMemory(Long userId, Long memoryId, String correctedContent);
}
