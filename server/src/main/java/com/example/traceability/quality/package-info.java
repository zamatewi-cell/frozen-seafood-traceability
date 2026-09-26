/**
 * 质量安全与温控业务包 (Quality Control &amp; Temperature Monitoring Domain)。
 * <p>
 * 当前已实现（Phase B PB2）：
 * <ul>
 *   <li>Shipment 在途温度记录：运输任务指定承运组织的操作员在运输途中逐条登记温度测量，数据来源只接受人工登记 (MANUAL)
 *       与教学模拟数据 (SIMULATED)；记录绑定运输任务，不复制到各个批次；</li>
 *   <li>单点判定：按装载批次的产品、TRANSPORT 环节与测量业务时间匹配当时生效的已发布规则版本，得出 NORMAL / HIGH / LOW，
 *       或在没有唯一适用规则时记为 MISSING_CONTEXT，并固定规则环节、上下限与允许越界时长快照，之后不追溯改写。</li>
 * </ul>
 * </p>
 * <p>
 * 尚未实现（不得在文档或演示中宣称已完成）：持续超温判定、Alert、自动风险冻结、隔离收货、检验报告、模拟召回、
 * 仓储 (Batch + Site) 温度记录、批量导入 (IMPORT) 与设备接入 (DEVICE)。单点越界不等于持续超温；批次风险状态只由
 * {@code BatchRiskService} 写入，本包不写入。
 * </p>
 * <p>
 * <b>分层架构定位</b>：质量风控域。严禁将模拟数据标为设备实采；温度记录追加式不可修改或删除。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
package com.example.traceability.quality;
