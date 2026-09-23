package com.example.traceability.trace.web;

import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.GlobalExceptionHandler;
import com.example.traceability.common.exception.ResourceNotFoundException;
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
import com.example.traceability.trace.application.PublicTraceApplicationService;
import com.example.traceability.trace.dto.PublicTraceCodeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批次公开追溯码企业端控制器 Web 切片与权限契约测试。
 * <p>
 * 验证 Session 认证、CSRF 防护、Idempotency-Key 头、权限隔离及响应白名单封套。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@WebMvcTest(PublicTraceCodeController.class)
@Import({GlobalExceptionHandler.class, RequestIdFilter.class, SecurityConfiguration.class})
@DisplayName("批次公开追溯码企业端 Web 契约与权限测试")
class PublicTraceCodeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicTraceApplicationService publicTraceService;

    @MockitoBean
    private AppUserMapper appUserMapper;

    @MockitoBean
    private OrganizationMapper organizationMapper;

    @MockitoBean
    private RoleMapper roleMapper;

    @MockitoBean
    private UserRoleMapper userRoleMapper;

    @MockitoBean
    private com.example.traceability.identity.mapper.SiteMapper siteMapper;

    @MockitoBean
    private com.example.traceability.masterdata.mapper.ProductMapper productMapper;

    @MockitoBean
    private com.example.traceability.masterdata.mapper.TemperatureRuleMapper temperatureRuleMapper;

    @MockitoBean
    private com.example.traceability.masterdata.mapper.TemperatureRuleStageMapper temperatureRuleStageMapper;

    @MockitoBean
    private com.example.traceability.batch.mapper.BatchMapper batchMapper;

    @MockitoBean
    private com.example.traceability.batch.mapper.BatchOperationMapper batchOperationMapper;

    @MockitoBean
    private com.example.traceability.batch.mapper.BatchOperationItemMapper batchOperationItemMapper;

    @MockitoBean
    private com.example.traceability.batch.mapper.BatchRelationMapper batchRelationMapper;

    @MockitoBean
    private com.example.traceability.trace.mapper.TraceEventMapper traceEventMapper;

    @MockitoBean
    private com.example.traceability.trace.mapper.PublicTraceCodeMapper publicTraceCodeMapper;

    @MockitoBean
    private com.example.traceability.trace.mapper.PublicTraceCodeIdempotencyMapper publicTraceCodeIdempotencyMapper;

    private TraceSecurityPrincipal operatorPrincipal;
    private static final String VALID_KEY = "idem-key-1234567890abcdef";

    @BeforeEach
    void setUp() {
        operatorPrincipal = new TraceSecurityPrincipal(
                101L, "operator1", "操作员", "{noop}pwd",
                10L, "ORG001", "东海捕捞集团", "SOURCE",
                List.of("OPERATOR"), List.of("ORG_ONLY"), true, true
        );

        AppUser mockUser = new AppUser();
        mockUser.setId(101L);
        mockUser.setOrgId(10L);
        mockUser.setUsername("operator1");
        mockUser.setStatus("ACTIVE");
        mockUser.setIsDeleted(0);
        when(appUserMapper.selectById(101L)).thenReturn(mockUser);

        Organization mockOrg = new Organization();
        mockOrg.setId(10L);
        mockOrg.setStatus("ACTIVE");
        mockOrg.setIsDeleted(0);
        when(organizationMapper.selectById(10L)).thenReturn(mockOrg);

        Role mockRole = new Role();
        mockRole.setRoleCode("OPERATOR");
        mockRole.setScopeType("ORG_ONLY");
        mockRole.setStatus("ACTIVE");
        when(roleMapper.findActiveRolesByUserId(101L)).thenReturn(List.of(mockRole));
    }

    @Test
    @DisplayName("匿名访问激活端点被安全拦截 (401)")
    void testActivateAnonymousUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/batches/100/public-trace-code/activate")
                        .with(csrf())
                        .header("Idempotency-Key", VALID_KEY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("缺失 CSRF 访问激活端点被安全拦截 (403)")
    void testActivateMissingCsrfForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/batches/100/public-trace-code/activate")
                        .with(user(operatorPrincipal))
                        .header("Idempotency-Key", VALID_KEY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("企业操作员带 CSRF 与 Idempotency-Key 正常激活公开追溯码成功 (200)")
    void testActivateSuccess() throws Exception {
        PublicTraceCodeResponse resp = new PublicTraceCodeResponse(
                1L, 100L, "ABCDEF234567890ABCDEF2345", "ACTIVE",
                "2026-09-10T10:00:00Z", null, "2026-09-10T10:00:00Z", "2026-09-10T10:00:00Z"
        );
        when(publicTraceService.activatePublicTraceCode(eq(100L), eq(VALID_KEY), any())).thenReturn(resp);

        mockMvc.perform(post("/api/v1/batches/100/public-trace-code/activate")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicId").value("ABCDEF234567890ABCDEF2345"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.batchId").value(100))
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("停用公开追溯码成功 (200)")
    void testDisableSuccess() throws Exception {
        PublicTraceCodeResponse resp = new PublicTraceCodeResponse(
                1L, 100L, "ABCDEF234567890ABCDEF2345", "DISABLED",
                "2026-09-10T10:00:00Z", "2026-09-10T11:00:00Z", "2026-09-10T10:00:00Z", "2026-09-10T11:00:00Z"
        );
        when(publicTraceService.disablePublicTraceCode(eq(100L), eq(VALID_KEY), any())).thenReturn(resp);

        mockMvc.perform(post("/api/v1/batches/100/public-trace-code/disable")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"))
                .andExpect(jsonPath("$.data.disabledAt").isNotEmpty());
    }

    @Test
    @DisplayName("非 ACTIVE 批次激活被拦截 (422 BATCH_FLOW_BLOCKED)")
    void testActivateBlockedBatch() throws Exception {
        when(publicTraceService.activatePublicTraceCode(eq(100L), eq(VALID_KEY), any()))
                .thenThrow(new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "BATCH_FLOW_BLOCKED", "批次状态不允许", "仅 ACTIVE 批次允许激活"));

        mockMvc.perform(post("/api/v1/batches/100/public-trace-code/activate")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_KEY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_FLOW_BLOCKED"));
    }

    @Test
    @DisplayName("跨组织访问被拦截 (403 ORG_SCOPE_DENIED)")
    void testActivateCrossOrgDenied() throws Exception {
        when(publicTraceService.activatePublicTraceCode(eq(100L), eq(VALID_KEY), any()))
                .thenThrow(new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "越权访问", "无权访问其他组织批次"));

        mockMvc.perform(post("/api/v1/batches/100/public-trace-code/activate")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_SCOPE_DENIED"));
    }

    @Test
    @DisplayName("批次不存在返回 (404 RESOURCE_NOT_FOUND)")
    void testActivateBatchNotFound() throws Exception {
        when(publicTraceService.activatePublicTraceCode(eq(9999L), eq(VALID_KEY), any()))
                .thenThrow(new ResourceNotFoundException("未找到批次 9999"));

        mockMvc.perform(post("/api/v1/batches/9999/public-trace-code/activate")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("幂等键混用冲突拦截 (409 IDEMPOTENCY_KEY_REUSED)")
    void testActivateIdempotencyReused() throws Exception {
        when(publicTraceService.activatePublicTraceCode(eq(100L), eq(VALID_KEY), any()))
                .thenThrow(new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "幂等冲突", "幂等键已被混用"));

        mockMvc.perform(post("/api/v1/batches/100/public-trace-code/activate")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }
    @Test
    @DisplayName("GET 当前公开追溯码：匿名访问 401，且不调用服务")
    void testGetCurrentAnonymousUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/batches/100/public-trace-code"))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verifyNoInteractions(publicTraceService);
    }

    @Test
    @DisplayName("GET 当前公开追溯码：已登录读取无需 CSRF 与 Idempotency-Key，返回企业端白名单 (200)")
    void testGetCurrentSuccess() throws Exception {
        PublicTraceCodeResponse resp = new PublicTraceCodeResponse(
                1L, 100L, "ABCDEF234567ABCDEF234567AB", "ACTIVE",
                "2026-09-10T10:00:00Z", null, "2026-09-10T10:00:00Z", "2026-09-10T10:00:00Z"
        );
        when(publicTraceService.getCurrentPublicTraceCode(eq(100L), any())).thenReturn(resp);

        mockMvc.perform(get("/api/v1/batches/100/public-trace-code").with(user(operatorPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicId").value("ABCDEF234567ABCDEF234567AB"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.tokenHash").doesNotExist())
                .andExpect(jsonPath("$.data.orgId").doesNotExist());
    }

    @Test
    @DisplayName("GET 当前公开追溯码：尚未激活 404 PUBLIC_TRACE_CODE_NOT_FOUND；他组织 403 ORG_SCOPE_DENIED")
    void testGetCurrentNotFoundAndForbidden() throws Exception {
        when(publicTraceService.getCurrentPublicTraceCode(eq(100L), any()))
                .thenThrow(new ResourceNotFoundException("PUBLIC_TRACE_CODE_NOT_FOUND", "公开追溯码未激活", "该批次尚未激活公开追溯码"));
        mockMvc.perform(get("/api/v1/batches/100/public-trace-code").with(user(operatorPrincipal)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PUBLIC_TRACE_CODE_NOT_FOUND"));

        when(publicTraceService.getCurrentPublicTraceCode(eq(200L), any()))
                .thenThrow(new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权", "只有批次当前责任组织可以查看该批次的公开追溯码"));
        mockMvc.perform(get("/api/v1/batches/200/public-trace-code").with(user(operatorPrincipal)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_SCOPE_DENIED"));
    }
}
