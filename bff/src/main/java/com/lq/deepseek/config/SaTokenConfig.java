package com.lq.deepseek.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 认证配置。
 *
 * <p>默认全局鉴权，白名单只放认证入口与接口文档；资源级权限由各业务 Service 基于
 * 资源归属（user_id）二次校验，避免"登录即可访问他人数据"。
 *
 * <p>管理端 {@code /admin/**} 在登录之外额外要求 admin 角色（数据源见
 * {@link StpInterfaceImpl}），普通登录用户访问管理端一律 403。
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    private static final String[] WHITE_LIST = {
            "/auth/register",
            "/auth/login",
            "/auth/captcha",
            "/actuator/health",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/error"
    };

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> {
                    StpUtil.checkLogin();
                    // 管理端角色门禁：登录用户还需具备 admin 角色
                    SaRouter.match("/admin/**", r -> StpUtil.checkRole("admin"));
                }))
                .addPathPatterns("/**")
                .excludePathPatterns(WHITE_LIST);
    }
}
