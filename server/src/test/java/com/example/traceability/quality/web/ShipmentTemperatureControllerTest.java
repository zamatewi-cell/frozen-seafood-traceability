package com.example.traceability.quality.web;

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
import com.example.traceability.quality.application.ShipmentTemperatureService;
import com.example.traceability.quality.dto.ShipmentTemperatureRecordRequest;
import com.example.traceability.quality.dto.TemperatureRecordResponse;
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

import java.math.BigDecimal;
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

@WebMvcTest(ShipmentTemperatureController.class)
@Import({GlobalExceptionHandler.class, RequestIdFilter.class, SecurityConfiguration.class})
@DisplayName("Shipment 在途温度记录 Web 接口与安全契约测试（PB2）")
class ShipmentTemperatureControllerTest {

    private static final String URL = "/api/v1/shipments/9001/temperature-records";
    private static final String KEY = "temp-ctl-key-000000000001";
    private static final String BODY = "{\"measuredAt\":\"2026-09-24T02:00:00.123456+08:00\",\"temperature\":-18.5,\"dataSource\":\"MANUAL\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShipmentTemperatureService temperatureService;

    @MockitoBean
    private AppUserMapper appUserMapper;

    @MockitoBean
    private OrganizationMapper organizationMapper;

    @MockitoBean
    private RoleMapper roleMapper;

    @MockitoBean
    private UserRoleMapper userRoleMapper;

    private TraceSecurityPrincipal carrier;

    @BeforeEach
    void setUp() {
        carrier = new TraceSecurityPrincipal(901L, "car_op", "承运操作员", "{noop}pwd",
                30L, "ORG_CAR", "承运企业", "CARRIER", List.of("OPERATOR"), List.of("ORG_ONLY"), true, true);
        AppUser u = new AppUser();
        u.setId(901L);
        u.setOrgId(30L);
        u.setStatus("ACTIVE");
        u.setIsDeleted(0);
        when(appUserMapper.selectById(901L)).thenReturn(u);
        Organization org = new Organization();
        org.setId(30L);
        org.setStatus("ACTIVE");
        org.setIsDeleted(0);
        when(organizationMapper.selectById(30L)).thenReturn(org);
        Role role = new Role();
        role.setRoleCode("OPERATOR");
        role.setScopeType("ORG_ONLY");
        role.setStatus("ACTIVE");
        role.setIsDeleted(0);
        when(roleMapper.findActiveRolesByUserId(901L)).thenReturn(List.of(role));
    }

    private static TemperatureRecordResponse response(long id, String evaluation, boolean withRule) {
        TemperatureRecordResponse.RuleBasis rule = withRule
                ? new TemperatureRecordResponse.RuleBasis(51L, "冷冻大黄鱼运输规则", 2, 61L,
                new BigDecimal("-25.00"), new BigDecimal("-15.00"), 1800)
                : null;
        return new TemperatureRecordResponse(id, 9001L, "TRANSPORT",
                OffsetDateTime.of(2026, 9, 23, 18, 0, 0, 123456000, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 9, 23, 18, 1, 2, 654321000, ZoneOffset.UTC),
                new BigDecimal("-18.50"), "CELSIUS", "MANUAL", "PROBE-01", evaluation, rule, 30L, 901L);
    }

