package com.example.traceability.identity.web;

import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.GlobalExceptionHandler;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.common.filter.RequestIdFilter;
import com.example.traceability.identity.application.OrganizationDirectoryApplicationService;
import com.example.traceability.identity.config.SecurityConfiguration;
import com.example.traceability.identity.domain.AppUser;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Role;
import com.example.traceability.identity.dto.OrganizationSummaryResponse;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrganizationDirectoryController.class)
@Import({GlobalExceptionHandler.class, RequestIdFilter.class, SecurityConfiguration.class})
@DisplayName("组织目录最小只读 Web 接口测试")
class OrganizationDirectoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrganizationDirectoryApplicationService organizationDirectoryService;

    @MockitoBean
    private AppUserMapper appUserMapper;

    @MockitoBean
    private OrganizationMapper organizationMapper;

    @MockitoBean
    private RoleMapper roleMapper;

    @MockitoBean
    private UserRoleMapper userRoleMapper;

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
    private com.example.traceability.identity.mapper.SiteMapper siteMapper;

    @MockitoBean
    private com.example.traceability.trace.mapper.TraceEventMapper traceEventMapper;

    @MockitoBean
    private com.example.traceability.trace.mapper.PublicTraceCodeMapper publicTraceCodeMapper;

    @MockitoBean
    private com.example.traceability.trace.mapper.PublicTraceCodeIdempotencyMapper publicTraceCodeIdempotencyMapper;


    private TraceSecurityPrincipal operatorPrincipal;

    @BeforeEach
    void setUp() {
        operatorPrincipal = new TraceSecurityPrincipal(
                101L, "operator1", "企业操作员", "{noop}pwd",
                10L, "ORG_FISHERY_01", "第一远洋捕捞公司", "SOURCE",
                List.of("OPERATOR"), List.of("ORG_ONLY"), true, true
        );

        AppUser activeUser = new AppUser();
        activeUser.setId(101L);
        activeUser.setOrgId(10L);
        activeUser.setStatus("ACTIVE");
        activeUser.setIsDeleted(0);
        when(appUserMapper.selectById(101L)).thenReturn(activeUser);

        Organization activeOrg = new Organization();
        activeOrg.setId(10L);
        activeOrg.setStatus("ACTIVE");
        activeOrg.setIsDeleted(0);
        when(organizationMapper.selectById(10L)).thenReturn(activeOrg);

        Role operatorRole = new Role();
        operatorRole.setRoleCode("OPERATOR");
        operatorRole.setScopeType("ORG_ONLY");
        operatorRole.setStatus("ACTIVE");
        when(roleMapper.findActiveRolesByUserId(101L)).thenReturn(List.of(operatorRole));
    }

    @Test
    @DisplayName("匿名读取组织目录返回 401 AUTH_REQUIRED")
    void anonymous_Unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/10"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    @DisplayName("读取本组织返回白名单摘要且不含 creditCode 与 version")
    void ownOrganization_ReturnsWhitelistedSummary() throws Exception {
        when(organizationDirectoryService.getOrganization(eq(10L), any(TraceSecurityPrincipal.class)))
                .thenReturn(new OrganizationSummaryResponse(10L, "ORG_FISHERY_01", "第一远洋捕捞公司", "SOURCE", "ACTIVE"));

        mockMvc.perform(get("/api/v1/organizations/10").with(user(operatorPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.orgNo").value("ORG_FISHERY_01"))
                .andExpect(jsonPath("$.data.name").value("第一远洋捕捞公司"))
                .andExpect(jsonPath("$.data.orgType").value("SOURCE"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.creditCode").doesNotExist())
                .andExpect(jsonPath("$.data.version").doesNotExist())
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("读取其他组织返回 403 ORG_SCOPE_DENIED")
    void otherOrganization_Forbidden() throws Exception {
        when(organizationDirectoryService.getOrganization(eq(20L), any(TraceSecurityPrincipal.class)))
                .thenThrow(new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权", "无权访问其他组织的目录信息"));

        mockMvc.perform(get("/api/v1/organizations/20").with(user(operatorPrincipal)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_SCOPE_DENIED"));
    }

    @Test
    @DisplayName("组织不存在返回 404 RESOURCE_NOT_FOUND")
    void missingOrganization_NotFound() throws Exception {
        when(organizationDirectoryService.getOrganization(eq(99L), any(TraceSecurityPrincipal.class)))
                .thenThrow(new ResourceNotFoundException("未找到 ID 为 99 的组织"));

        mockMvc.perform(get("/api/v1/organizations/99").with(user(operatorPrincipal)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
