package com.example.traceability.masterdata.web;

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
import com.example.traceability.masterdata.mapper.ProductMapper;
import com.example.traceability.masterdata.mapper.TemperatureRuleMapper;
import com.example.traceability.masterdata.mapper.TemperatureRuleStageMapper;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.masterdata.application.TemperatureRuleApplicationService;
import com.example.traceability.masterdata.dto.TemperatureRuleCreateRequest;
import com.example.traceability.masterdata.dto.TemperatureRuleResponse;
import com.example.traceability.masterdata.dto.TemperatureRuleStageCreateRequest;
import com.example.traceability.masterdata.dto.TemperatureRuleStageResponse;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TemperatureRuleController.class)
@Import({GlobalExceptionHandler.class, RequestIdFilter.class, SecurityConfiguration.class})
@DisplayName("温控基准规则 Web 接口与权限契约测试")
class TemperatureRuleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TemperatureRuleApplicationService ruleService;

    @MockitoBean
    private AppUserMapper appUserMapper;

    @MockitoBean
    private OrganizationMapper organizationMapper;

    @MockitoBean
    private RoleMapper roleMapper;

    @MockitoBean
    private UserRoleMapper userRoleMapper;

    @MockitoBean
    private ProductMapper productMapper;

    @MockitoBean
    private TemperatureRuleMapper temperatureRuleMapper;

    @MockitoBean
    private TemperatureRuleStageMapper temperatureRuleStageMapper;

    @MockitoBean
    private com.example.traceability.batch.mapper.BatchMapper batchMapper;

    @MockitoBean
    private com.example.traceability.batch.mapper.BatchOperationMapper batchOperationMapper;

    @MockitoBean
    private com.example.traceability.batch.mapper.BatchOperationItemMapper batchOperationItemMapper;

    @MockitoBean
    private com.example.traceability.batch.mapper.BatchRelationMapper batchRelationMapper;

    private TraceSecurityPrincipal platformPrincipal;
    private TraceSecurityPrincipal operatorPrincipal;

    @BeforeEach
    void setUp() {
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
    @DisplayName("匿名查询产品温控规则版本返回 401 AUTH_REQUIRED")
    void anonymousListRulesReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/products/100/temperature-rules"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("匿名创建温控规则草稿返回 401 AUTH_REQUIRED")
    void anonymousCreateRuleReturns401() throws Exception {
        TemperatureRuleCreateRequest req = new TemperatureRuleCreateRequest(
                "匿名测试规则", OffsetDateTime.now(ZoneOffset.UTC), null, null,
                List.of(new TemperatureRuleStageCreateRequest("STORAGE", new BigDecimal("-20"), new BigDecimal("-18"), "CELSIUS", 0, 1))
        );
        mockMvc.perform(post("/api/v1/products/100/temperature-rules")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    @DisplayName("匿名发布温控规则返回 401 AUTH_REQUIRED")
    void anonymousPublishRuleReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/temperature-rules/200/publish")
                        .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    // =========================================================================
    // 2. 普通已登录用户测试 (读成功 200，写被拒绝 403 ACCESS_DENIED)
    // =========================================================================

    @Test
    @DisplayName("普通已登录用户可以成功查询温控规则版本与阶段列表 (200 OK)")
    void authenticatedUserCanListRules() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        TemperatureRuleStageResponse stage = new TemperatureRuleStageResponse(
                1L, 201L, "STORAGE", new BigDecimal("-25.00"), new BigDecimal("-18.00"),
                "CELSIUS", 0, 1, 0L, now, 1L, now, 1L
        );
        TemperatureRuleResponse ruleResp = new TemperatureRuleResponse(
                201L, 100L, 1, "冷冻鱼温控标准v1",
                now, null, "ACTIVE", "国标依据",
                List.of(stage), 0L, now, 1L, now, 1L
        );
        when(ruleService.listRulesByProductId(100L)).thenReturn(SuccessEnvelope.of(List.of(ruleResp)));

        mockMvc.perform(get("/api/v1/products/100/temperature-rules").with(user(operatorPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(201L))
                .andExpect(jsonPath("$.data[0].versionNo").value(1))
                .andExpect(jsonPath("$.data[0].stages[0].stageCode").value("STORAGE"))
                .andExpect(jsonPath("$.data[0].isDeleted").doesNotExist())
                .andExpect(jsonPath("$.data[0].stages[0].isDeleted").doesNotExist());
    }

    @Test
    @DisplayName("普通已登录用户创建温控规则必须被拒绝，返回 403 ACCESS_DENIED")
    void operatorCannotCreateRuleReturns403() throws Exception {
        TemperatureRuleCreateRequest req = new TemperatureRuleCreateRequest(
                "测试规则", OffsetDateTime.now(ZoneOffset.UTC), null, null,
                List.of(new TemperatureRuleStageCreateRequest("STORAGE", new BigDecimal("-20"), new BigDecimal("-18"), "CELSIUS", 0, 1))
        );
        when(ruleService.createDraftRule(eq(100L), any(TemperatureRuleCreateRequest.class), any(TraceSecurityPrincipal.class)))
                .thenThrow(new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "无权访问", "需要平台管理权限"));

        mockMvc.perform(post("/api/v1/products/100/temperature-rules")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("普通已登录用户发布温控规则必须被拒绝，返回 403 ACCESS_DENIED")
    void operatorCannotPublishRuleReturns403() throws Exception {
        when(ruleService.publishRule(eq(200L), any(TraceSecurityPrincipal.class)))
                .thenThrow(new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "无权访问", "需要平台管理权限"));

        mockMvc.perform(post("/api/v1/temperature-rules/200/publish")
                        .with(user(operatorPrincipal))
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.status").value(403));
    }

    // =========================================================================
    // 3. 平台管理员操作与各种校验异常路径
    // =========================================================================

    @Test
    @DisplayName("平台管理员创建规则草稿成功，返回 201 CREATED")
    void platformAdminCreatesRuleSuccessfully() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        TemperatureRuleCreateRequest req = new TemperatureRuleCreateRequest(
                "冷冻大黄鱼温控基准v1", now, null, "农业部标准",
                List.of(
                        new TemperatureRuleStageCreateRequest("PROCESSING", new BigDecimal("-35.00"), new BigDecimal("-30.00"), "CELSIUS", 0, 1),
                        new TemperatureRuleStageCreateRequest("STORAGE", new BigDecimal("-25.00"), new BigDecimal("-18.00"), "CELSIUS", 0, 2)
                )
        );

        TemperatureRuleStageResponse s1 = new TemperatureRuleStageResponse(
                10L, 300L, "PROCESSING", new BigDecimal("-35.00"), new BigDecimal("-30.00"),
                "CELSIUS", 0, 1, 0L, now, 1L, now, 1L
        );
        TemperatureRuleStageResponse s2 = new TemperatureRuleStageResponse(
                11L, 300L, "STORAGE", new BigDecimal("-25.00"), new BigDecimal("-18.00"),
                "CELSIUS", 0, 2, 0L, now, 1L, now, 1L
        );
        TemperatureRuleResponse resp = new TemperatureRuleResponse(
                300L, 100L, 1, "冷冻大黄鱼温控基准v1",
                now, null, "DRAFT", "农业部标准",
                List.of(s1, s2), 0L, now, 1L, now, 1L
        );

        when(ruleService.createDraftRule(eq(100L), any(TemperatureRuleCreateRequest.class), any(TraceSecurityPrincipal.class)))
                .thenReturn(resp);

        mockMvc.perform(post("/api/v1/products/100/temperature-rules")
                        .with(user(platformPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(300L))
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.stages").isArray())
                .andExpect(jsonPath("$.data.stages.length()").value(2))
                .andExpect(jsonPath("$.data.isDeleted").doesNotExist());
    }

    @Test
    @DisplayName("规则反向温度阈值创建失败，返回 422 TEMPERATURE_RULE_INVALID_LIMITS")
    void createRuleInvertedLimitsReturns422() throws Exception {
        TemperatureRuleCreateRequest req = new TemperatureRuleCreateRequest(
                "反向阈值规则", OffsetDateTime.now(ZoneOffset.UTC), null, null,
                List.of(new TemperatureRuleStageCreateRequest("STORAGE", new BigDecimal("-15"), new BigDecimal("-18"), "CELSIUS", 0, 1))
        );

        when(ruleService.createDraftRule(eq(100L), any(TemperatureRuleCreateRequest.class), any(TraceSecurityPrincipal.class)))
                .thenThrow(new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "TEMPERATURE_RULE_INVALID_LIMITS", "温控阈值非法", "下限不得高于上限"));

        mockMvc.perform(post("/api/v1/products/100/temperature-rules")
                        .with(user(platformPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TEMPERATURE_RULE_INVALID_LIMITS"))
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    @DisplayName("规则错误温标单位创建失败，返回 422 INVALID_TEMPERATURE_UNIT")
    void createRuleInvalidUnitReturns422() throws Exception {
        TemperatureRuleCreateRequest req = new TemperatureRuleCreateRequest(
                "错误温标规则", OffsetDateTime.now(ZoneOffset.UTC), null, null,
                List.of(new TemperatureRuleStageCreateRequest("STORAGE", new BigDecimal("-20"), new BigDecimal("-18"), "KELVIN", 0, 1))
        );

        when(ruleService.createDraftRule(eq(100L), any(TemperatureRuleCreateRequest.class), any(TraceSecurityPrincipal.class)))
                .thenThrow(new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TEMPERATURE_UNIT", "温标单位不合法", "仅支持 CELSIUS"));

        mockMvc.perform(post("/api/v1/products/100/temperature-rules")
                        .with(user(platformPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TEMPERATURE_UNIT"));
    }

    @Test
    @DisplayName("环节温度阈值超过小数位数限制(>2位)拦截，返回 400 INVALID_REQUEST")
    void createRuleStageFractionDigitsExceededReturns400() throws Exception {
        String jsonWithInvalidDigits = """
                {
                  "name": "精度超限规则",
                  "effectiveFrom": "2026-06-01T00:00:00Z",
                  "stages": [
                    {
                      "stageCode": "STORAGE",
                      "lowerLimit": -20.123,
                      "upperLimit": -18.00,
                      "unitCode": "CELSIUS",
                      "allowedDurationSeconds": 0,
                      "sequenceNo": 1
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/products/100/temperature-rules")
                        .with(user(platformPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonWithInvalidDigits))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("环节温度阈值超过整数位数限制(>4位)拦截，返回 400 INVALID_REQUEST")
    void createRuleStageIntegerDigitsExceededReturns400() throws Exception {
        String jsonWithInvalidDigits = """
                {
                  "name": "整数位超限规则",
                  "effectiveFrom": "2026-06-01T00:00:00Z",
                  "stages": [
                    {
                      "stageCode": "STORAGE",
                      "lowerLimit": -12345.00,
                      "upperLimit": -18.00,
                      "unitCode": "CELSIUS",
                      "allowedDurationSeconds": 0,
                      "sequenceNo": 1
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/products/100/temperature-rules")
                        .with(user(platformPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonWithInvalidDigits))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("环节缓冲秒数为负数(<0)拦截，返回 400 INVALID_REQUEST")
    void createRuleStageNegativeAllowedDurationReturns400() throws Exception {
        String jsonWithNegativeDuration = """
                {
                  "name": "负缓冲秒数规则",
                  "effectiveFrom": "2026-06-01T00:00:00Z",
                  "stages": [
                    {
                      "stageCode": "STORAGE",
                      "lowerLimit": -25.00,
                      "upperLimit": -18.00,
                      "unitCode": "CELSIUS",
                      "allowedDurationSeconds": -1,
                      "sequenceNo": 1
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/products/100/temperature-rules")
                        .with(user(platformPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonWithNegativeDuration))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("环节次序号小于1(<1)拦截，返回 400 INVALID_REQUEST")
    void createRuleStageZeroSequenceNoReturns400() throws Exception {
        String jsonWithZeroSeq = """
                {
                  "name": "序号小于1规则",
                  "effectiveFrom": "2026-06-01T00:00:00Z",
                  "stages": [
                    {
                      "stageCode": "STORAGE",
                      "lowerLimit": -25.00,
                      "upperLimit": -18.00,
                      "unitCode": "CELSIUS",
                      "allowedDurationSeconds": 0,
                      "sequenceNo": 0
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/products/100/temperature-rules")
                        .with(user(platformPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonWithZeroSeq))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("API时间契约测试：带+08:00偏移的输入正确解析且响应包含明确UTC偏移(Z)")
    void createRuleWithOffsetTimeConvertsToUtcSemanticsAndOutputsUtcOffset() throws Exception {
        String jsonWithPlus8Offset = """
                {
                  "name": "时区语义验证规则",
                  "effectiveFrom": "2026-06-01T08:00:00+08:00",
                  "effectiveTo": "2026-12-31T20:00:00+08:00",
                  "basisNote": "GB/T 32204-2015",
                  "stages": [
                    {
                      "stageCode": "STORAGE",
                      "lowerLimit": -25.00,
                      "upperLimit": -18.00,
                      "unitCode": "CELSIUS",
                      "allowedDurationSeconds": 600,
                      "sequenceNo": 1
                    }
                  ]
                }
                """;

        OffsetDateTime expectedUtcFrom = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime expectedUtcTo = OffsetDateTime.of(2026, 12, 31, 12, 0, 0, 0, ZoneOffset.UTC);

        TemperatureRuleStageResponse stage = new TemperatureRuleStageResponse(
                1L, 500L, "STORAGE", new BigDecimal("-25.00"), new BigDecimal("-18.00"),
                "CELSIUS", 600, 1, 0L, expectedUtcFrom, 1L, expectedUtcFrom, 1L
        );
        TemperatureRuleResponse resp = new TemperatureRuleResponse(
                500L, 100L, 1, "时区语义验证规则",
                expectedUtcFrom, expectedUtcTo, "DRAFT", "GB/T 32204-2015",
                List.of(stage), 0L, expectedUtcFrom, 1L, expectedUtcFrom, 1L
        );

        when(ruleService.createDraftRule(eq(100L), any(TemperatureRuleCreateRequest.class), any(TraceSecurityPrincipal.class)))
                .thenReturn(resp);

        mockMvc.perform(post("/api/v1/products/100/temperature-rules")
                        .with(user(platformPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonWithPlus8Offset))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(500L))
                // 确保响应的时间字符串具有明确的 UTC 偏移标识（如以 Z 结尾或 +00:00）
                .andExpect(jsonPath("$.data.effectiveFrom").value(matchesPattern("^.*(Z|\\+00:00)$")))
                .andExpect(jsonPath("$.data.effectiveTo").value(matchesPattern("^.*(Z|\\+00:00)$")));

        // 验证 DTO 接收到的 +08:00 时间转换成 Instant 后与预期的 UTC Instant 精确一致
        ArgumentCaptor<TemperatureRuleCreateRequest> captor = ArgumentCaptor.forClass(TemperatureRuleCreateRequest.class);
        verify(ruleService).createDraftRule(eq(100L), captor.capture(), any(TraceSecurityPrincipal.class));
        TemperatureRuleCreateRequest captured = captor.getValue();
        assertThat(captured.effectiveFrom().toInstant()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(captured.effectiveTo().toInstant()).isEqualTo(Instant.parse("2026-12-31T12:00:00Z"));
    }

    @Test
    @DisplayName("规则发布成功返回 200 OK 且状态为 ACTIVE")
    void publishRuleSuccess() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        TemperatureRuleStageResponse s1 = new TemperatureRuleStageResponse(
                10L, 300L, "STORAGE", new BigDecimal("-25.00"), new BigDecimal("-18.00"),
                "CELSIUS", 0, 1, 0L, now, 1L, now, 1L
        );
        TemperatureRuleResponse published = new TemperatureRuleResponse(
                300L, 100L, 1, "冷冻大黄鱼温控基准v1",
                now, null, "ACTIVE", "已正式生效",
                List.of(s1), 0L, now, 1L, now, 1L
        );
        when(ruleService.publishRule(eq(300L), any(TraceSecurityPrincipal.class)))
                .thenReturn(published);

        mockMvc.perform(post("/api/v1/temperature-rules/300/publish")
                        .with(user(platformPrincipal))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(300L))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.isDeleted").doesNotExist());
    }

    @Test
    @DisplayName("停用或草稿状态产品发布规则失败，返回 422 PRODUCT_NOT_ACTIVE")
    void publishRuleInactiveProductReturns422() throws Exception {
        when(ruleService.publishRule(eq(300L), any(TraceSecurityPrincipal.class)))
                .thenThrow(new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "PRODUCT_NOT_ACTIVE", "产品未启用", "产品未处于 ACTIVE 状态"));

        mockMvc.perform(post("/api/v1/temperature-rules/300/publish")
                        .with(user(platformPrincipal))
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_ACTIVE"))
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    @DisplayName("生效时间区间重叠冲突发布失败，返回 422 TEMPERATURE_RULE_EFFECTIVE_CONFLICT")
    void publishRuleOverlappingConflictReturns422() throws Exception {
        when(ruleService.publishRule(eq(300L), any(TraceSecurityPrincipal.class)))
                .thenThrow(new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "TEMPERATURE_RULE_EFFECTIVE_CONFLICT", "生效时间冲突", "区间与已有生效规则重叠"));

        mockMvc.perform(post("/api/v1/temperature-rules/300/publish")
                        .with(user(platformPrincipal))
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TEMPERATURE_RULE_EFFECTIVE_CONFLICT"))
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    @DisplayName("重复发布非 DRAFT 规则失败，返回 409 INVALID_STATE_TRANSITION")
    void publishRuleInvalidStateTransitionReturns409() throws Exception {
        when(ruleService.publishRule(eq(300L), any(TraceSecurityPrincipal.class)))
                .thenThrow(new BusinessException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "状态流转冲突", "只能发布 DRAFT 规则"));

        mockMvc.perform(post("/api/v1/temperature-rules/300/publish")
                        .with(user(platformPrincipal))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"))
                .andExpect(jsonPath("$.status").value(409));
    }
}
