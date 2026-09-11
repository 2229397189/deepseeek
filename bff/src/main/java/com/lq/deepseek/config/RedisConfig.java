package com.lq.deepseek.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Redis 配置。
 *
 * <p>Single-flight 治理全部基于 Lua 脚本 + Hash 结构实现（owner/follower 状态机、心跳续租、
 * 失败接管、结果回放），因此这里显式注册脚本 Bean，避免每次调用重复加载脚本体。
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(mapper);

        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /**
     * 尝试成为 owner：key 不存在或原 owner 租约已过期（可接管）时写入自身 ownerId 并返回 1。
     */
    @Bean
    public DefaultRedisScript<Long> singleFlightAcquireScript() {
        String lua = """
                local stateKey = KEYS[1]
                local ownerId  = ARGV[1]
                local leaseMs  = tonumber(ARGV[2])
                local now      = tonumber(ARGV[3])

                local current = redis.call('HGET', stateKey, 'ownerId')
                local expireAt = tonumber(redis.call('HGET', stateKey, 'ownerExpireAt') or '0')

                -- 无人持有，或原持有者租约已过期 -> 接管
                if (not current) or current == ownerId or expireAt < now then
                    redis.call('HSET', stateKey, 'ownerId', ownerId)
                    redis.call('HSET', stateKey, 'ownerExpireAt', now + leaseMs)
                    redis.call('HSET', stateKey, 'status', 'RUNNING')
                    redis.call('PEXPIRE', stateKey, leaseMs * 10)
                    return 1
                end
                return 0
                """;
        return new DefaultRedisScript<>(lua, Long.class);
    }

    /**
     * follower 注册：未达上限则计数 +1 并返回 1，否则返回 0（保护下游，快速失败）。
     */
    @Bean
    public DefaultRedisScript<Long> singleFlightJoinScript() {
        String lua = """
                local stateKey   = KEYS[1]
                local maxFollowers = tonumber(ARGV[1])
                local followers = tonumber(redis.call('HGET', stateKey, 'followers') or '0')
                if followers >= maxFollowers then
                    return 0
                end
                redis.call('HINCRBY', stateKey, 'followers', 1)
                return 1
                """;
        return new DefaultRedisScript<>(lua, Long.class);
    }

    /**
     * owner 心跳续租：只有仍是 owner 时才延长租约，避免「已失联的 owner 复活后覆盖新 owner」。
     */
    @Bean
    public DefaultRedisScript<Long> singleFlightRenewScript() {
        String lua = """
                local stateKey = KEYS[1]
                local ownerId  = ARGV[1]
                local leaseMs  = tonumber(ARGV[2])
                local now      = tonumber(ARGV[3])

                local current = redis.call('HGET', stateKey, 'ownerId')
                if current == ownerId then
                    redis.call('HSET', stateKey, 'ownerExpireAt', now + leaseMs)
                    redis.call('PEXPIRE', stateKey, leaseMs * 10)
                    return 1
                end
                return 0
                """;
        return new DefaultRedisScript<>(lua, Long.class);
    }

    /**
     * 结果发布：写入终态与结果体，并按 TTL 保留供回放。
     *
     * <p>失败态会同时抹掉 ownerExpireAt，使下一次请求可以立即接管重试，而不是被"失败态"卡住。
     */
    @Bean
    public DefaultRedisScript<Long> singleFlightPublishScript() {
        String lua = """
                local stateKey  = KEYS[1]
                local status    = ARGV[1]
                local result    = ARGV[2]
                local errorCode = ARGV[3]
                local errorMsg  = ARGV[4]
                local ttlMs     = tonumber(ARGV[5])
                local now       = tonumber(ARGV[6])

                redis.call('HSET', stateKey, 'status', status)
                redis.call('HSET', stateKey, 'finishedAt', now)
                if result ~= '' then
                    redis.call('HSET', stateKey, 'result', result)
                end
                if errorCode ~= '' then
                    redis.call('HSET', stateKey, 'errorCode', errorCode)
                end
                if errorMsg ~= '' then
                    redis.call('HSET', stateKey, 'errorMsg', errorMsg)
                end
                if status == 'FAILED' then
                    redis.call('HDEL', stateKey, 'ownerExpireAt')
                end
                redis.call('PEXPIRE', stateKey, ttlMs)
                return 1
                """;
        return new DefaultRedisScript<>(lua, Long.class);
    }
}
