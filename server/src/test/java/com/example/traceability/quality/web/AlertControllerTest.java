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
import com.example.traceability.quality.application.AlertApplicationService;
import com.example.traceability.quality.dto.AlertAcknowledgeRequest;
import com.example.traceability.quality.dto.AlertDecisionRequest;
import com.example.traceability.quality.dto.AlertResponse;
import com.example.traceability.quality.dto.TemperatureRecordResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AlertController.class)
@Import({GlobalExceptionHandler.class, RequestIdFilter.class, SecurityConfiguration.class})
@DisplayName("告警 Web 接口与安全契约测试（PB3）")
class AlertControllerTest {

    private static final String KEY = "alert-ctl-key-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AlertApplicationService alertService;
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
        qm = new TraceSecurityPrincipal(801L, "prc_qm", "加工质量管理员", "{noop}pwd",
                30L, "ORG_PRC", "加工企业", "PROCESSOR", List.of("QUALITY_MANAGER"), List.of("ORG_ONLY"), true, true);
        AppUser u = new AppUser();
        u.setId(801L);
        u.setOrgId(30L);
        u.setStatus("ACTIVE");
        u.setIsDeleted(0);
        when(appUserMapper.selectById(801L)).thenReturn(u);
        Organization org = new Organization();
        org.setId(30L);
        org.setStatus("ACTIVE");
        org.setIsDeleted(0);
        when(organizationMapper.selectById(30L)).thenReturn(org);
        Role role = new Role();
        role.setRoleCode("QUALITY_MANAGER");
        role.setScopeType("ORG_ONLY");
        role.setStatus("ACTIVE");
        role.setIsDeleted(0);
        when(roleMapper.findActiveRolesByUserId(801L)).thenReturn(List.of(role));
    }

    private static AlertResponse alert(String status) {
        OffsetDateTime t = OffsetDateTime.of(2026, 9, 25, 1, 10, 0, 123456000, ZoneOffset.UTC);
        return new AlertResponse(5001L, "ALT-20260925011000-123456", "TEMP_OVER_UPPER", "HIGH", status, "在途温度连续高于上限",
                30L, 601L, "SHP-1", 60L, 50L, "TRANSPORT", 701L, 702L, t, t.plusMinutes(30), 1800,
                new TemperatureRecordResponse.RuleBasis(51L, "运输规则", 1, 61L, new BigDecimal("-25.00"), new BigDecimal("-15.00"), 1800),
                t.plusMinutes(31), null, null, null, null, null, 0L, List.of(), List.of());
    }

    @Test
    @DisplayName("匿名查询与确认 401，不调用服务；确认缺失 CSRF 403")
    void anonymousAndCsrf() throws Exception {
        mockMvc.perform(get("/api/v1/alerts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/alerts/5001")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/alerts/5001/acknowledge").with(csrf()).header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/alerts/5001/acknowledge").with(user(qm)).header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        verify(alertService, never()).acknowledge(any(), any(), any(), any());
        verify(alertService, never()).listAlerts(any(), any(), any());
    }

    @Test
    @DisplayName("列表透传状态与运输任务过滤；详情返回白名单字段（微秒 UTC 时间、判定依据快照），不暴露幂等键")
    void listAndDetail() throws Exception {
        when(alertService.listAlerts(eq("OPEN"), eq(601L), any())).thenReturn(List.of(alert("OPEN")));
        mockMvc.perform(get("/api/v1/alerts").param("status", "OPEN").param("shipmentId", "601").with(user(qm)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(5001))
                .andExpect(jsonPath("$.data[0].alertType").value("TEMP_OVER_UPPER"));
        when(alertService.getAlert(eq(5001L), any())).thenReturn(alert("OPEN"));
        mockMvc.perform(get("/api/v1/alerts/5001").with(user(qm)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.episodeStartedAt").value("2026-09-25T01:10:00.123456Z"))
                .andExpect(jsonPath("$.data.rule.allowedDurationSeconds").value(1800))
                .andExpect(jsonPath("$.data.durationSeconds").value(1800))
                .andExpect(jsonPath("$.data.acknowledgedAt").doesNotExist())
                .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist());
        when(alertService.listAlerts(isNull(), isNull(), any())).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/alerts").with(user(qm))).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("确认 200：透传幂等键与说明；未声明字段交由服务拒绝")
    void acknowledge() throws Exception {
        when(alertService.acknowledge(eq(5001L), argThat((AlertAcknowledgeRequest r) -> "已确认".equals(r.note())), eq(KEY), any()))
                .thenReturn(alert("ACKNOWLEDGED"));
        mockMvc.perform(post("/api/v1/alerts/5001/acknowledge").with(user(qm)).with(csrf()).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"已确认\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACKNOWLEDGED"));
        verify(alertService).acknowledge(eq(5001L), argThat((AlertAcknowledgeRequest r) -> r.unknownFields().isEmpty()), eq(KEY), any());

        mockMvc.perform(post("/api/v1/alerts/5001/acknowledge").with(user(qm)).with(csrf()).header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"已确认\",\"status\":\"RESOLVED\"}"));
        verify(alertService).acknowledge(eq(5001L), argThat((AlertAcknowledgeRequest r) -> r.unknownFields().containsKey("status")),
                eq(KEY), any());
    }

    @Test
    @DisplayName("PB4 放行与处置结论：匿名 401、缺失 CSRF 403；透传告警 / 批次、幂等键与说明 / 结论")
    void releaseAndResolve() throws Exception {
        mockMvc.perform(post("/api/v1/alerts/5001/batches/21/release").with(csrf()).header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/alerts/5001/resolve").with(user(qm)).header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"resolution\":\"x\"}")).andExpect(status().isForbidden());
        verify(alertService, never()).releaseBatch(any(), any(), any(), any(), any());
        verify(alertService, never()).resolve(any(), any(), any(), any());

        when(alertService.releaseBatch(eq(5001L), eq(21L), argThat((AlertDecisionRequest r) -> "复检合格".equals(r.note())), eq(KEY), any()))
                .thenReturn(alert("ACKNOWLEDGED"));
        mockMvc.perform(post("/api/v1/alerts/5001/batches/21/release").with(user(qm)).with(csrf()).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"复检合格\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(5001));
        when(alertService.resolve(eq(5001L), argThat((AlertDecisionRequest r) -> "处置完毕".equals(r.resolution())), eq(KEY), any()))
                .thenReturn(alert("RESOLVED"));
        mockMvc.perform(post("/api/v1/alerts/5001/resolve").with(user(qm)).with(csrf()).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resolution\":\"处置完毕\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"));
    }
}
