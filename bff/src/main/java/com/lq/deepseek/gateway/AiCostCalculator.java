package com.lq.deepseek.gateway;

import org.springframework.stereotype.Component;

/**
 * 调用成本换算：把 token 用量折算为平台 credit。
 *
 * <p>换算规则（1 credit ≈ 1 分成本口径）：
 * <ul>
 *   <li>输入 token：每 1K 计 1 credit</li>
 *   <li>输出 token：每 1K 计 3 credit（输出侧推理成本显著更高）</li>
 *   <li>只要产生了实际调用，最低计 1 credit，避免免费刷接口</li>
 *   <li>下游显式回传 costCredit 时以其为准（支持不同模型差异定价）</li>
 * </ul>
 */
@Component
public class AiCostCalculator {

    private static final long PROMPT_CREDIT_PER_1K = 1L;
    private static final long OUTPUT_CREDIT_PER_1K = 3L;
    private static final long MIN_COST_CREDIT = 1L;

    public long cost(int promptTokens, int outputTokens, long explicitCostCredit) {
        if (explicitCostCredit > 0) {
            return explicitCostCredit;
        }
        int prompt = Math.max(promptTokens, 0);
        int output = Math.max(outputTokens, 0);
        if (prompt == 0 && output == 0) {
            return 0L;
        }
        long cost = ceilDiv(prompt * PROMPT_CREDIT_PER_1K, 1000L)
                + ceilDiv(output * OUTPUT_CREDIT_PER_1K, 1000L);
        return Math.max(cost, MIN_COST_CREDIT);
    }

    private long ceilDiv(long dividend, long divisor) {
        return (dividend + divisor - 1) / divisor;
    }
}
