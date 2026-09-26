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
import com.example.traceability.quality.application.InspectionReportService;
import com.example.traceability.quality.dto.InspectionReportRequest;
import com.example.traceability.quality.dto.InspectionReportResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InspectionReportController.class)
@Import({GlobalExceptionHandler.class, RequestIdFilter.class, SecurityConfiguration.class})
@DisplayName("检验报告 Web 接口与安全契约测试（PB4）")
class InspectionReportControllerTest {

    private static final String URL = "/api/v1/batches/21/inspection-reports";
    private static final String KEY = "insp-ctl-key-000000000001";
    private static final String BODY = "{\"reportNo\":\"R-1\",\"institutionName\":\"演示检测中心\",\"inspectedAt\":\"2026-09-25T01:00:00Z\","
            + "\"itemsSummary\":\"感官\",\"conclusion\":\"PASS\",\"dataSource\":\"SIMULATED\",\"alertId\":5001}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InspectionReportService reportService;
    @MockitoBean
    private AppUserMapper appUserMapper;
    @MockitoBean
    private OrganizationMapper organizationMapper;
    @MockitoBean
    private RoleMapper roleMapper;
    @MockitoBean
    private UserRoleMapper userRoleMapper;

    private TraceSecurityPrincipal qm;

    @BeforeEach
    void setUp() {
        qm = new TraceSecurityPrincipal(801L, "ret_qm", "零售质量管理员", "{noop}pwd",
                60L, "ORG_RET", "零售企业", "RETAILER", List.of("QUALITY_MANAGER"), List.of("ORG_ONLY"), true, true);
        AppUser u = new AppUser();
        u.setId(801L);
        u.setOrgId(60L);
        u.setStatus("ACTIVE");
        u.setIsDeleted(0);
        when(appUserMapper.selectById(801L)).thenReturn(u);
        Organization org = new Organization();
        org.setId(60L);
        org.setStatus("ACTIVE");
        org.setIsDeleted(0);
        when(organizationMapper.selectById(60L)).thenReturn(org);
        Role role = new Role();
        role.setRoleCode("QUALITY_MANAGER");
        role.setScopeType("ORG_ONLY");
        role.setStatus("ACTIVE");
        role.setIsDeleted(0);
        when(roleMapper.findActiveRolesByUserId(801L)).thenReturn(List.of(role));
    }

    private static InspectionReportResponse report() {
        OffsetDateTime t = OffsetDateTime.of(2026, 9, 25, 1, 0, 0, 0, ZoneOffset.UTC);
        return new InspectionReportResponse(9001L, 21L, 60L, "QUARANTINE_RECEIVER", 501L, 5001L, "R-1", "演示检测中心", t, "感官", "PASS",
                "SIMULATED", 801L, t.plusMinutes(5));
    }

    @Test
    @DisplayName("匿名 401、缺失 CSRF 403，不调用服务")
    void anonymousAndCsrf() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).with(csrf()).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).with(user(qm)).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verify(reportService, never()).submit(any(), any(), any(), any());
    }

    @Test
    @DisplayName("提交 201：透传请求字段与幂等键，返回白名单字段（不暴露幂等键 / 请求哈希）；查询 200")
    void submitAndList() throws Exception {
        when(reportService.submit(eq(21L), argThat((InspectionReportRequest r) -> "R-1".equals(r.reportNo()) && r.alertId() == 5001L
                && r.unknownFields().isEmpty()), eq(KEY), any())).thenReturn(report());
        mockMvc.perform(post(URL).with(user(qm)).with(csrf()).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.submitterRole").value("QUARANTINE_RECEIVER"))
                .andExpect(jsonPath("$.data.inspectedAt").value("2026-09-25T01:00:00.000000Z"))
                .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.data.requestHash").doesNotExist());
        when(reportService.listReports(eq(21L), any())).thenReturn(List.of(report()));
        mockMvc.perform(get(URL).with(user(qm))).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].reportNo").value("R-1"));
    }
}
