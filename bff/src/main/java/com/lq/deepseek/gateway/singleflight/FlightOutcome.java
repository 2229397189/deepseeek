package com.lq.deepseek.gateway.singleflight;

/**
 * single-flight 一次参与的产出。
 *
 * @param mode        OWNER / REPLAY / LOCAL_OWNER / LOCAL_REPLAY
 * @param payloadJson 结果 JSON（owner 为自身执行结果，replay 为回放结果）
 * @param waitMs      从发起到拿到结果的耗时
 */
public record FlightOutcome(String mode, String payloadJson, long waitMs) {

    public static final String MODE_OWNER = "OWNER";
    public static final String MODE_REPLAY = "REPLAY";
    public static final String MODE_LOCAL_OWNER = "LOCAL_OWNER";
    public static final String MODE_LOCAL_REPLAY = "LOCAL_REPLAY";

    public boolean isOwner() {
        return MODE_OWNER.equals(mode) || MODE_LOCAL_OWNER.equals(mode);
    }

    public boolean isLocal() {
        return MODE_LOCAL_OWNER.equals(mode) || MODE_LOCAL_REPLAY.equals(mode);
    }
}
