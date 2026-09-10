package com.example.traceability.masterdata.web;

import com.example.traceability.common.envelope.PageMeta;
import com.example.traceability.common.envelope.SuccessEnvelope;
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
import com.example.traceability.masterdata.application.ProductApplicationService;
import com.example.traceability.masterdata.dto.ProductCreateRequest;
import com.example.traceability.masterdata.dto.ProductPatchRequest;
import com.example.traceability.masterdata.dto.ProductQueryCriteria;
import com.example.traceability.masterdata.dto.ProductResponse;
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
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
@Import({GlobalExceptionHandler.class, RequestIdFilter.class, SecurityConfiguration.class})
@DisplayName("产品主数据 Web 接口与权限契约测试")
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProductApplicationService productService;

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

    private TraceSecurityPrincipal platformPrincipal;
    private TraceSecurityPrincipal operatorPrincipal;

    @BeforeEach
    void setUp() {
        // 平台管理员主体 (ID=1, OrgID=10, PLATFORM scope)
        platformPrincipal = new TraceSecurityPrincipal(
                1L, "admin", "平台管理员", "{noop}pwd",
                10L, "ORG_PLATFORM", "溯源监管中心", "SOURCE",
                List.of("ADMIN"), List.of("PLATFORM"), true, true
        );

        AppUser adminUser = new AppUser();
        adminUser.setId(1L);
        adminUser.setUsername("admin");
        adminUser.setOrgId(10L);
        adminUser.setStatus("ACTIVE");
        adminUser.setIsDeleted(0);
        when(appUserMapper.selectById(1L)).thenReturn(adminUser);

        Organization platformOrg = new Organization();
        platformOrg.setId(10L);
        platformOrg.setOrgNo("ORG_PLATFORM");
        platformOrg.setStatus("ACTIVE");
        platformOrg.setIsDeleted(0);
        when(organizationMapper.selectById(10L)).thenReturn(platformOrg);

        Role adminRole = new Role();
        adminRole.setId(1L);
        adminRole.setRoleCode("ADMIN");
        adminRole.setScopeType("PLATFORM");
        adminRole.setStatus("ACTIVE");
        adminRole.setIsDeleted(0);
        when(roleMapper.findActiveRolesByUserId(1L)).thenReturn(List.of(adminRole));

        // 普通企业操作员主体 (ID=2, OrgID=20, ORG_ONLY scope)
        operatorPrincipal = new TraceSecurityPrincipal(
                2L, "operator", "企业操作员", "{noop}pwd",
                20L, "ORG_FISHERY", "东海捕捞公司", "SOURCE",
                List.of("OPERATOR"), List.of("ORG_ONLY"), true, true
        );

        AppUser opUser = new AppUser();
        opUser.setId(2L);
        opUser.setUsername("operator");
        opUser.setOrgId(20L);
        opUser.setStatus("ACTIVE");
        opUser.setIsDeleted(0);
        when(appUserMapper.selectById(2L)).thenReturn(opUser);

        Organization opOrg = new Organization();
        opOrg.setId(20L);
        opOrg.setOrgNo("ORG_FISHERY");
        opOrg.setStatus("ACTIVE");
        opOrg.setIsDeleted(0);
        when(organizationMapper.selectById(20L)).thenReturn(opOrg);

        Role opRole = new Role();
        opRole.setId(2L);
        opRole.setRoleCode("OPERATOR");
        opRole.setScopeType("ORG_ONLY");
        opRole.setStatus("ACTIVE");
        opRole.setIsDeleted(0);
        when(roleMapper.findActiveRolesByUserId(2L)).thenReturn(List.of(opRole));
    }

    // =========================================================================
    // 1. 匿名访问受限测试 (401 AUTH_REQUIRED)
    // =========================================================================

    @Test
    @DisplayName("匿名访问产品列表接口应返回 401 AUTH_REQUIRED")
    void anonymousListProductsReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    @DisplayName("匿名访问产品详情接口应返回 401 AUTH_REQUIRED")
    void anonymousGetProductReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/products/100"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("匿名访问产品创建接口应返回 401 AUTH_REQUIRED")
    void anonymousCreateProductReturns401() throws Exception {
        ProductCreateRequest req = new ProductCreateRequest(
                "PRD-001", "大黄鱼", null, "FISH", "500g", "DOMESTIC_CAPTURE", "kg", "ACTIVE"
        );
        mockMvc.perform(post("/api/v1/products")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    @DisplayName("匿名访问产品 PATCH 更新接口应返回 401 AUTH_REQUIRED")
    void anonymousPatchProductReturns401() throws Exception {
        ProductPatchRequest req = new ProductPatchRequest(
                0L, null, "新大黄鱼", null, null, null, null, null, null
        );
        mockMvc.perform(patch("/api/v1/products/100")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    // =========================================================================
    // 2. 普通已登录用户访问测试 (读成功 200，写被拒绝 403 ACCESS_DENIED)
    // =========================================================================

    @Test
    @DisplayName("普通已登录用户可以成功读取产品列表 (200 OK)")
    void authenticatedUserCanListProducts() throws Exception {
        ProductResponse item = new ProductResponse(
                100L, "PRD-YELLOW-001", "东海大黄鱼", "Larimichthys crocea",
                "FISH", "条冻 500-600g", "DOMESTIC_CAPTURE", "kg", "ACTIVE",
                0L, OffsetDateTime.now(ZoneOffset.UTC), 1L, OffsetDateTime.now(ZoneOffset.UTC), 1L
        );
        PageMeta pageMeta = new PageMeta(1, 20, 1L, 1);
        when(productService.listProducts(any(ProductQueryCriteria.class)))
                .thenReturn(SuccessEnvelope.ofPage(List.of(item), pageMeta));

        mockMvc.perform(get("/api/v1/products").with(user(operatorPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(100L))
                .andExpect(jsonPath("$.data[0].productCode").value("PRD-YELLOW-001"))
                .andExpect(jsonPath("$.meta.page.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].isDeleted").doesNotExist());
    }

    @Test
    @DisplayName("普通已登录用户可以成功读取产品详情 (200 OK)")
    void authenticatedUserCanGetProductDetail() throws Exception {
        ProductResponse item = new ProductResponse(
                100L, "PRD-YELLOW-001", "东海大黄鱼", "Larimichthys crocea",
                "FISH", "条冻 500-600g", "DOMESTIC_CAPTURE", "kg", "ACTIVE",
                0L, OffsetDateTime.now(ZoneOffset.UTC), 1L, OffsetDateTime.now(ZoneOffset.UTC), 1L
        );
        when(productService.getProductById(100L)).thenReturn(item);

        mockMvc.perform(get("/api/v1/products/100").with(user(operatorPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(100L))
                .andExpect(jsonPath("$.data.publicName").value("东海大黄鱼"))
                .andExpect(jsonPath("$.data.isDeleted").doesNotExist());
    }

    @Test
    @DisplayName("分页查询参数 page=0 违背最小值约束应被校验拦截返回 400 INVALID_REQUEST")
    void listProductsInvalidPageReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .param("page", "0")
                        .with(user(operatorPrincipal)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("分页查询参数 size=999 违背最大值 100 约束应被校验拦截返回 400 INVALID_REQUEST")
    void listProductsInvalidSizeReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .param("size", "999")
                        .with(user(operatorPrincipal)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("普通已登录用户创建产品主数据必须被拒绝，返回 403 ACCESS_DENIED")
    void operatorCannotCreateProductReturns403() throws Exception {
        ProductCreateRequest req = new ProductCreateRequest(
                "PRD-YELLOW-001", "东海大黄鱼", null, "FISH", "500g", "DOMESTIC_CAPTURE", "kg", "ACTIVE"
        );
        when(productService.createProduct(any(ProductCreateRequest.class), any(TraceSecurityPrincipal.class)))
                .thenThrow(new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "无权访问", "需要 PLATFORM 权限"));

        mockMvc.perform(post("/api/v1/products")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("普通已登录用户更新产品主数据必须被拒绝，返回 403 ACCESS_DENIED")
    void operatorCannotPatchProductReturns403() throws Exception {
        ProductPatchRequest req = new ProductPatchRequest(
                0L, null, "新大黄鱼", null, null, null, null, null, null
        );
        when(productService.patchProduct(eq(100L), any(ProductPatchRequest.class), any(TraceSecurityPrincipal.class)))
                .thenThrow(new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "无权访问", "需要 PLATFORM 权限"));

        mockMvc.perform(patch("/api/v1/products/100")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.status").value(403));
    }

    // =========================================================================
    // 3. 平台管理员 (PLATFORM) 成功与异常路径测试
    // =========================================================================

    @Test
    @DisplayName("PLATFORM 用户正常创建产品，返回 201 CREATED 且不泄露 isDeleted")
    void platformAdminCreatesProductSuccessfully() throws Exception {
        ProductCreateRequest req = new ProductCreateRequest(
                "PRD-CRAB-001", "舟山梭子蟹", "Portunus trituberculatus",
                "CRUSTACEAN", "只冻 200-300g", "DOMESTIC_CAPTURE", "kg", "ACTIVE"
        );

        ProductResponse created = new ProductResponse(
                101L, "PRD-CRAB-001", "舟山梭子蟹", "Portunus trituberculatus",
                "CRUSTACEAN", "只冻 200-300g", "DOMESTIC_CAPTURE", "kg", "ACTIVE",
                0L, OffsetDateTime.now(ZoneOffset.UTC), 1L, OffsetDateTime.now(ZoneOffset.UTC), 1L
        );
        when(productService.createProduct(any(ProductCreateRequest.class), any(TraceSecurityPrincipal.class)))
                .thenReturn(created);

        mockMvc.perform(post("/api/v1/products")
                        .with(user(platformPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(101L))
                .andExpect(jsonPath("$.data.productCode").value("PRD-CRAB-001"))
                .andExpect(jsonPath("$.data.version").value(0L))
                .andExpect(jsonPath("$.data.isDeleted").doesNotExist());
    }

    @Test
    @DisplayName("创建产品时传入非 kg 的 baseUnitCode 返回 400 INVALID_REQUEST")
    void createProductInvalidBaseUnitCodeReturns400() throws Exception {
        String invalidJson = """
                {
                    "productCode": "PRD-ERR-001",
                    "publicName": "测试非kg单位",
                    "category": "FISH",
                    "specification": "500g",
                    "sourceType": "DOMESTIC_CAPTURE",
                    "baseUnitCode": "ton"
                }
                """;

        mockMvc.perform(post("/api/v1/products")
                        .with(user(platformPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("创建产品时重复产品编码返回 409 PRODUCT_CODE_CONFLICT")
    void createProductDuplicateCodeReturns409() throws Exception {
        ProductCreateRequest req = new ProductCreateRequest(
                "PRD-CRAB-001", "舟山梭子蟹", null, "CRUSTACEAN", "200g", "DOMESTIC_CAPTURE", "kg", "ACTIVE"
        );
        when(productService.createProduct(any(ProductCreateRequest.class), any(TraceSecurityPrincipal.class)))
                .thenThrow(new BusinessException(HttpStatus.CONFLICT, "PRODUCT_CODE_CONFLICT", "产品编码冲突", "编码已存在"));

        mockMvc.perform(post("/api/v1/products")
                        .with(user(platformPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_CODE_CONFLICT"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("PATCH 更新产品时携带旧 version 返回 409 VERSION_CONFLICT")
    void patchProductOldVersionReturns409() throws Exception {
        ProductPatchRequest req = new ProductPatchRequest(
                0L, null, "修改名称", null, null, null, null, null, null
        );
        when(productService.patchProduct(eq(100L), any(ProductPatchRequest.class), any(TraceSecurityPrincipal.class)))
                .thenThrow(new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "资源版本冲突", "当前版本不匹配"));

        mockMvc.perform(patch("/api/v1/products/100")
                        .with(user(platformPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("写接口未携带 CSRF Token 应当被安全拦截并返回 403")
    void writeEndpointWithoutCsrfReturnsForbidden() throws Exception {
        ProductCreateRequest req = new ProductCreateRequest(
                "PRD-NO-CSRF", "无CSRF测试", null, "FISH", "100g", "IMPORT", "kg", "ACTIVE"
        );

        mockMvc.perform(post("/api/v1/products")
                        .with(user(platformPrincipal))
                        // 故意不传 csrf()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }
}
