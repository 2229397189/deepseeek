package com.lq.deepseek.service;

import com.lq.deepseek.dto.AuthDtos;

/**
 * 认证与用户账号服务。
 */
public interface AuthService {

    /**
     * 注册：创建用户 + 初始化钱包 + 发放新人额度，全过程在同一事务内。
     *
     * @return 注册后的用户视图
     */
    AuthDtos.UserVO register(AuthDtos.RegisterRequest request);

    /**
     * 登录：校验凭据、检测账号状态、写入登录态并刷新最后登录时间。
     */
    AuthDtos.LoginResponse login(AuthDtos.LoginRequest request);

    /**
     * 退出登录。
     */
    void logout();

    /**
     * 查询当前登录用户。
     */
    AuthDtos.UserVO currentUser();

    /**
     * 按 id 查询用户，并校验其存在性与可用状态。
     */
    com.lq.deepseek.domain.entity.User requireActiveUser(Long userId);
}
