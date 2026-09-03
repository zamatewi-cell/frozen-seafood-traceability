# ADR-001：Java 与 Spring Boot 版本

- 状态：接受（课程硬约束触发回退）
- 日期：2026-09-03

## 决策

服务端基线使用 Eclipse Temurin Java 21 LTS、Spring Boot 3.5.16、Maven Wrapper 3.9.x。个人电脑可安装更高 JDK，但编译 `release` 与 CI 统一为 21。

## 原因

- Java 21 是 LTS，语言和生态成熟；使用 Temurin 避免把 Oracle JDK 的授权安排误当作 OpenJDK 的统一限制。
- Spring Boot 3.5.16 官方要求至少 Java 17，并兼容到 Java 25，Java 21 位于支持区间内。
- 相比 Spring Boot 4，本项目选择 3.5 以降低实训期间 Jakarta/Servlet 新一轮升级和第三方库适配风险。
- MyBatis-Plus 提供独立的 Spring Boot 3 starter。

依据：[Spring Boot 3.5 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[Java 支持路线](https://www.oracle.com/java/technologies/java-se-support-roadmap.html)、[MyBatis-Plus 安装](https://baomidou.com/en/getting-started/install/)。

## 后果

- 不支持 Java 8 字节码；代码可使用 Java 21 的稳定语言特性，但避免为炫技引入预览特性。
- 若教师明确强制 Java 8，单独切换到 Java 8 + Spring Boot 2.7.x 兼容分支，并重做依赖、安全与测试评估；不在主线做双运行时兼容。

