package com.example.traceability.common.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 核心插件配置。
 * <p>
 * 注册乐观锁拦截器，解析实体类中的 {@code @Version} 注解，
 * 在执行更新时自动进行基于版本号的 CAS 检查与递增。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Configuration(proxyBeanMethods = false)
public class MybatisPlusConfiguration {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 注册乐观锁拦截器
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
