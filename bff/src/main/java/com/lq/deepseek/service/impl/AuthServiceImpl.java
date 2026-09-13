package com.lq.deepseek.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.lq.deepseek.common.BusinessException;
import com.lq.deepseek.common.ErrorCode;
import com.lq.deepseek.domain.entity.InviteCode;
import com.lq.deepseek.domain.entity.User;
import com.lq.deepseek.domain.mapper.InviteCodeMapper;
import com.lq.deepseek.domain.mapper.UserMapper;
import com.lq.deepseek.dto.AuthDtos;
import com.lq.deepseek.service.AuthService;
import com.lq.deepseek.service.BillingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;

/**
 * 认证与账号服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /** 新用户注册赠送额度 */
    private static final long NEW_USER_GRANT_CREDIT = 1000L;
    /** 邀请成功双方各得额度 */
    private static final long INVITE_REWARD_CREDIT = 500L;

    private static final String CHANGE_TYPE_GRANT = "GRANT";

    private final UserMapper userMapper;
    private final InviteCodeMapper inviteCodeMapper;
    private final BillingService billingService;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AuthDtos.UserVO register(AuthDtos.RegisterRequest request) {
        if (userMapper.selectByUsername(request.getUsername()) != null) {
            throw BusinessException.of(ErrorCode.USERNAME_EXISTS);
        }
        if (StringUtils.hasText(request.getEmail())
                && userMapper.selectByEmail(request.getEmail()) != null) {
            throw BusinessException.of(ErrorCode.EMAIL_EXISTS);
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setEmail(StringUtils.hasText(request.getEmail()) ? request.getEmail() : null);
        user.setNickname(StringUtils.hasText(request.getNickname())
                ? request.getNickname() : request.getUsername());
        user.setStatus(1);
        user.setRole("user");
        userMapper.insert(user);

        // 钱包与新人额度：同一事务，注册失败不会留下"半截账号"
        billingService.ensureWallet(user.getId());
        billingService.grant(user.getId(), NEW_USER_GRANT_CREDIT, CHANGE_TYPE_GRANT,
                "REGISTER", user.getId(), "grant:newbie:" + user.getId(), "新用户注册赠送额度");

        if (StringUtils.hasText(request.getInviteCode())) {
            applyInviteCode(user.getId(), request.getInviteCode().trim());
        }

        log.info("用户注册成功 userId={} username={}", user.getId(), user.getUsername());
        return toVO(user);
    }

    @Override
    public AuthDtos.LoginResponse login(AuthDtos.LoginRequest request) {
        User user = userMapper.selectByUsername(request.getUsername());
        // 用户不存在与密码错误返回同一错误码，避免账号枚举
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw BusinessException.of(ErrorCode.PASSWORD_INVALID);
        }
        if (!user.isEnabled()) {
            throw BusinessException.of(ErrorCode.USER_DISABLED);
        }

        StpUtil.login(user.getId());

        User touch = new User();
        touch.setId(user.getId());
        touch.setLastLoginAt(OffsetDateTime.now());
        userMapper.updateById(touch);

        return AuthDtos.LoginResponse.builder()
                .token(StpUtil.getTokenValue())
                .tokenName(StpUtil.getTokenName())
                .expiresIn(StpUtil.getTokenTimeout())
                .user(toVO(user))
                .build();
    }

    @Override
    public void logout() {
        StpUtil.logout();
    }

    @Override
    public AuthDtos.UserVO currentUser() {
        return toVO(requireActiveUser(StpUtil.getLoginIdAsLong()));
    }

    @Override
    public User requireActiveUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.of(ErrorCode.USER_NOT_FOUND);
        }
        if (!user.isEnabled()) {
            throw BusinessException.of(ErrorCode.USER_DISABLED);
        }
        return user;
    }

    /**
     * 邀请码核销：先原子占用次数，再发额度，保证一码不会被重复领满。
     */
    private void applyInviteCode(Long newUserId, String code) {
        InviteCode invite = inviteCodeMapper.selectByCode(code);
        if (invite == null || !invite.usable()) {
            throw BusinessException.of(ErrorCode.INVITE_CODE_INVALID);
        }
        if (inviteCodeMapper.consumeOnce(invite.getId()) == 0) {
            throw BusinessException.of(ErrorCode.INVITE_CODE_EXHAUSTED);
        }
        billingService.grant(newUserId, INVITE_REWARD_CREDIT, CHANGE_TYPE_GRANT,
                "INVITE", invite.getId(), "grant:invite:invitee:" + newUserId, "邀请码奖励（被邀请人）");
        billingService.grant(invite.getInviterUserId(), INVITE_REWARD_CREDIT, CHANGE_TYPE_GRANT,
                "INVITE", invite.getId(), "grant:invite:inviter:" + newUserId, "邀请码奖励（邀请人）");
    }

    private AuthDtos.UserVO toVO(User user) {
        return AuthDtos.UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .avatarUrl(user.getAvatarUrl())
                .role(user.getRole())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
