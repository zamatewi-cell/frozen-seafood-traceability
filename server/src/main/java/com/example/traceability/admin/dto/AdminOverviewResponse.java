package com.example.traceability.admin.dto;

import java.util.List;

/**
 * 系统管理概览响应（仅系统管理员可见）。
 */
public record AdminOverviewResponse(
        int orgCount,
        int userCount,
        int roleCount,
        List<AdminOrg> orgs,
        List<AdminUser> users,
        List<AdminRole> roles
) {

    public record AdminOrg(Long id, String orgNo, String name, String orgType, String status) {
    }

    public record AdminUser(Long id, Long orgId, String username, String displayName, String jobType, String status) {
    }

    public record AdminRole(Long id, String roleCode, String name, String scopeType, String status) {
    }
}