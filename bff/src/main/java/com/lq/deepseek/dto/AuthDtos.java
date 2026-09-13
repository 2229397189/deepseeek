package com.lq.deepseek.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 认证相关 DTO 容器。
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    @Data
    public static class RegisterRequest {

        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^[a-zA-Z0-9_]{4,32}$", message = "用户名需为 4-32 位字母、数字或下划线")
        private String username;

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度需为 8-64 位")
        private String password;

        @Email(message = "邮箱格式不正确")
        private String email;

        @Size(max = 32, message = "昵称最长 32 位")
        private String nickname;

        /** 可选邀请码，填写后邀请人与被邀请人均获得额度奖励 */
        private String inviteCode;
    }

    @Data
    public static class LoginRequest {

        @NotBlank(message = "用户名不能为空")
        private String username;

        @NotBlank(message = "密码不能为空")
        private String password;
    }

    @Data
    @Builder
    public static class LoginResponse {
        private String token;
        private String tokenName;
        private long expiresIn;
        private UserVO user;
    }

    @Data
    @Builder
    public static class UserVO {
        private Long id;
        private String username;
        private String email;
        private String nickname;
        private String avatarUrl;
        /** user 普通用户 / admin 管理员 */
        private String role;
        private OffsetDateTime createdAt;
    }
}
