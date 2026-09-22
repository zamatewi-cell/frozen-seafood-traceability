package com.example.traceability.identity.web;

import com.example.traceability.common.exception.GlobalExceptionHandler;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.common.filter.RequestIdFilter;
import com.example.traceability.identity.application.OrganizationDirectoryApplicationService;
import com.example.traceability.identity.config.SecurityConfiguration;
import com.example.traceability.identity.domain.AppUser;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Role;
import com.example.traceability.identity.dto.OrganizationSummaryResponse;
import com.example.traceability.identity.dto.SiteSummaryResponse;
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
    @DisplayName("读取交易对手组织返回同一白名单摘要")
    void otherOrganization_ReturnsWhitelistedSummary() throws Exception {
        when(organizationDirectoryService.getOrganization(eq(20L), any(TraceSecurityPrincipal.class)))
                .thenReturn(new OrganizationSummaryResponse(20L, "ORG_PROC_01", "东海加工", "PROCESSOR", "ACTIVE"));

        mockMvc.perform(get("/api/v1/organizations/20").with(user(operatorPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("东海加工"))
                .andExpect(jsonPath("$.data.creditCode").doesNotExist());
    }

    @Test
    @DisplayName("启用组织目录按类型筛选")
    void listOrganizations_ByType() throws Exception {
        when(organizationDirectoryService.listActiveOrganizations(eq("CARRIER"), any(TraceSecurityPrincipal.class)))
                .thenReturn(List.of(new OrganizationSummaryResponse(30L, "ORG_CAR_01", "冷链承运", "CARRIER", "ACTIVE")));

        mockMvc.perform(get("/api/v1/organizations").param("orgType", "CARRIER").with(user(operatorPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(30))
                .andExpect(jsonPath("$.data[0].orgType").value("CARRIER"));
    }

    @Test
    @DisplayName("组织启用场所目录返回白名单摘要且不含详细地址")
    void listSites_ReturnsWhitelistedSummary() throws Exception {
        when(organizationDirectoryService.listActiveSites(eq(20L), any(TraceSecurityPrincipal.class)))
                .thenReturn(List.of(new SiteSummaryResponse(7L, 20L, "S-7", "加工厂", "FACTORY", "ACTIVE")));

        mockMvc.perform(get("/api/v1/organizations/20/sites").with(user(operatorPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("加工厂"))
                .andExpect(jsonPath("$.data[0].addressText").doesNotExist());
    }

    @Test
    @DisplayName("匿名读取场所目录返回 401")
    void listSites_Anonymous_Unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/20/sites"))
                .andExpect(status().isUnauthorized());
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
