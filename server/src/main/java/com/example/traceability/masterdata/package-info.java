/**
 * 基础主数据业务包 (Master Data Management Domain)。
 * <p>
 * 本业务域管理冻品海鲜追溯系统的基础静态与字典配置数据：
 * <ul>
 *   <li>组织机构与站点：包含企业档案、捕捞港口、加工车间、冷库与流通节点</li>
 *   <li>产品主数据：海鲜商品目录、学名、条形码、存储温区标准</li>
 *   <li>冷链温控阈值规则：分环节温度控制标准与阶段规则版本化定义</li>
 * </ul>
 * </p>
 * <p>
 * <b>分层架构定位</b>：基础支撑域。提供全局唯一的业务编码标准与引用字典；已发布且生效的温控规则严禁物理修改与删除。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
package com.example.traceability.masterdata;
