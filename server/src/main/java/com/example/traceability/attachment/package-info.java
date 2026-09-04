/**
 * 附件存储与防篡改管理业务包 (Attachment & Secure Storage Domain)。
 * <p>
 * 本业务域负责各类检验检疫凭证、货运发据与多媒体附件的受控存储：
 * <ul>
 *   <li>受控本地存储端口：定义并实现受控存储抽象，隔离物理磁盘路径与上传原文件名</li>
 *   <li>附件完整性与防篡改：计算并记录文件 SHA-256 散列值，支持作废与权限隔离</li>
 *   <li>安全元数据管理：记录文件大小、MIME 类型、存储随机唯一键与上传者信息</li>
 * </ul>
 * </p>
 * <p>
 * <b>分层架构定位</b>：资源与文件基础设施支持域。严禁将用户输入原文件名直接作为存储路径，严禁未经鉴权开放静态物理文件访问。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
package com.example.traceability.attachment;
