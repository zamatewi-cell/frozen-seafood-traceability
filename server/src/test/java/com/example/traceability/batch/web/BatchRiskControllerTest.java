package com.example.traceability.batch.web;

import com.example.traceability.batch.application.BatchRiskService;
import com.example.traceability.batch.dto.BatchRiskTransitionRequest;
import com.example.traceability.batch.dto.BatchRiskTransitionResponse;
import com.example.traceability.common.exception.BusinessException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
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

@WebMvcTest(BatchRiskController.class)
@Import({GlobalExceptionHandler.class, RequestIdFilter.class, SecurityConfiguration.class})
@DisplayName("批次风险状态 Web 接口与安全契约测试")
class BatchRiskControllerTest {

    private static final String FREEZE_URL = "/api/v1/batches/3000/risk/freeze";
    private static final String RELEASE_URL = "/api/v1/batches/3000/risk/release";
    private static final String HISTORY_URL = "/api/v1/batches/3000/risk-transitions";
    private static final String KEY = "risk-ctl-key-000000000001";
    private static final String BODY = "{\"reason\":\"来料抽检异常，等待复检\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BatchRiskService batchRiskService;

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

    private static BatchRiskTransitionResponse response(String from, String to) {
        return new BatchRiskTransitionResponse(7001L, 3000L, 30L, "ACTIVE", from, to, "MANUAL", "来料抽检异常，等待复检", 801L,
                OffsetDateTime.of(2026, 9, 23, 1, 30, 15, 123456000, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("匿名冻结 / 解除 / 查询 401 AUTH_REQUIRED，不调用服务")
    void anonymous_unauthorized() throws Exception {
        mockMvc.perform(post(FREEZE_URL).with(csrf()).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mockMvc.perform(post(RELEASE_URL).with(csrf()).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(HISTORY_URL))
                .andExpect(status().isUnauthorized());
        verify(batchRiskService, never()).freeze(any(), any(), any(), any());
        verify(batchRiskService, never()).release(any(), any(), any(), any());
        verify(batchRiskService, never()).listTransitions(any(), any());
    }

    @Test
    @DisplayName("缺失 CSRF 403，不调用服务")
    void missingCsrf_forbidden() throws Exception {
        mockMvc.perform(post(FREEZE_URL).with(user(qm)).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(RELEASE_URL).with(user(qm)).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verify(batchRiskService, never()).freeze(any(), any(), any(), any());
        verify(batchRiskService, never()).release(any(), any(), any(), any());
    }

    @Test
    @DisplayName("冻结 201：返回转换白名单字段（微秒 UTC 时间），不暴露幂等键、请求哈希与登记时间")
    void freeze_created() throws Exception {
        when(batchRiskService.freeze(eq(3000L), any(BatchRiskTransitionRequest.class), eq(KEY), any())).thenReturn(response("NORMAL", "FROZEN"));
        mockMvc.perform(post(FREEZE_URL).with(user(qm)).with(csrf()).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(7001))
                .andExpect(jsonPath("$.data.batchId").value(3000))
                .andExpect(jsonPath("$.data.orgId").value(30))
                .andExpect(jsonPath("$.data.flowStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.fromStatus").value("NORMAL"))
                .andExpect(jsonPath("$.data.toStatus").value("FROZEN"))
                .andExpect(jsonPath("$.data.sourceType").value("MANUAL"))
                .andExpect(jsonPath("$.data.reason").value("来料抽检异常，等待复检"))
                .andExpect(jsonPath("$.data.actorUserId").value(801))
                .andExpect(jsonPath("$.data.occurredAt").value("2026-09-23T01:30:15.123456Z"))
                .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.data.requestHash").doesNotExist())
                .andExpect(jsonPath("$.data.createdAt").doesNotExist());
    }

    @Test
    @DisplayName("解除冻结 201")
    void release_created() throws Exception {
        when(batchRiskService.release(eq(3000L), any(BatchRiskTransitionRequest.class), eq(KEY), any())).thenReturn(response("FROZEN", "NORMAL"));
        mockMvc.perform(post(RELEASE_URL).with(user(qm)).with(csrf()).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.toStatus").value("NORMAL"));
    }

    @Test
    @DisplayName("缺少 reason / 空白 reason 400 INVALID_REQUEST，不调用服务")
    void validation_badRequest() throws Exception {
        for (String body : List.of("{}", "{\"reason\":\"   \"}")) {
            mockMvc.perform(post(FREEZE_URL).with(user(qm)).with(csrf()).header("Idempotency-Key", KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        verify(batchRiskService, never()).freeze(any(), any(), any(), any());
    }

    @Test
    @DisplayName("未声明字段原样收集并交由服务拒绝（不被静默忽略）")
    void unknownFields_forwarded() throws Exception {
        when(batchRiskService.freeze(eq(3000L), any(BatchRiskTransitionRequest.class), eq(KEY), any()))
                .thenAnswer(inv -> {
                    BatchRiskTransitionRequest r = inv.getArgument(1);
                    if (!r.unknownFields().isEmpty()) {
                        throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", String.join(",", r.unknownFields().keySet()));
                    }
                    return response("NORMAL", "FROZEN");
                });
        mockMvc.perform(post(FREEZE_URL).with(user(qm)).with(csrf()).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"抽检\",\"occurredAt\":\"2020-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("occurredAt"));
    }

    @Test
    @DisplayName("业务异常原样映射为 Problem（409 INVALID_STATE_TRANSITION / 403 ORG_SCOPE_DENIED）")
    void businessError_problem() throws Exception {
        when(batchRiskService.freeze(eq(3000L), any(BatchRiskTransitionRequest.class), eq(KEY), any()))
                .thenThrow(new BusinessException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "风险状态转换不允许", "批次已处于风险冻结状态"));
        mockMvc.perform(post(FREEZE_URL).with(user(qm)).with(csrf()).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        when(batchRiskService.listTransitions(eq(3000L), any()))
                .thenThrow(new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权", "无权查看"));
        mockMvc.perform(get(HISTORY_URL).with(user(qm)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_SCOPE_DENIED"));
    }

    @Test
    @DisplayName("查询历史 200：数组按服务返回顺序，不含幂等键与请求哈希")
    void history_ok() throws Exception {
        when(batchRiskService.listTransitions(eq(3000L), any())).thenReturn(List.of(response("NORMAL", "FROZEN"), response("FROZEN", "NORMAL")));
        mockMvc.perform(get(HISTORY_URL).with(user(qm)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].toStatus").value("FROZEN"))
                .andExpect(jsonPath("$.data[1].toStatus").value("NORMAL"))
                .andExpect(jsonPath("$.data[0].idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.data[0].requestHash").doesNotExist());
    }
}
