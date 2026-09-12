# 冷冻海产品溯源系统

面向高校实训的冷冻海产品供应链追溯原型。系统以“批次、追溯事件、批次关系”为核心，记录来源、加工速冻、仓储、运输、交接、检测和销售等关键节点，支持消费者一码查询，以及企业侧的正向追踪、反向溯源和模拟召回。

> 当前阶段：`Phase 3 / 最小纵向闭环研发`。已完成 Phase 1 设计评审与 Phase 2 后端工程骨架建设；已完成 Issue #7 会话认证与组织上下文；已完成 Issue #9 产品主数据与分阶段温控规则；已完成 Issue #11 组织范围批次草稿生命周期；已完成 Issue #13 批次操作、物料平衡与谱系边；已完成 Issue #15 追加式追溯事件与更正工作流；已完成 Issue #17（PR #18 已合入）公开追溯码与消费者公开投影（包含 26 位 Base32 安全编码、内部 token_hash SHA-256 哈希索引、企业端激活与终态停用、统一幂等表持久绑定、消费者匿名免认证免 CSRF 白名单安全投影、敏感自由文本隔离、温度 INSUFFICIENT_DATA 诚实声明、RECALLED 模拟召回演练声明与 Flyway V6 物理约束）；当前正在分支 `feat/19-consumer-trace-web` 研发并验证 Issue #19 生产 Vue 3 移动优先消费者查询端（待合并：基于 Vue 3 + TypeScript + Vite 8 构建响应式消费者查验页，对接 FR-TRACE-002 公开接口、RFC 4648 Base32 客户端前置校验、骨架屏、中性 404、模拟召回演练显式声明、真实性诚实披露、Vitest/Playwright 全自动化验证、无 v-html、无前端持久化与独立前端 CI 流水线）。

## 项目边界

- 本项目是教学原型，不是政府监管平台，也不构成生产级合规认证。
- “可追溯”表示能够查询系统中已记录的信息，不代表系统能够独立证明原始数据真实。
- 本期不宣称接入真实物联网、区块链、海关、政府平台或第三方检测机构。
- 演示数据必须虚构或脱敏，不上传真实个人信息、密钥、企业证照原件。
- 当前目录中的“肉类食品溯源”材料仅供结构参考，不是本项目需求来源，也不会提交到仓库。

## MVP 业务闭环

```mermaid
flowchart LR
    A[捕捞/养殖/进口来源] --> B[加工与速冻]
    B --> C[冷库入库]
    C --> D[冷链运输]
    D --> E[经销/零售验收]
    E --> F[消费者扫码查询]
    B -.拆分/合并.-> B
    C -.温度异常.-> G[告警与处置]
    D -.温度异常.-> G
    G --> H[冻结批次/模拟召回]
```

## 已形成的项目文档

- [产品需求文档](docs/PRD.md)：产品目标、角色、功能需求、业务规则、数据模型和验收标准。
- [领域调研与依据](docs/RESEARCH.md)：法规、标准、旧参考资料差异与需求推导。
- [开发流程规范](docs/DEVELOPMENT_PROCESS.md)：从需求到发布的阶段门、Issue、分支、提交、PR、测试和发布规则。
- [Phase 1 交付索引](docs/phase1/README.md)：原型、权限矩阵、业务流程、数据模型、OpenAPI、ADR 与测试计划。
- [Phase 2 交付总结](docs/phase2/README.md)：后端工程骨架、8 大业务域边界、统一响应契约、Flyway 迁移与 CI 体系。
- [Phase 3 交付文档](docs/phase3/README.md)：会话认证与组织上下文（Issue #7）与产品主数据及分阶段温控规则（Issue #9）契约、安全机制、核心算法与测试验证规范。
- [服务端工程自述](server/README.md)：Java 25 / Boot 4 开发指南、MySQL 8.4 容器 (3307 端口) 启动与测试指南。
- [可交互原型](prototype/README.md)：经确认视觉稿转化的产品走查原型，不是生产 Vue 工程。
- [参与贡献](CONTRIBUTING.md)：成员日常协作的精简入口。

## 计划中的仓库结构

```text
.
├─ server/                 # Spring Boot 服务端（已建立工程骨架与 8 大业务包）
├─ web/                    # Vue 3 Web 端（已建立 Vue 3 + TS + Vite 8 生产工程与消费者查询页）
├─ database/               # 版本化迁移与演示数据
├─ tests/                  # 跨端验收与测试资产
├─ deploy/                 # 部署配置和运维说明（含 deploy/docker-compose.yml 数据库环境编排）
├─ docs/                   # 产品、调研、设计、测试与答辩资料
└─ .github/                # Issue / PR 模板与后端 CI 流水线
```

