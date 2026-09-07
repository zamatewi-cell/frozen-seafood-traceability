package com.example.traceability;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 冷冻海鲜溯源系统后端服务启动入口类。
 * <p>
 * 基于 Spring Boot 4.1.1 与 Java 25 构建，
 * 负责引导整个溯源系统后端服务上下文的初始化与启动。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@MapperScan("com.example.traceability.**.mapper")
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class TraceabilityApplication {

    /**
     * 应用程序入口 main 方法。
     *
     * @param args 命令行启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(TraceabilityApplication.class, args);
    }
}
