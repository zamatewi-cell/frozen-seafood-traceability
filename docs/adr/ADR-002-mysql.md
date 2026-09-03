# ADR-002：MySQL 版本与迁移

- 状态：接受（课程硬约束触发回退）
- 日期：2026-09-03

## 决策

使用 MySQL 8.4 LTS，字符集 `utf8mb4`，数据库时区 UTC；所有结构和种子数据通过 Flyway 版本化迁移。

## 原因

- MySQL 官方将 8.4 定义为 LTS 轨道，适合需要稳定特性集的项目。
- 递归 CTE 支撑批次谱系遍历；有效 `CHECK` 约束和当前连接器生态优于旧版 5.5。
- MySQL 5.5 已不适合作为新项目安全与兼容基线。

依据：[MySQL LTS 与 Innovation 轨道](https://dev.mysql.com/doc/refman/8.4/en/mysql-releases.html)。

## 后果

- CI 使用真实 MySQL 8.4 容器，不用 H2 冒充数据库兼容测试。
- 迁移脚本一经共享环境执行不得原地修改。
- 若学校环境只能提供旧版 MySQL，优先使用 Docker/独立测试数据库；教师明确禁止时才另开兼容 ADR。

