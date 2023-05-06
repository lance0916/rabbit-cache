package com.snailwu.cacheable.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 将方法的接口进行缓存
 * @author WuQinglong
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Cacheable {

    /**
     * 缓存前缀
     */
    String prefix() default "cache:";

    /**
     * 缓存的名字，需要符合 spel 表达式规范
     */
    String key();

    /**
     * 缓存过期时间
     */
    long expire() default 1000 * 60;

    /**
     * 是否启用在过期时间上加一个随机时间，防止雪崩
     */
    boolean randomTime() default true;

    /**
     * 获取缓存数据需要的时间
     */
    long methodTimeout() default 3000;

    /**
     * 使用的缓存对象
     */
    String cacheBeanName() default "";

}
