package com.lq.deepseek.config;

import com.lq.deepseek.config.props.LqProperties;
import com.lq.deepseek.gateway.singleflight.LocalFlightRegistry;
import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * 基础能力 Bean。
 */
@Configuration
public class AppBeansConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // 强度 10，兼顾安全与注册耗时
        return new BCryptPasswordEncoder(10);
    }

    /**
     * 调用 Python Agent 的非流式客户端；SSE 流式走 WebClient。
     */
    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    /**
     * Agent 调用专用 WebClient。
     *
     * <p>只在连接器层设置连接超时，读超时交给每次调用的 {@code timeout(...)} 控制——
     * 流式（SSE）与非流式的合理读超时差异很大，放在连接器上会误杀长时间的流式对话。
     */
    @Bean
    public WebClient agentWebClient(LqProperties properties) {
        LqProperties.Agent agent = properties.getAgent();
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) agent.getConnectTimeout().toMillis());
        // Agent 返回的评估报告可能较大，放宽内存缓冲上限，避免 DataBufferLimitException
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        return WebClient.builder()
                .baseUrl(agent.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }

    /**
     * Redis 抖动时的进程内 single-flight 兜底实现。
     */
    @Bean
    public LocalFlightRegistry localFlightRegistry() {
        return new LocalFlightRegistry();
    }
}
