/**
 * 质量安全与温控告警业务包 (Quality Control & Temperature Monitoring Domain)。
 * <p>
 * 本业务域负责冷链温控监控、超温告警与质量安全召回管控：
 * <ul>
 *   <li>多源温控数据接入：支持人工补录 (MANUAL)、批量导入 (IMPORT)、仿真生成 (SIMULATED) 与 IoT 设备实采 (DEVICE)</li>
 *   <li>超温判定与告警管理：按阶段规则对温控时序数据进行阈值核验，自动触发超温预警</li>
 *   <li>批次冻结与模拟召回：支持根据批次质量异常执行冻结闭环，并支持教学与应急场景下的模拟召回 (simulated=true)</li>
 * </ul>
 * </p>
 * <p>
 * <b>分层架构定位</b>：质量风控与物联网集成域。严禁将模拟仿真数据标为设备实采，真实不合格检测记录不可篡改删除。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
package com.example.traceability.quality;
