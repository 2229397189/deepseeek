package com.lq.deepseek.gateway.support;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * 输入摘要计算：把「用户 + 业务类型 + 规格指纹 + 业务负载」压成 64 位十六进制串。
 *
 * <p>用途有二：
 * <ol>
 *   <li>作为 single-flight 的去重键，使相同输入的并发请求只会真正调用下游一次；</li>
 *   <li>作为幂等键的一部分，落库到 agent_runs.input_digest，便于事后追溯与对账。</li>
 * </ol>
 * 负载按键名字典序规范化后再序列化，避免 Map 顺序抖动导致同一输入算出不同摘要。
 */
public final class InvokeDigest {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private InvokeDigest() {
    }

    /**
     * @param objectMapper 用于稳定序列化负载
     */
    public static String of(ObjectMapper objectMapper, String bizType, String specHash,
                            Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(bizType == null ? "" : bizType).append('|')
          .append(specHash == null ? "" : specHash).append('|');
        Map<String, Object> normalized = payload == null ? Map.of() : new TreeMap<>(payload);
        try {
            sb.append(objectMapper.writeValueAsString(normalized));
        } catch (Exception e) {
            // 序列化失败时退化为 toString，保证摘要仍可计算（仅影响精度，不影响正确性）
            sb.append(normalized);
        }
        return sha256Hex(sb.toString());
    }

    public static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            char[] out = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                int v = bytes[i] & 0xFF;
                out[i * 2] = HEX[v >>> 4];
                out[i * 2 + 1] = HEX[v & 0x0F];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", e);
        }
    }
}
