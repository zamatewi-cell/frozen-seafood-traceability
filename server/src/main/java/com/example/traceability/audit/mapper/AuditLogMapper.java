package com.example.traceability.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.audit.domain.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 操作审计日志持久层访问接口。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
