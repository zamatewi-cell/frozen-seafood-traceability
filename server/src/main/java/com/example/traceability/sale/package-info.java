/**
 * 终端销售业务包 (Terminal Sale Domain)。
 * <p>
 * 统一业务契约 v1.1 §2.13 / §5 / §9.1：Sale 只表示零售企业 (RETAILER) 在本组织 {@code Site(STORE)} 面向供应链外部消费者的
 * 终端数量出库，是终端销售数量台账与售罄判断的唯一依据。企业之间的买卖仍由 Transfer 表达。
 * 本包不负责订单、支付、发票或消费者身份。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
package com.example.traceability.sale;
