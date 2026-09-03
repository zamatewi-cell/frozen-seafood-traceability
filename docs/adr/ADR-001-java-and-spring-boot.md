# ADR-001：Java 与 Spring Boot 版本

- 状态：接受（课程硬约束触发回退）
- 日期：2026-09-03

## 决策

服务端基线使用 Eclipse Temurin Java 25 LTS、Spring Boot 4.1.1、Maven Wrapper 3.9.x。本地编译、Maven Wrapper 与 CI 的 Java `release` 统一为 25。

## 原因

- Java 25 是 LTS，本机已安装并实际启用 Temurin 25.0.3；使用 Temurin 避免把 Oracle JDK 的授权安排误当作 OpenJDK 的统一限制。
- Spring Boot 4.1.1 官方要求至少 Java 17，并兼容到 Java 26，Java 25 位于支持区间内。
- 本项目是尚未进入生产编码的新项目，没有从 Boot 3 迁移的历史负担，可以直接采用 Spring Framework 7、Jakarta EE 11、Servlet 6.1 和 Jackson 3 基线。
- MyBatis-Plus 3.5.17 提供独立的 `mybatis-plus-spring-boot4-starter`，无需误用 Boot 3 starter。

依据：[Spring Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)、[Spring Boot 4 迁移指南](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)、[Java 支持路线](https://www.oracle.com/java/technologies/java-se-support-roadmap.html)、[MyBatis-Plus 安装](https://baomidou.com/en/getting-started/install/)。

## 后果

- 不支持 Java 8/17/21 字节码；代码可使用 Java 25 的稳定语言特性，但避免为炫技引入预览特性。
- Web 使用 `spring-boot-starter-webmvc`；Flyway 使用 `spring-boot-starter-flyway`；JSON 默认使用 Jackson 3。不得沿用 Boot 3 的隐式依赖假设。
- 测试依赖按 Boot 4 的模块化 starter 组织，并重点覆盖 JSON 序列化、Security 7 权限规则与数据库迁移。
- 若教师明确强制 Java 8，单独切换到 Java 8 + Spring Boot 2.7.x 兼容分支，并重做依赖、安全与测试评估；不在主线做双运行时兼容。