    @Test
    @DisplayName("匿名登记 / 查询 401 AUTH_REQUIRED，不调用服务（温度记录从不匿名可见）")
    void anonymous_unauthorized() throws Exception {
        mockMvc.perform(post(URL).with(csrf()).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        verify(temperatureService, never()).record(any(), any(), any(), any());
        verify(temperatureService, never()).listRecords(any(), any());
    }

    @Test
    @DisplayName("缺失 CSRF 403，不调用服务")
    void missingCsrf_forbidden() throws Exception {
        mockMvc.perform(post(URL).with(user(carrier)).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verify(temperatureService, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("登记 201：白名单字段、微秒 UTC 时间、单点判定与规则依据快照；不暴露幂等键与请求哈希")
    void record_created() throws Exception {
        when(temperatureService.record(eq(9001L), any(ShipmentTemperatureRecordRequest.class), eq(KEY), any()))
                .thenReturn(response(7101L, "NORMAL", true));
        mockMvc.perform(post(URL).with(user(carrier)).with(csrf()).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(7101))
                .andExpect(jsonPath("$.data.shipmentId").value(9001))
                .andExpect(jsonPath("$.data.stageCode").value("TRANSPORT"))
                .andExpect(jsonPath("$.data.measuredAt").value("2026-09-23T18:00:00.123456Z"))
                .andExpect(jsonPath("$.data.recordedAt").value("2026-09-23T18:01:02.654321Z"))
                .andExpect(jsonPath("$.data.temperature").value(-18.5))
                .andExpect(jsonPath("$.data.unitCode").value("CELSIUS"))
                .andExpect(jsonPath("$.data.dataSource").value("MANUAL"))
                .andExpect(jsonPath("$.data.deviceNo").value("PROBE-01"))
                .andExpect(jsonPath("$.data.evaluation").value("NORMAL"))
                .andExpect(jsonPath("$.data.rule.ruleId").value(51))
                .andExpect(jsonPath("$.data.rule.versionNo").value(2))
                .andExpect(jsonPath("$.data.rule.ruleStageId").value(61))
                .andExpect(jsonPath("$.data.rule.lowerLimit").value(-25.0))
                .andExpect(jsonPath("$.data.rule.upperLimit").value(-15.0))
                .andExpect(jsonPath("$.data.rule.allowedDurationSeconds").value(1800))
                .andExpect(jsonPath("$.data.orgId").value(30))
                .andExpect(jsonPath("$.data.actorUserId").value(901))
                .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.data.requestHash").doesNotExist());
    }

    @Test
    @DisplayName("MISSING_CONTEXT：没有规则依据（rule 为空，按全局约定不输出空字段）")
    void record_missingContext_ruleNull() throws Exception {
        when(temperatureService.record(eq(9001L), any(ShipmentTemperatureRecordRequest.class), eq(KEY), any()))
                .thenReturn(response(7102L, "MISSING_CONTEXT", false));
        mockMvc.perform(post(URL).with(user(carrier)).with(csrf()).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.evaluation").value("MISSING_CONTEXT"))
                .andExpect(jsonPath("$.data.rule").doesNotExist());
    }

    @Test
    @DisplayName("缺少 measuredAt / temperature / dataSource 400 INVALID_REQUEST，不调用服务")
    void validation_badRequest() throws Exception {
        for (String body : List.of(
                "{\"temperature\":-18.5,\"dataSource\":\"MANUAL\"}",
                "{\"measuredAt\":\"2026-09-24T02:00:00Z\",\"dataSource\":\"MANUAL\"}",
                "{\"measuredAt\":\"2026-09-24T02:00:00Z\",\"temperature\":-18.5,\"dataSource\":\"  \"}")) {
            mockMvc.perform(post(URL).with(user(carrier)).with(csrf()).header("Idempotency-Key", KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        verify(temperatureService, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("未声明字段（例如 evaluation）原样收集并交由服务拒绝（不被静默忽略）")
    void unknownFields_forwarded() throws Exception {
        when(temperatureService.record(eq(9001L), any(ShipmentTemperatureRecordRequest.class), eq(KEY), any()))
                .thenAnswer(inv -> {
                    ShipmentTemperatureRecordRequest r = inv.getArgument(1);
                    if (!r.unknownFields().isEmpty()) {
                        throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", String.join(",", r.unknownFields().keySet()));
                    }
                    return response(7103L, "NORMAL", true);
                });
        mockMvc.perform(post(URL).with(user(carrier)).with(csrf()).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"measuredAt\":\"2026-09-24T02:00:00Z\",\"temperature\":-18.5,\"dataSource\":\"MANUAL\",\"evaluation\":\"NORMAL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("evaluation"));
    }

    @Test
    @DisplayName("业务异常原样映射为 Problem（409 SHIPMENT_NOT_IN_TRANSIT / 403 ORG_SCOPE_DENIED）")
    void businessError_problem() throws Exception {
        when(temperatureService.record(eq(9001L), any(ShipmentTemperatureRecordRequest.class), eq(KEY), any()))
                .thenThrow(new BusinessException(HttpStatus.CONFLICT, "SHIPMENT_NOT_IN_TRANSIT", "运输任务不在途", "已到达"));
        mockMvc.perform(post(URL).with(user(carrier)).with(csrf()).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIPMENT_NOT_IN_TRANSIT"));
        when(temperatureService.listRecords(eq(9001L), any()))
                .thenThrow(new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权", "无权查看"));
        mockMvc.perform(get(URL).with(user(carrier)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_SCOPE_DENIED"));
    }

    @Test
    @DisplayName("查询 200：数组按服务返回顺序（测量时间、主键），不含幂等键与请求哈希")
    void list_ok() throws Exception {
        when(temperatureService.listRecords(eq(9001L), any())).thenReturn(List.of(response(7101L, "NORMAL", true), response(7102L, "HIGH", true)));
        mockMvc.perform(get(URL).with(user(carrier)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].id").value(7101))
                .andExpect(jsonPath("$.data[1].evaluation").value("HIGH"))
                .andExpect(jsonPath("$.data[0].idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.data[0].requestHash").doesNotExist());
    }
}
