package com.lq.deepseek.dto;

import lombok.Data;

import java.util.List;

/**
 * 模型可用列表 DTO 容器。
 */
public final class ModelsDtos {

    private ModelsDtos() {
    }

    /** 单个可用模型。 */
    @Data
    public static class AvailableModel {
        private String id;
        private String label;
        private String provider;
    }

    /** /models/available 回包。 */
    @Data
    public static class AvailableModels {
        private List<AvailableModel> models;
        private String defaultModel;
    }
}
