package com.lq.deepseek;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI 求职评估与模拟面试平台 —— Java BFF 入口。
 *
 * <p>分层职责：frontend -> Java BFF -> Python Agent。
 * BFF 负责身份认证、资源访问控制、计费额度与前端 API 聚合；
 * Python Agent 负责评估任务、用户画像、报告与面试状态的权威生成。
 */
@SpringBootApplication
@MapperScan("com.lq.deepseek.domain.mapper")
@ConfigurationPropertiesScan("com.lq.deepseek.config.props")
@EnableAsync
@EnableScheduling
public class LqDeepseekApplication {

    public static void main(String[] args) {
        SpringApplication.run(LqDeepseekApplication.class, args);
    }
}
