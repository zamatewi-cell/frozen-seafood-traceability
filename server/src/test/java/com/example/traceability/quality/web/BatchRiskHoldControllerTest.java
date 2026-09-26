package com.example.traceability.quality.web;

import com.example.traceability.common.exception.GlobalExceptionHandler;
import com.example.traceability.common.filter.RequestIdFilter;
import com.example.traceability.identity.config.SecurityConfiguration;
import com.example.traceability.identity.domain.AppUser;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Role;
import com.example.traceability.identity.mapper.AppUserMapper;
import com.example.traceability.identity.mapper.OrganizationMapper;
import com.example.traceability.identity.mapper.RoleMapper;
import com.example.traceability.identity.mapper.UserRoleMapper;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.quality.application.BatchRiskHoldService;
import com.example.traceability.quality.dto.BatchRiskHoldsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BatchRiskHoldController.class)
@Import({GlobalExceptionHandler.class, RequestIdFilter.class, SecurityConfiguration.class})
@DisplayName("批次风险事项查询 Web 接口与安全契约测试（独立评审修复）")
class BatchRiskHoldControllerTest {

    private static final String URL = "/api/v1/batches/21/risk-holds";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BatchRiskHoldService holdService;
    @MockitoBean
    private AppUserMapper appUserMapper;
    @MockitoBean
    private OrganizationMapper organizationMapper;
    @MockitoBean
    private RoleMapper roleMapper;
    @MockitoBean
    private UserRoleMapper userRoleMapper;

    private TraceSecurityPrincipal operator;

    @BeforeEach
    void setUp() {
        operator = new TraceSecurityPrincipal(802L, "ret_op", "零售操作员", "{noop}pwd",
                60L, "ORG_RET", "零售企业", "RETAILER", List.of("OPERATOR"), List.of("ORG_ONLY"), true, true);
        AppUser u = new AppUser();
        u.setId(802L);
        u.setOrgId(60L);
        u.setStatus("ACTIVE");
        u.setIsDeleted(0);
        when(appUserMapper.selectById(802L)).thenReturn(u);
        Organization org = new Organization();
        org.setId(60L);
        org.setStatus("ACTIVE");
        org.setIsDeleted(0);
        when(organizationMapper.selectById(60L)).thenReturn(org);
        Role role = new Role();
        role.setRoleCode("OPERATOR");
        role.setScopeType("ORG_ONLY");
        role.setStatus("ACTIVE");
        role.setIsDeleted(0);
        when(roleMapper.findActiveRolesByUserId(802L)).thenReturn(List.of(role));
    }

    @Test
    @DisplayName("匿名 401，不调用服务")
    void anonymousRejected() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        verify(holdService, never()).getRiskHolds(any(), any());
    }

    @Test
    @DisplayName("当前责任组织查询 200：未解除的告警风险事项、人工冻结与上游召回通知（只含白名单字段）")
    void currentHolderReadsHolds() throws Exception {
        BatchRiskHoldsResponse body = new BatchRiskHoldsResponse(21L, "FROZEN",
                List.of(new BatchRiskHoldsResponse.AlertHold(5001L, "ALT-1", "ACKNOWLEDGED")), true,
                List.of(new BatchRiskHoldsResponse.Notice(7001L, "RCL-1", "IN_PROGRESS", 30L,
                        OffsetDateTime.of(2026, 9, 27, 1, 0, 0, 0, ZoneOffset.UTC), 1)));
        when(holdService.getRiskHolds(eq(21L), any())).thenReturn(body);
        mockMvc.perform(get(URL).with(user(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.riskStatus").value("FROZEN"))
                .andExpect(jsonPath("$.data.alertHolds[0].alertNo").value("ALT-1"))
                .andExpect(jsonPath("$.data.manualFreezeHold").value(true))
                .andExpect(jsonPath("$.data.recallNotices[0].recallNo").value("RCL-1"))
                .andExpect(jsonPath("$.data.recallNotices[0].notifiedAt").value("2026-09-27T01:00:00.000000Z"))
                .andExpect(jsonPath("$.data.recallNotices[0].reason").doesNotExist());
    }
}
