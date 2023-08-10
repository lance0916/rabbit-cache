package com.snailwu.cacheable.aspect;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.snailwu.cacheable.annotation.CacheEvict;
import com.snailwu.cacheable.annotation.Cacheable;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import javax.annotation.Resource;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.LocalVariableTableParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

/**
 * @author WuQinglong
 */
@Aspect
@Component
@SuppressWarnings("unchecked")
public class RedisCacheAspect implements ApplicationContextAware {

    private final Logger log = LoggerFactory.getLogger(RedisCacheAspect.class);

    public final ObjectMapper jackson = new ObjectMapper()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .setDateFormat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"))
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final LocalVariableTableParameterNameDiscoverer discoverer = new LocalVariableTableParameterNameDiscoverer();

    private ApplicationContext applicationContext;

    /**
     * 一级缓存，本地缓存
     * FIXME 不同的缓存有不同的配置
     */
    private Cache<String, Object> loadingCache = Caffeine.newBuilder()
        .maximumSize(100)
        .expireAfterAccess(Duration.ofSeconds(10))
        .build();

    @Resource
    private RedissonClient redissonClient;

    /**
     * 缓存切面不应该影响业务，所以这里不抛出异常
     */
    @Around("@annotation(com.snailwu.cacheable.annotation.Cacheable)")
    public Object cachePut(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        Cacheable cacheable = method.getAnnotation(Cacheable.class);
        String key = getCacheKey(joinPoint, method, cacheable.prefix(), cacheable.key());

        // 使用自定义的缓存
        if (cacheable.cacheBeanName() != null && !cacheable.cacheBeanName().isEmpty()) {
            loadingCache = (Cache<String, Object>) applicationContext.getBean(cacheable.cacheBeanName());
        }

        // 本地缓存有，直接返回
        Object localCacheResult = loadingCache.getIfPresent(key);
        if (localCacheResult != null) {
            log.debug("从本地缓存中获取到数据，返回");
            return localCacheResult;
        }

        // 获取 Redis 缓存的值
        String dataJson = (String) redissonClient.getBucket(key, new StringCodec()).get();
        if (dataJson != null && !dataJson.isEmpty()) {
            JavaType javaType = getJavaType(method.getGenericReturnType());
            // FIXME readJson不应该抛异常，而是返回空值，然后去数据库中进行查询，再重新放入缓存中
            Object result = readJson(dataJson, javaType);

            // 放入本地缓存
            loadingCache.put(key, result);
            log.debug("从 Redis 缓存中获取到数据，并更新到本地缓存");

            return result;
        }

        // 分布式锁控制读取数据
        RLock lock = redissonClient.getLock("lock:" + key);
        if (!lock.tryLock(cacheable.methodTimeout(), TimeUnit.MILLISECONDS)) {
            throw new RuntimeException("获取分布式锁异常");
        }

        try {
            // 再次尝试获取本地缓存的值
            localCacheResult = loadingCache.getIfPresent(key);
            if (localCacheResult != null) {
                return localCacheResult;
            }

            // 执行方法逻辑，获取要缓存的数据
            Object result = joinPoint.proceed();

            // 如果返回值为 null，则不缓存
            if (result == null) {
                return null;
            }

            // 数据缓存到 Redis
            long expire = cacheable.expire();
            if (cacheable.randomTime()) {
                expire = expire + ThreadLocalRandom.current().nextInt(-900, 900);
            }
            redissonClient.getBucket(key, new StringCodec()).set(writeJson(result), expire, TimeUnit.MILLISECONDS);

            // 缓存到本地
            loadingCache.put(key, result);

            log.info("缓存数据 key={} expire={}", key, expire);
            return result;
        } finally {
            lock.unlock();
        }
    }

    @Around("@annotation(com.snailwu.cacheable.annotation.CacheEvict)")
    public Object cacheEvict(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        CacheEvict cacheEvict = method.getAnnotation(CacheEvict.class);
        String key = getCacheKey(joinPoint, method, cacheEvict.prefix(), cacheEvict.key());

        // 使用自定义的缓存
        if (cacheEvict.cacheBeanName() != null && !cacheEvict.cacheBeanName().isEmpty()) {
            loadingCache = (Cache<String, Object>) applicationContext.getBean(cacheEvict.cacheBeanName());
        }

        loadingCache.invalidate(key);
        redissonClient.getBucket(key).delete();

        return joinPoint.proceed();
    }

    /**
     * 解析出缓存的 key
     */
    private String getCacheKey(ProceedingJoinPoint joinPoint, Method method, String prefix, String cacheKey) {
        EvaluationContext context = new StandardEvaluationContext();
        String[] parameterNames = discoverer.getParameterNames(method);
        if (parameterNames != null) {
            Object[] args = joinPoint.getArgs();
            for (int i = 0; i < parameterNames.length; i++) {
                context.setVariable(parameterNames[i], args[i]);
            }
        }

        // 解析出 key 值
        Expression expression = parser.parseExpression(cacheKey);
        String key = (String) expression.getValue(context);
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("缓存的key不能为null");
        }
        return prefix + key;
    }

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * 将对象转为 String 类型的 Json
     * @param value 待转换的对象
     * @return JSON数据
     */
    public String writeJson(Object value) {
        if (value == null) {
            throw new NullPointerException();
        }
        try {
            return jackson.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 读取 Json 数据转为对象
     * @param json Json数据
     * @param javaType 对象类型
     * @param <T> 对象泛型
     * @return 对象
     */
    public <T> T readJson(String json, JavaType javaType) {
        if (json == null || json.isEmpty()) {
            throw new IllegalArgumentException();
        }
        try {
            return jackson.readValue(json, javaType);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public JavaType getJavaType(Type type) {
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
            Type rawType = parameterizedType.getRawType();
            JavaType[] javaTypes = new JavaType[actualTypeArguments.length];
            for (int i = 0; i < actualTypeArguments.length; i++) {
                javaTypes[i] = getJavaType(actualTypeArguments[i]);
            }
            return TypeFactory.defaultInstance().constructParametricType((Class<?>) rawType, javaTypes);
        } else {
            return TypeFactory.defaultInstance().constructParametricType((Class<?>) type, new JavaType[0]);
        }
    }

}

