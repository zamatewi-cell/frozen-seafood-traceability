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
import com.example.traceability.trace.application.TraceEventApplicationService;
import com.example.traceability.trace.dto.CorrectTraceEventRequest;
import com.example.traceability.trace.dto.CreateTraceEventRequest;
import com.example.traceability.trace.dto.TraceEventResponse;
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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TraceEventController.class)
@Import({GlobalExceptionHandler.class, RequestIdFilter.class, SecurityConfiguration.class})
@DisplayName("追溯事件 Web 接口与权限契约测试")
class TraceEventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TraceEventApplicationService traceEventService;

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
    private TraceSecurityPrincipal platformAdmin;

    private static final String VALID_KEY = "1234567890abcdef123456";
    private static final OffsetDateTime OCCURRED_AT = OffsetDateTime.of(2026, 9, 9, 10, 0, 0, 0, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        operatorPrincipal = new TraceSecurityPrincipal(
                101L, "operator1", "企业操作员", "{noop}pwd",
                10L, "ORG_FISHERY_01", "第一远洋捕捞公司", "SOURCE",
                List.of("OPERATOR"), List.of("ORG_ONLY"), true, true
        );

        platformAdmin = new TraceSecurityPrincipal(
                1L, "admin", "平台管理员", "{noop}pwd",
                1L, "ORG_PLATFORM", "溯源管理中心", "PLATFORM",
                List.of("ADMIN"), List.of("PLATFORM"), true, true
        );

        // 活性复核依赖 Mock
        AppUser activeUser = new AppUser();
        activeUser.setId(101L);
        activeUser.setOrgId(10L);
        activeUser.setStatus("ACTIVE");
        activeUser.setIsDeleted(0);
        when(appUserMapper.selectById(101L)).thenReturn(activeUser);

        AppUser adminUser = new AppUser();
        adminUser.setId(1L);
        adminUser.setOrgId(1L);
        adminUser.setStatus("ACTIVE");
        adminUser.setIsDeleted(0);
        when(appUserMapper.selectById(1L)).thenReturn(adminUser);

        Organization activeOrg = new Organization();
        activeOrg.setId(10L);
        activeOrg.setStatus("ACTIVE");
        activeOrg.setIsDeleted(0);
        when(organizationMapper.selectById(10L)).thenReturn(activeOrg);

        Organization platformOrg = new Organization();
        platformOrg.setId(1L);
        platformOrg.setStatus("ACTIVE");
        platformOrg.setIsDeleted(0);
        when(organizationMapper.selectById(1L)).thenReturn(platformOrg);

        Role operatorRole = new Role();
        operatorRole.setRoleCode("OPERATOR");
        operatorRole.setScopeType("ORG_ONLY");
        operatorRole.setStatus("ACTIVE");
        operatorRole.setIsDeleted(0);
        when(roleMapper.findActiveRolesByUserId(101L)).thenReturn(List.of(operatorRole));

        Role adminRole = new Role();
        adminRole.setRoleCode("ADMIN");
        adminRole.setScopeType("PLATFORM");
        adminRole.setStatus("ACTIVE");
        adminRole.setIsDeleted(0);
        when(roleMapper.findActiveRolesByUserId(1L)).thenReturn(List.of(adminRole));
    }

    @Test
    @DisplayName("匿名用户访问追溯事件列表被拦截 (401 AUTH_REQUIRED)")
    void listEvents_Anonymous_Returns401() throws Exception {
        mockMvc.perform(get("/api/v1/batches/1000/events"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    @DisplayName("写操作未携带 CSRF 凭据被拦截 (403 ACCESS_DENIED)")
    void createEvent_WithoutCsrf_Returns403() throws Exception {
        CreateTraceEventRequest req = new CreateTraceEventRequest(
                "FREEZE", OCCURRED_AT, null, "MANUAL", "加工切片", null
        );

        mockMvc.perform(post("/api/v1/batches/1000/events")
                        .with(user(operatorPrincipal))
                        .header("Idempotency-Key", VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("企业操作员创建普通事件成功 (201 Created，白名单响应投影与时间格式)")
    void createEvent_Success() throws Exception {
        CreateTraceEventRequest req = new CreateTraceEventRequest(
                "FREEZE", OCCURRED_AT, null, "MANUAL", "车间速冻", Map.of("temperature", -35.0)
        );

        TraceEventResponse mockResponse = new TraceEventResponse(
                501L, 1000L, 10L, null, "FREEZE",
                OCCURRED_AT, OCCURRED_AT, 101L, "MANUAL", "SUBMITTED",
                "车间速冻", Map.of("temperature", -35.0), null, null
        );

        when(traceEventService.createEvent(eq(1000L), any(), eq(VALID_KEY), any()))
                .thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/batches/1000/events")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(501L))
                .andExpect(jsonPath("$.data.batchId").value(1000L))
                .andExpect(jsonPath("$.data.orgId").value(10L))
                .andExpect(jsonPath("$.data.eventType").value("FREEZE"))
                .andExpect(jsonPath("$.data.occurredAt").value("2026-09-09T10:00:00.000Z"))
                .andExpect(jsonPath("$.data.recordedAt").value("2026-09-09T10:00:00.000Z"))
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.summary").value("车间速冻"))
                .andExpect(jsonPath("$.data.siteId").doesNotExist())
                .andExpect(jsonPath("$.data.correctsEventId").doesNotExist())
                .andExpect(jsonPath("$.data.correctionReason").doesNotExist())
                .andExpect(jsonPath("$.data.isDeleted").doesNotExist())
                .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist());
    }

    @Test
    @DisplayName("平台管理员尝试写入追溯事件被拦截 (403 ACCESS_DENIED)")
    void createEvent_PlatformAdmin_Returns403() throws Exception {
        CreateTraceEventRequest req = new CreateTraceEventRequest(
                "FREEZE", OCCURRED_AT, null, "MANUAL", "车间速冻", null
        );

        when(traceEventService.createEvent(eq(1000L), any(), eq(VALID_KEY), any()))
                .thenThrow(new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "权限不足", "仅企业操作员允许写入追溯事件"));

        mockMvc.perform(post("/api/v1/batches/1000/events")
                        .with(user(platformAdmin))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("平台角色尝试更正追溯事件被拦截 (403 ACCESS_DENIED)")
    void correctEvent_PlatformAdmin_Returns403() throws Exception {
        CorrectTraceEventRequest req = new CorrectTraceEventRequest(
                "FREEZE", OCCURRED_AT, null, "MANUAL", "修正摘要", null, "原因说明"
        );

        when(traceEventService.correctEvent(eq(1000L), eq(501L), any(), eq(VALID_KEY), any()))
                .thenThrow(new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "权限不足", "仅企业操作员允许写入追溯事件"));

        mockMvc.perform(post("/api/v1/batches/1000/events/501/corrections")
                        .with(user(platformAdmin))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("企业操作员更正事件成功 (201 Created，包含 correctsEventId 与 correctionReason)")
    void correctEvent_Success() throws Exception {
        CorrectTraceEventRequest req = new CorrectTraceEventRequest(
                "FREEZE", OCCURRED_AT, null, "MANUAL", "修正后的加工摘要", null, "补充说明温度"
        );

        TraceEventResponse mockResponse = new TraceEventResponse(
                502L, 1000L, 10L, null, "FREEZE",
                OCCURRED_AT, OCCURRED_AT, 101L, "MANUAL", "SUBMITTED",
                "修正后的加工摘要", null, 501L, "补充说明温度"
        );

        when(traceEventService.correctEvent(eq(1000L), eq(501L), any(), eq(VALID_KEY), any()))
                .thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/batches/1000/events/501/corrections")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(502L))
                .andExpect(jsonPath("$.data.correctsEventId").value(501L))
                .andExpect(jsonPath("$.data.correctionReason").value("补充说明温度"))
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.siteId").doesNotExist())
                .andExpect(jsonPath("$.data.detailsJson").doesNotExist());
    }

    @Test
    @DisplayName("更正请求缺少更正原因 correctionReason 校验失败 (400 INVALID_REQUEST)")
    void correctEvent_MissingReason_Returns400() throws Exception {
        CorrectTraceEventRequest req = new CorrectTraceEventRequest(
                "FREEZE", OCCURRED_AT, null, "MANUAL", "修正后的加工摘要", null, ""
        );

        mockMvc.perform(post("/api/v1/batches/1000/events/501/corrections")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("查询批次追溯事件列表成功 (200 OK)")
    void listEvents_Success() throws Exception {
        TraceEventResponse event1 = new TraceEventResponse(
                1L, 1000L, 10L, null, "SOURCE",
                OCCURRED_AT, OCCURRED_AT, 101L, "MANUAL", "CORRECTED",
                "旧事件", null, null, null
        );
        TraceEventResponse event2 = new TraceEventResponse(
                2L, 1000L, 10L, null, "SOURCE",
                OCCURRED_AT, OCCURRED_AT, 101L, "MANUAL", "SUBMITTED",
                "更正后新事件", null, 1L, "修正产地"
        );

        when(traceEventService.listEvents(eq(1000L), any()))
                .thenReturn(List.of(event1, event2));

        mockMvc.perform(get("/api/v1/batches/1000/events")
                        .with(user(operatorPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].id").value(1L))
                .andExpect(jsonPath("$.data[0].status").value("CORRECTED"))
                .andExpect(jsonPath("$.data[1].id").value(2L))
                .andExpect(jsonPath("$.data[1].status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data[1].correctsEventId").value(1L));
    }

    @Test
    @DisplayName("跨组织查询批次事件被拒绝 (403 ORG_SCOPE_DENIED)")
    void listEvents_CrossOrg_Returns403() throws Exception {
        when(traceEventService.listEvents(eq(2000L), any()))
                .thenThrow(new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权", "无权访问其他组织的批次追溯事件"));

        mockMvc.perform(get("/api/v1/batches/2000/events")
                        .with(user(operatorPrincipal)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_SCOPE_DENIED"));
    }

    @Test
    @DisplayName("批次不存在返回 404 RESOURCE_NOT_FOUND")
    void listEvents_BatchNotFound_Returns404() throws Exception {
        when(traceEventService.listEvents(eq(9999L), any()))
                .thenThrow(new ResourceNotFoundException("未找到 ID 为 9999 的批次"));

        mockMvc.perform(get("/api/v1/batches/9999/events")
                        .with(user(operatorPrincipal)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("DRAFT 批次创建追溯事件被拒绝 (422 BATCH_FLOW_BLOCKED)")
    void createEvent_DraftBatch_Returns422() throws Exception {
        CreateTraceEventRequest req = new CreateTraceEventRequest(
                "SOURCE", OCCURRED_AT, null, "MANUAL", "初次捕捞", null
        );

        when(traceEventService.createEvent(eq(1000L), any(), eq(VALID_KEY), any()))
                .thenThrow(new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "BATCH_FLOW_BLOCKED", "批次状态不可流转", "DRAFT 批次禁止创建追溯事件"));

        mockMvc.perform(post("/api/v1/batches/1000/events")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_FLOW_BLOCKED"));
    }

    @Test
    @DisplayName("幂等冲突返回 409 IDEMPOTENCY_CONFLICT")
    void createEvent_IdempotencyConflict_Returns409() throws Exception {
        CreateTraceEventRequest req = new CreateTraceEventRequest(
                "SOURCE", OCCURRED_AT, null, "MANUAL", "不同载荷", null
        );

        when(traceEventService.createEvent(eq(1000L), any(), eq(VALID_KEY), any()))
                .thenThrow(new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "幂等提交冲突", "请求载荷不一致"));

        mockMvc.perform(post("/api/v1/batches/1000/events")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    @DisplayName("更正已更正事件返回 409 EVENT_ALREADY_CORRECTED")
    void correctEvent_AlreadyCorrected_Returns409() throws Exception {
        CorrectTraceEventRequest req = new CorrectTraceEventRequest(
                "FREEZE", OCCURRED_AT, null, "MANUAL", "再次更正", null, "原因"
        );

        when(traceEventService.correctEvent(eq(1000L), eq(500L), any(), eq(VALID_KEY), any()))
                .thenThrow(new BusinessException(HttpStatus.CONFLICT, "EVENT_ALREADY_CORRECTED", "事件已被更正", "禁止分叉更正"));

        mockMvc.perform(post("/api/v1/batches/1000/events/500/corrections")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_ALREADY_CORRECTED"));
    }
}
