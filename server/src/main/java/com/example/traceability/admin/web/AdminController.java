package com.example.traceability.admin.web;

import com.example.traceability.admin.application.AdminApplicationService;
import com.example.traceability.admin.dto.AdminOverviewResponse;
import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统管理控制器（仅系统管理员 ROLE_SYS_ADMIN）。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminApplicationService adminService;

    public AdminController(AdminApplicationService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/overview")
    public SuccessEnvelope<AdminOverviewResponse> overview(
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        return SuccessEnvelope.of(adminService.overview(principal));
    }
}