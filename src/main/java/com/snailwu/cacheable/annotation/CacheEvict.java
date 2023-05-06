package com.snailwu.cacheable.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 清除缓存
 * @author WuQinglong
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface CacheEvict {

    /**
     * 缓存前缀
     */
    String prefix() default "cache:";

    /**
     * 缓存的名字，需要符合 spel 表达式规范
     */
    String key();

    /**
     * 使用的缓存对象
     */
    String cacheBeanName() default "";

}
