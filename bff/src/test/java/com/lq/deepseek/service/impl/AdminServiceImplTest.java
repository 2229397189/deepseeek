package com.lq.deepseek.service.impl;

import com.lq.deepseek.common.BusinessException;
import com.lq.deepseek.common.ErrorCode;
import com.lq.deepseek.domain.entity.ModelConfig;
import com.lq.deepseek.domain.entity.PricingRule;
import com.lq.deepseek.domain.mapper.ModelConfigMapper;
import com.lq.deepseek.domain.mapper.PricingRuleMapper;
import com.lq.deepseek.dto.AdminDtos;
import com.lq.deepseek.service.support.ModelHealthProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 系统管理行为测试。
 *
 * <p>钉死两条：apiKey 读取一律脱敏（禁止明文回传）；更新时未传新密钥不得清空旧密钥。
 */
class AdminServiceImplTest {

    private ModelConfigMapper modelConfigMapper;
    private PricingRuleMapper pricingRuleMapper;
    private ModelHealthProbe healthProbe;
    private AdminServiceImpl service;

    @BeforeEach
    void setUp() {
        modelConfigMapper = mock(ModelConfigMapper.class);
        pricingRuleMapper = mock(PricingRuleMapper.class);
        healthProbe = mock(ModelHealthProbe.class);
        service = new AdminServiceImpl(modelConfigMapper, pricingRuleMapper, healthProbe);

        when(modelConfigMapper.insert(any(ModelConfig.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ModelConfig.class).setId(2001L);
            return 1;
        });
        when(modelConfigMapper.updateById(any(ModelConfig.class))).thenReturn(1);
    }

    @Test
    void createModel_masksApiKeyOnRead() {
        AdminDtos.ModelConfigRequest request = new AdminDtos.ModelConfigRequest();
        request.setName("DeepSeek Chat");
        request.setProvider("deepseek");
        request.setBaseUrl("https://api.deepseek.com");
        request.setModel("deepseek-chat");
        request.setApiKey("sk-abcdef1234");

        AdminDtos.ModelConfig vo = service.createModel(request);

        assertThat(vo.getId()).isEqualTo(2001L);
        assertThat(vo.getModel()).isEqualTo("deepseek-chat");
        // 明文密钥绝不能回传：只保留尾部 4 位
        assertThat(vo.getApiKey()).isEqualTo("****1234");
        assertThat(vo.getApiKey()).doesNotContain("abcdef");
        assertThat(vo.getEnabled()).isTrue();
    }

    @Test
    void createModel_persistsRawApiKey() {
        AdminDtos.ModelConfigRequest request = new AdminDtos.ModelConfigRequest();
        request.setName("DeepSeek");
        request.setProvider("deepseek");
        request.setModel("deepseek-chat");
        request.setApiKey("sk-raw-value-9999");

        service.createModel(request);

        ArgumentCaptor<ModelConfig> captor = ArgumentCaptor.forClass(ModelConfig.class);
        verify(modelConfigMapper).insert(captor.capture());
        // 落库保留原文（脱敏只在读取侧）
        assertThat(captor.getValue().getApiKey()).isEqualTo("sk-raw-value-9999");
    }

    @Test
    void updateModel_keepsApiKeyWhenBlank() {
        ModelConfig existing = model(2001L, "sk-keep9999");
        when(modelConfigMapper.selectById(2001L)).thenReturn(existing);

        AdminDtos.ModelConfigRequest request = new AdminDtos.ModelConfigRequest();
        request.setName("DeepSeek Chat v2");
        request.setProvider("deepseek");
        request.setModel("deepseek-chat");
        // 未传 apiKey：不得清空既有密钥

        AdminDtos.ModelConfig vo = service.updateModel(2001L, request);

        assertThat(vo.getName()).isEqualTo("DeepSeek Chat v2");
        assertThat(vo.getApiKey()).isEqualTo("****9999");
    }

    @Test
    void updateModel_missingConfig_isRejected() {
        when(modelConfigMapper.selectById(anyLong())).thenReturn(null);

        assertThatThrownBy(() -> service.updateModel(404L, new AdminDtos.ModelConfigRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void testModel_returnsProbeResult() {
        when(modelConfigMapper.selectById(2001L)).thenReturn(model(2001L, "sk-x"));
        when(healthProbe.ping(anyString())).thenReturn(new ModelHealthProbe.ProbeResult(true, 12L, null));

        AdminDtos.ModelTestResult result = service.testModel(2001L);

        assertThat(result.getOk()).isTrue();
        assertThat(result.getLatencyMs()).isEqualTo(12L);
        assertThat(result.getError()).isNull();
    }

    @Test
    void testModel_withoutBaseUrl_isNoopSuccess() {
        ModelConfig noUrl = model(2001L, "sk-x");
        noUrl.setBaseUrl(null);
        when(modelConfigMapper.selectById(2001L)).thenReturn(noUrl);

        AdminDtos.ModelTestResult result = service.testModel(2001L);

        assertThat(result.getOk()).isTrue();
        verify(healthProbe, org.mockito.Mockito.never()).ping(anyString());
    }

    @Test
    void listPricing_returnsRules() {
        PricingRule rule = new PricingRule();
        rule.setId(910001L);
        rule.setBizType("DECIDE");
        rule.setUnitCredit(12L);
        rule.setDescription("JD 正式匹配分析");
        when(pricingRuleMapper.selectList(any())).thenReturn(List.of(rule));

        List<AdminDtos.PricingRule> rules = service.listPricing();

        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).getBizType()).isEqualTo("DECIDE");
        assertThat(rules.get(0).getUnitCredit()).isEqualTo(12L);
    }

    @Test
    void deleteModel_missingConfig_isRejected() {
        when(modelConfigMapper.selectById(anyLong())).thenReturn(null);

        assertThatThrownBy(() -> service.deleteModel(404L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ------------------------------------------------------------------

    private static ModelConfig model(Long id, String apiKey) {
        ModelConfig config = new ModelConfig();
        config.setId(id);
        config.setName("DeepSeek Chat");
        config.setProvider("deepseek");
        config.setBaseUrl("https://api.deepseek.com");
        config.setModel("deepseek-chat");
        config.setApiKey(apiKey);
        config.setEnabled(true);
        return config;
    }
}
