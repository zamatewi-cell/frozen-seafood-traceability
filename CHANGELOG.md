# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 的基本结构，并使用语义化版本。

## [Unreleased]

### Added

- 服务端子工程 `server/`：基于 Eclipse Temurin Java 25 LTS 与 Spring Boot 4.1.1。
- 引入 Apache Maven Wrapper 3.9.x（纯脚本模式 `mvnw` / `mvnw.cmd`），配置 `maven.compiler.release=25`。
- 引入 Spring Boot 4 模块化依赖：WebMVC、Validation、Security、Actuator、Flyway、官方 WebMVC/Security 测试模块、`mybatis-plus-spring-boot4-starter 3.5.17` 及 Jackson 3。
- 配置分层安全机制：`application.yml` 与 `application-local.yml.example`，全环境变量占位，严禁敏感密码硬编码。
- 规划 8 大业务包边界：`common`、`identity`、`masterdata`、`batch`、`trace`、`quality`、`attachment`、`audit`，确立四层架构规范。
- common 模块链路追踪：`RequestIdFilter` 自动提取/生成 `X-Request-Id`，HTTP 响应头透传与 SLF4J MDC 绑定。
- 统一成功响应包装器：`SuccessEnvelope`，匹配 OpenAPI 3.1 契约标准（data + meta）。
- 统一错误处理：基于 `@RestControllerAdvice` 遵循 RFC 9457 Problem Details 规范，集成参数校验字段错误 `fieldErrors` 格式化。
- 受控示例端点及官方 Boot 4 MockMvc/Security 测试，覆盖统一 200/400/401/403/404、CSRF 与请求 ID 契约。
- 本地数据库容器编排 `deploy/docker-compose.yml`：基于 MySQL 8.4 LTS，配置宿主机端口 3307 映射、`utf8mb4`、UTC 与健康检查；密码只从本地 `.env` 注入。
- 最小基准数据库迁移脚本 `server/src/main/resources/db/migration/V1__init_schema.sql`：覆盖 8 大业务域 24 张核心基准表，包含雪花算法 BIGINT 主键、5 大通用审计字段、软删除标记、DECIMAL 精度度量与追加式追溯/审计模型，支持空库干净执行。
- GitHub Actions 后端持续集成工作流 `.github/workflows/backend-ci.yml`：集成 Temurin JDK 25、真实 MySQL 8.4 空库迁移/健康检查、Maven 缓存与失败报告归档。
- 工程文档：`server/README.md`（后端快速开发与容器指南）、`docs/phase2/README.md`（Phase 2 交付总结与规范索引）。
- PRD v0.1、领域调研、开发流程规范。
- Issue、Pull Request 模板及基础仓库配置。
- Phase 1 页面清单、交互规则、角色权限矩阵和业务状态机。
- 批次操作/谱系数据模型、数据字典、OpenAPI 3.1 草案和统一错误规范。
- Java 25 LTS/Spring Boot 4.1.1/MySQL 8.4 LTS/Vue 3 技术架构及 7 项 ADR；本机 MySQL 9.1.0 仅作为兼容验证环境。
- Phase 1 可交互原型、测试计划和三条版本化演示数据方案。

### Changed

- 更新根 `README.md`：当前阶段推进至 `Phase 2 / 迭代开发（服务端最小骨架启动）`，标明 Phase 1 评审与 Phase 2 后端工程骨架建设已达成。
