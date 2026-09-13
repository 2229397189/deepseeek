package com.lq.deepseek.config;

import cn.dev33.satoken.stp.StpInterface;
import com.lq.deepseek.domain.entity.User;
import com.lq.deepseek.domain.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sa-Token 角色数据源：把 users.role 接进 Sa-Token 的注解/路由鉴权体系。
 *
 * <p>目前只有一种受控角色：admin（/admin/** 路由校验）。权限列表恒为空——
 * 本项目不做细粒度权限点，资源归属校验由各业务 Service 的 userId 过滤承担。
 */
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final UserMapper userMapper;

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        User user = userMapper.selectById(Long.valueOf(String.valueOf(loginId)));
        if (user == null || user.getRole() == null) {
            return List.of();
        }
        return List.of(user.getRole());
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return List.of();
    }
}
