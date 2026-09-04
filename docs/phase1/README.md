# Phase 1 产品与技术设计交付索引

基准日期：2026-09-03  
关联 Issue：[#1](https://github.com/zamatewi-cell/frozen-seafood-traceability/issues/1)  
范围：完成设计基线，不进入生产业务代码开发。

## 交付物状态

| 交付物 | 文件 | 状态 |
|---|---|---|
| 页面清单、低保真结构、关键状态、设计系统 | [PRODUCT_DESIGN.md](PRODUCT_DESIGN.md) | 已完成 |
| 可交互高保真原型 | [prototype/](../../prototype/) | 已完成并通过视觉走查 |
| 视觉与交互验收报告 | [design-qa.md](../../design-qa.md) | 已通过 |
| 角色权限矩阵 | [RBAC_MATRIX.md](RBAC_MATRIX.md) | 已完成 |
| 业务流程与状态机 | [BUSINESS_FLOWS.md](BUSINESS_FLOWS.md) | 已完成 |
| 技术架构 | [TECHNICAL_ARCHITECTURE.md](TECHNICAL_ARCHITECTURE.md) | 已完成 |
| ER、谱系约束、迁移方案 | [DATA_MODEL.md](DATA_MODEL.md) | 已完成 |
| 数据字典 | [DATA_DICTIONARY.md](DATA_DICTIONARY.md) | 已完成 |
| API 约定与端点清单 | [API_DESIGN.md](API_DESIGN.md) | 已完成 |
| OpenAPI 3.1 草案 | [openapi.yaml](../api/openapi.yaml) | 已完成 |
| 架构决策记录 | [docs/adr/](../adr/) | 已完成 |
| 测试计划 | [TEST_PLAN.md](TEST_PLAN.md) | 已完成 |
| 演示数据计划 | [DEMO_DATA_PLAN.md](DEMO_DATA_PLAN.md) | 已完成 |

## Phase 1 基线结论

1. 产品采用统一 PC 管理壳层和独立消费者响应式 H5；服务端权限不是由菜单隐藏代替。
2. 核心模型采用批次、追溯事件、批次操作和批次关系；批次操作负责数量平衡，关系表负责图遍历。
3. 服务端采用 Java 25 LTS + Spring Boot 4.1.1 的模块化单体，并使用 Boot 4 专用 starter；这是 20 个工作日实训范围内的最小复杂度方案。
4. 温控规则按产品、环节和版本配置；温度记录必须绑定判定时使用的规则快照或版本。
5. 公开查询采用专用白名单 DTO，不直接序列化企业内部实体。

## 退出条件检查

- [x] 页面动作能够映射到角色权限。
- [x] 页面字段能够映射到数据实体和 API。
- [x] 拆分、合并、加工和损耗有可验证的数量平衡规则。
- [x] 状态机覆盖拒收、冻结、召回、更正等异常路径。
- [x] 最小纵向闭环可拆分为 Phase 2 工作项。
- [ ] 教师确认是否强制旧版 JDK/MySQL/Vue CLI；当前 ADR 已写明回退门槛。
- [x] 可交互原型完成浏览器视觉走查并生成 `design-qa.md`。

## Phase 2 最小纵向闭环拆分

1. 工程骨架、Flyway、统一错误响应和 CI。
2. 组织、用户、角色、登录与数据范围。
3. 产品和温控规则基础数据。
4. 创建批次并提交一条来源/加工事件。
5. 生成不可猜测的公开追溯 ID。
6. 消费者白名单接口与响应式查询页。
7. 单元、集成和端到端验收测试。
