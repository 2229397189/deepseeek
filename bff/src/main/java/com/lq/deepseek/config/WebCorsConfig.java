package com.lq.deepseek.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * 跨域配置。
 *
 * <p>允许的来源通过 {@code lq.cors.allowed-origins} 配置（逗号分隔），默认只放本地开发
 * 与生产同源地址。生产部署走 nginx 同源（前端与 /api 同域），浏览器不会发跨域请求，
 * 这里只服务于本地 Vite 开服器（5173）等显式白名单场景；不再放行通配符来源。
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    @Value("${lq.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173,http://121.199.22.206}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("lq-token", "Content-Disposition")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
