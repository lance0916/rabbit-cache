package com.snailwu.cacheable.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author WuQinglong
 */
@Configuration
public class CacheConfig {

    @Bean
    public Cache<Object, Object> loadingCache() {
        return Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterAccess(Duration.ofMinutes(5))
            .build();
    }

}
