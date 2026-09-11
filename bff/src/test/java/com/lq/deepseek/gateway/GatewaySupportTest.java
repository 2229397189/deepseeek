package com.lq.deepseek.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lq.deepseek.gateway.support.InvokeDigest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计量换算与输入摘要的单元验证。
 *
 * <p>摘要稳定性直接决定 single-flight 能否命中：只要 Map 顺序不同就产生不同摘要，
 * 去重就会失效，因此这里把"键序无关"作为硬断言。
 */
class GatewaySupportTest {

    private final AiCostCalculator calculator = new AiCostCalculator();

    @Test
    @DisplayName("token 换算：输入 1K=1、输出 1K=3，且最低计 1")
    void shouldCalculateCost() {
        assertEquals(4L, calculator.cost(1000, 1000, 0));
        assertEquals(1L, calculator.cost(10, 0, 0), "只要真实调用，最低计 1 credit");
        assertEquals(0L, calculator.cost(0, 0, 0), "未产生任何 token 视为未调用");
        assertEquals(99L, calculator.cost(1000, 1000, 99L), "下游显式定价优先");
    }

    @Test
    @DisplayName("输入摘要：与键序无关、与用户相关")
    void shouldDigestStably() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("userId", 1L);
        first.put("jd", "Java 后端");
        first.put("resume", "三年经验");

        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("resume", "三年经验");
        reordered.put("jd", "Java 后端");
        reordered.put("userId", 1L);

        String digest1 = InvokeDigest.of(mapper, "RESUME_PARSE", "deepseek-chat:v1", first);
        String digest2 = InvokeDigest.of(mapper, "RESUME_PARSE", "deepseek-chat:v1", reordered);
        assertEquals(digest1, digest2, "同一输入的不同键序必须算出相同摘要");

        Map<String, Object> otherUser = new LinkedHashMap<>(first);
        otherUser.put("userId", 2L);
        assertNotEquals(digest1, InvokeDigest.of(mapper, "RESUME_PARSE", "deepseek-chat:v1", otherUser),
                "不同用户不得共用回放结果");

        assertNotEquals(digest1, InvokeDigest.of(mapper, "RESUME_PARSE", "deepseek-chat:v2", first),
                "规格指纹变化必须重新调用");
        assertEquals(64, digest1.length());
        assertTrue(digest1.matches("[0-9a-f]{64}"));
    }
}
