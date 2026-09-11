package com.lq.deepseek.common;

import lombok.Getter;

/**
 * 业务错误码。前三位表示 HTTP 语义，后三位为业务序号。
 */
@Getter
public enum ErrorCode {

    OK(0, "success"),

    // 通用
    PARAM_INVALID(400001, "参数校验失败"),
    UNAUTHORIZED(401001, "未登录或登录已过期"),
    FORBIDDEN(403001, "无权访问该资源"),
    NOT_FOUND(404001, "资源不存在"),
    CONFLICT(409001, "资源状态冲突"),
    INTERNAL_ERROR(500001, "服务内部错误"),

    // 用户 / 认证
    USERNAME_EXISTS(400101, "用户名已被占用"),
    EMAIL_EXISTS(400102, "邮箱已被注册"),
    USER_NOT_FOUND(404101, "用户不存在"),
    PASSWORD_INVALID(400103, "用户名或密码错误"),
    USER_DISABLED(403102, "账号已被禁用"),

    // 计费
    WALLET_NOT_FOUND(404201, "钱包不存在"),
    INSUFFICIENT_CREDIT(402201, "可用额度不足，请先充值"),
    LEDGER_DUPLICATED(409201, "该笔额度变动已处理，请勿重复提交"),

    // 邀请码
    INVITE_CODE_INVALID(400301, "邀请码无效"),
    INVITE_CODE_EXHAUSTED(409301, "邀请码已被领完"),

    // 文件
    FILE_EMPTY(400401, "上传文件为空"),
    FILE_TYPE_UNSUPPORTED(400402, "不支持的文件类型"),
    FILE_TOO_LARGE(400403, "文件超出大小限制"),
    FILE_NOT_FOUND(404401, "文件资产不存在"),

    // Agent / 网关
    AGENT_UNAVAILABLE(503501, "Agent 服务暂不可用，请稍后重试"),
    AGENT_TIMEOUT(504501, "AI 调用超时"),
    AGENT_STAGE_FAILED(500501, "AI 调用失败"),
    SINGLE_FLIGHT_REJECTED(429501, "相同请求正在处理中，请稍候"),
    RUN_NOT_FOUND(404501, "运行记录不存在");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