## 本地数据库快速启动

开发环境通过 Docker Compose 一键启动 MySQL 8.4 LTS 服务（宿主机端口 `3307` 隔离，避开宿主机 3306 冲突）：

```bash
# 首次启动前复制模板，并修改 .env 中的本地开发密码
cp .env.example .env

# 在仓库根目录启动数据库；显式读取根目录 .env
docker compose --env-file .env -f deploy/docker-compose.yml up -d --wait
```

## 技术约束说明

实践任务书提出 JDK 8、Spring Boot、MyBatis-Plus、MySQL、Vue 3 的学习目标。主线已通过 ADR 锁定为 Java 25 LTS + Spring Boot 4.1.1；Boot 4 使用 Spring Framework 7、Jakarta EE 11、Jackson 3 和专用模块化 starter。若教师明确强制 JDK 8，应单独回退到 Spring Boot 2.7.x，不能在 Boot 4 主线兼容。数据库以 MySQL 8.4 LTS 为团队/CI 基线（本地 Docker 映射 3307 规避 Windows 宿主机 3306 冲突），本机 MySQL 9.1.0 仅用于兼容验证；任务书中的 MySQL 5.5 和 Vue CLI 属于旧模板信息。

## 当前里程碑

- [x] 盘点本地参考资料并识别不可照搬内容
- [x] 完成领域资料检索与 PRD v0.1
- [x] 建立开发流程和 GitHub 协作模板
- [x] 用户确认 MVP 核心范围与 UI 设计方向
- [x] 完成角色权限矩阵、交互原型、数据模型和 API 草案
- [x] 完成 Phase 1 浏览器视觉走查
- [x] 完成团队/教师评审与 Phase 1 设计基线封版
- [x] 完成 Phase 2 后端工程骨架建设（Spring Boot 4.1.1 + Java 25、Flyway V1、MySQL 8.4 容器、CI 流水线）
- [x] 完成 Phase 3 会话认证与组织上下文（Issue #7：identity 安全会话、CSRF 防护、防会话固定、动态活性复核与组织上下文唯一推导）
- [x] 完成 Phase 3 产品主数据与分阶段温控规则（Issue #9：产品创建/查询/更新、乐观锁、分阶段可版本化温控基准、半开区间无重叠判定算法与不可变发布）
- [x] 完成 Phase 3 组织范围批次草稿生命周期（Issue #11：基础批次草稿创建、Idempotency-Key 幂等防重与并发竞态恢复、同组织批号排他、SQL 层组织数据隔离、单条 SQL 条件原子更新与 ACTIVE 提交流转、Flyway V3 物理 CHECK 约束）
- [x] 完成 Phase 3 批次操作、物料平衡与谱系边（Issue #13：批次操作草稿创建与提交、服务端物料平衡重新计算、引用批次活性/组织归属校验、输出批次唯一产出与声明量校验、输入批次累计量防超额、MySQL 8.4 recursive CTE 环检测、双幂等机制、不可变谱系边与 Flyway V4 物理约束）
- [x] 完成 Phase 3 追加式追溯事件与更正工作流（Issue #15：10类标准事件录入、受控 detailsJson 扩展属性校验、双时间维度输出、同组织操作员鉴权、防分叉链式更正、写路径批次行锁防 TOCTOU、锁后当前读幂等恢复与 Flyway V5 物理约束）
- [x] 完成 Phase 3 公开追溯码与消费者公开投影（Issue #17 / PR #18：26 位 RFC 4648 Base32 唯一编码、内部 token_hash SHA-256 哈希索引、企业端激活与终态停用、统一幂等记录表持久绑定与组织隔离、消费者匿名免认证免 CSRF 白名单安全投影、敏感自由文本隔离、温度 INSUFFICIENT_DATA 诚实声明、RECALLED 模拟召回演练声明与 Flyway V6 物理约束）
- [ ] 正在分支 `feat/19-consumer-trace-web` 研发并验证 Phase 3 响应式消费者追溯 Web（Issue #19：Vue 3 + TypeScript + Vite 8、移动优先查询页、真实性披露、Vitest、Playwright、真实 Vue → Spring Boot → MySQL 8.4 冒烟与独立前端 CI）
- [ ] 完成异常、追溯、召回、测试、部署和答辩材料

## 下一步

在 Issue #7 会话认证、Issue #9 产品及温控规则、Issue #11 批次生命周期、Issue #13 批次操作与谱系边、Issue #15 追溯事件更正与 Issue #17 公开追溯码（PR #18 已合入）的基础上，推进 Issue #19 消费者响应式 Web 端分支合并验收，完成首个纵向业务闭环的前端查验能力；后续推进异常处置、双向图谱追溯与综合答辩材料准备。
