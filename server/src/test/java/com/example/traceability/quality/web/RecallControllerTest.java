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
import com.example.traceability.quality.application.RecallService;
import com.example.traceability.quality.dto.RecallCloseRequest;
import com.example.traceability.quality.dto.RecallCreateRequest;
import com.example.traceability.quality.dto.RecallResponse;
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

@WebMvcTest(RecallController.class)
@Import({GlobalExceptionHandler.class, RequestIdFilter.class, SecurityConfiguration.class})
@DisplayName("模拟召回 Web 接口与安全契约测试（PB5）")
class RecallControllerTest {

    private static final String KEY = "recall-ctl-key-00000000001";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RecallService recallService;
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

    private static RecallResponse recall(String status) {
        OffsetDateTime t = OffsetDateTime.of(2026, 9, 26, 1, 0, 0, 0, ZoneOffset.UTC);
        return new RecallResponse(9001L, "RCL-1", 30L, null, "检验不合格", status, t, 801L, null, null, null, null, 0L, "OWNER", null, null);
    }

    @Test
    @DisplayName("匿名 401、缺失 CSRF 403，不调用服务")
    void anonymousAndCsrf() throws Exception {
        mockMvc.perform(get("/api/v1/recalls")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/recalls").with(csrf()).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                .content("{\"batchIds\":[1],\"reason\":\"x\"}")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/recalls/9001/close").with(user(qm)).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                .content("{\"publicDisposition\":\"DESTROYED\",\"resultSummary\":\"x\"}")).andExpect(status().isForbidden());
        verify(recallService, never()).start(any(), any(), any());
        verify(recallService, never()).close(any(), any(), any(), any());
    }

    @Test
    @DisplayName("发起 201 / 关闭 200 / 查询：透传请求与幂等键，不暴露幂等键")
    void startCloseAndRead() throws Exception {
        when(recallService.start(argThat((RecallCreateRequest r) -> r.batchIds().equals(List.of(21L, 22L)) && "检验不合格".equals(r.reason())
                && r.unknownFields().isEmpty()), eq(KEY), any())).thenReturn(recall("IN_PROGRESS"));
        mockMvc.perform(post("/api/v1/recalls").with(user(qm)).with(csrf()).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"batchIds\":[21,22],\"reason\":\"检验不合格\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.recallNo").value("RCL-1"))
                .andExpect(jsonPath("$.data.startedAt").value("2026-09-26T01:00:00.000000Z"))
                .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.data.requestHash").doesNotExist());
        when(recallService.close(eq(9001L), argThat((RecallCloseRequest r) -> "DESTROYED".equals(r.publicDisposition())), eq(KEY), any()))
                .thenReturn(recall("CLOSED"));
        mockMvc.perform(post("/api/v1/recalls/9001/close").with(user(qm)).with(csrf()).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"publicDisposition\":\"DESTROYED\",\"resultSummary\":\"已销毁\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));
        when(recallService.listRecalls(any())).thenReturn(List.of(recall("IN_PROGRESS")));
        mockMvc.perform(get("/api/v1/recalls").with(user(qm))).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(9001));
        when(recallService.getRecall(eq(9001L), any())).thenReturn(recall("IN_PROGRESS"));
        mockMvc.perform(get("/api/v1/recalls/9001").with(user(qm))).andExpect(status().isOk()).andExpect(jsonPath("$.data.reason").value("检验不合格"));
    }
}
