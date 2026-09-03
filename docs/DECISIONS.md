# 当前决策与待决策

本文件记录规划阶段已经形成的关键判断。Phase 1 技术方案已形成独立 ADR；课程硬约束变化时通过新 ADR 修订，不直接覆盖历史决定。

## 已确定

| 编号 | 决策 | 原因 |
|---|---|---|
| D-001 | 旧肉类资料只作参考，不作为需求基线 | 存在肉类专属证件、固定链路、复制残留和版权边界 |
| D-002 | 使用通用批次、追溯事件和批次关系模型 | 支持捕捞/养殖/进口、加工、拆分、合并和分装 |
| D-003 | MVP 采用批次级查询，不宣称逐件防伪 | 符合实训工期，也避免虚构真实性能力 |
| D-004 | 统一企业操作端，通过组织和权限区分环节 | 避免为每类企业复制整套页面与后端逻辑 |
| D-005 | 温度、检测和外部数据必须标注来源 | 手工、导入、模拟和设备实采的可信程度不同 |
| D-006 | 区块链、真实 IoT 和真实监管接口不进入 MVP | 缺少外部条件，且不是建立可用追溯闭环的前提 |
| D-007 | GitHub 仓库默认私有，不自动添加开源许可证 | 原始参考资料有版权提示，公开范围和许可证尚未确认 |

## Phase 1 已接受 ADR

| ADR | 决策摘要 |
|---|---|
| [ADR-001](adr/ADR-001-java-and-spring-boot.md) | Temurin Java 21 LTS + Spring Boot 3.5.16；教师强制 Java 8 时单独回退 |
| [ADR-002](adr/ADR-002-mysql.md) | MySQL 8.4 LTS + Flyway |
| [ADR-003](adr/ADR-003-vue-toolchain.md) | Vue 3 + TypeScript + Vite 8；Element Plus、X6、ECharts |
| [ADR-004](adr/ADR-004-authentication.md) | 同源服务端会话 + CSRF |
| [ADR-005](adr/ADR-005-attachment-storage.md) | 受控本地附件目录 + 可替换存储端口 |
| [ADR-006](adr/ADR-006-temperature-ingestion.md) | 人工/模拟 P0，CSV P1，真实设备 P2 |
| [ADR-007](adr/ADR-007-deployment.md) | Docker Compose 单体部署 + 本机 Maven 备选 |

## 外部待确认

- 教师是否强制 Java 8、MySQL 5.5 或 Vue CLI；若强制，将触发 ADR 修订。
- 最终部署目标是本机、学校服务器还是云主机；当前 Compose 方案适配三者。
