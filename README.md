# 自定义缓存注解

自定义 Cache 注解，用于替换 Spring Cacheable 包，支持开启本地缓存。
使用 redisson 作为 redis 的客户端，并且使用 redisson 提供的 redis 锁进行实现。 

# 注解

1. @Cacheable 标注以该注解的方法，会被缓存起来，下次调用时，会从缓存中获取数据，而不是执行方法。
2. @CacheEvict 标注以该注解的方法，会清除缓存。

