package com.example.traceability.trace.web;

import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.GlobalExceptionHandler;
import com.example.traceability.common.filter.RequestIdFilter;
import com.example.traceability.identity.config.SecurityConfiguration;
import com.example.traceability.identity.domain.AppUser;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Role;
import com.example.traceability.identity.dto.SiteSummaryResponse;
import com.example.traceability.identity.mapper.AppUserMapper;
import com.example.traceability.identity.mapper.OrganizationMapper;
import com.example.traceability.identity.mapper.RoleMapper;
import com.example.traceability.identity.mapper.UserRoleMapper;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.trace.application.ShipmentApplicationService;
import com.example.traceability.trace.domain.ShipmentStatus;
import com.example.traceability.trace.domain.TransferStatus;
import com.example.traceability.trace.dto.ShipmentArriveRequest;
import com.example.traceability.trace.dto.ShipmentBindTransferRequest;
import com.example.traceability.trace.dto.ShipmentCreateRequest;
import com.example.traceability.trace.dto.ShipmentDispatchRequest;
import com.example.traceability.trace.dto.ShipmentResponse;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ShipmentController.class)
@Import({GlobalExceptionHandler.class, RequestIdFilter.class, SecurityConfiguration.class})
@DisplayName("冷链运输任务 Web 接口与权限契约测试")
class ShipmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ShipmentApplicationService shipmentService;

    @MockitoBean
    private AppUserMapper appUserMapper;

    @MockitoBean
    private OrganizationMapper organizationMapper;

    @MockitoBean
    private RoleMapper roleMapper;

    @MockitoBean
    private UserRoleMapper userRoleMapper;

    private TraceSecurityPrincipal senderOperator;

    @BeforeEach
    void setUp() {
        senderOperator = new TraceSecurityPrincipal(
                101L, "sender_op", "发货操作员", "hash",
                10L, "ORG-SENDER", "来源企业", "SOURCE",
                List.of("OPERATOR"), List.of("OWN_ORG"), true, true
        );
        AppUser u = new AppUser();
        u.setId(101L);
        u.setOrgId(10L);
        u.setStatus("ACTIVE");
        u.setIsDeleted(0);
        when(appUserMapper.selectById(101L)).thenReturn(u);
        Organization org = new Organization();
        org.setId(10L);
        org.setStatus("ACTIVE");
        org.setIsDeleted(0);
        when(organizationMapper.selectById(10L)).thenReturn(org);
        Role role = new Role();
        role.setId(1L);
        role.setRoleCode("OPERATOR");
        role.setScopeType("OWN_ORG");
        role.setStatus("ACTIVE");
        role.setIsDeleted(0);
        when(roleMapper.findActiveRolesByUserId(101L)).thenReturn(List.of(role));
    }

    private ShipmentResponse sample(ShipmentStatus status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new ShipmentResponse(
                9001L, "SHP-20260922-0001", status,
                new ShipmentResponse.PartyRef(10L, "ORG-SENDER", "来源企业", "SOURCE"),
                new ShipmentResponse.PartyRef(20L, "ORG-PRC", "加工企业", "PROCESSOR"),
                new ShipmentResponse.PartyRef(30L, "ORG-CAR", "承运企业", "CARRIER"),
                "浙A-12345",
                new SiteSummaryResponse(1L, 10L, "SRC-PORT", "来源码头", "PORT", "ACTIVE"),
                new SiteSummaryResponse(2L, 20L, "PRC-FAC", "加工厂", "FACTORY", "ACTIVE"),
                status == ShipmentStatus.PLANNED ? null : now, null, null, null, null, null, null, null, null,
                List.of(new ShipmentResponse.TransferItem(5001L, "TRF-1", 1001L, "TB-1001", new BigDecimal("1000.000"), "kg", TransferStatus.DRAFT, 1L)),
                1L, now, now
        );
    }

    @Test
    @DisplayName("未登录访问返回 401")
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/shipments")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("缺少 CSRF 的写操作返回 403 且不进入应用服务")
    void missingCsrf_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/shipments/9001/dispatch")
                        .with(user(senderOperator))
                        .header("Idempotency-Key", "idem-dispatch-000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ShipmentDispatchRequest(OffsetDateTime.now(ZoneOffset.UTC), 1L))))
                .andExpect(status().isForbidden());
        verify(shipmentService, never()).dispatchShipment(any(), any(), any(), any());
    }

    @Test
    @DisplayName("创建运输任务返回 201 与白名单契约（三方组织、起止场所、装载清单）")
    void createShipment_success() throws Exception {
        when(shipmentService.createShipment(any(ShipmentCreateRequest.class), eq("idem-shp-create-0001"), any()))
                .thenReturn(sample(ShipmentStatus.PLANNED));

        mockMvc.perform(post("/api/v1/shipments")
                        .with(user(senderOperator)).with(csrf())
                        .header("Idempotency-Key", "idem-shp-create-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ShipmentCreateRequest(30L, 1L, 2L, "浙A-12345"))))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.data.status").value("PLANNED"))
                .andExpect(jsonPath("$.data.carrierOrg.orgType").value("CARRIER"))
                .andExpect(jsonPath("$.data.destinationSite.name").value("加工厂"))
                .andExpect(jsonPath("$.data.destinationSite.addressText").doesNotExist())
                .andExpect(jsonPath("$.data.transfers[0].traceBatchNo").value("TB-1001"))
                .andExpect(jsonPath("$.data.loadedAt").doesNotExist())
                .andExpect(jsonPath("$.data.isDeleted").doesNotExist());
    }

    @Test
    @DisplayName("创建运输任务缺少必填字段返回 400 INVALID_REQUEST")
    void createShipment_validation() throws Exception {
        mockMvc.perform(post("/api/v1/shipments")
                        .with(user(senderOperator)).with(csrf())
                        .header("Idempotency-Key", "idem-shp-create-0002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("carrierOrgId", 30, "originSiteId", 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verify(shipmentService, never()).createShipment(any(), any(), any());
    }

    @Test
    @DisplayName("列表查询规范化 role / status 并返回分页元数据")
    void listShipments_normalizesFilters() throws Exception {
        when(shipmentService.listShipments(eq("CARRIER"), eq("IN_TRANSIT"), eq(1L), eq(20), any())).thenReturn(List.of(sample(ShipmentStatus.IN_TRANSIT)));
        when(shipmentService.countShipments(eq("CARRIER"), eq("IN_TRANSIT"), any())).thenReturn(1L);

        mockMvc.perform(get("/api/v1/shipments").with(user(senderOperator)).param("role", " carrier ").param("status", "in_transit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.meta.page.totalElements").value(1));
    }

    @Test
    @DisplayName("列表查询非法 role 或 status 返回 400")
    void listShipments_invalidFilters() throws Exception {
        mockMvc.perform(get("/api/v1/shipments").with(user(senderOperator)).param("role", "OWNER"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/shipments").with(user(senderOperator)).param("status", "QUARANTINED"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("绑定交接：透传 Idempotency-Key 并返回运输任务")
    void bindTransfer_success() throws Exception {
        when(shipmentService.bindTransfer(eq(9001L), any(ShipmentBindTransferRequest.class), eq("idem-shp-bind-000001"), any()))
                .thenReturn(sample(ShipmentStatus.PLANNED));

        mockMvc.perform(post("/api/v1/shipments/9001/transfers")
                        .with(user(senderOperator)).with(csrf())
                        .header("Idempotency-Key", "idem-shp-bind-000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ShipmentBindTransferRequest(5001L, 0L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transfers[0].transferId").value(5001));
    }

    @Test
    @DisplayName("解绑交接：expectedTransferVersion 必填")
    void unbindTransfer_requiresVersion() throws Exception {
        mockMvc.perform(delete("/api/v1/shipments/9001/transfers/5001").with(user(senderOperator)).with(csrf()))
                .andExpect(status().isBadRequest());
        when(shipmentService.unbindTransfer(eq(9001L), eq(5001L), eq(1L), any())).thenReturn(sample(ShipmentStatus.PLANNED));
        mockMvc.perform(delete("/api/v1/shipments/9001/transfers/5001").param("expectedTransferVersion", "1")
                        .with(user(senderOperator)).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("发运：loadedAt 缺失返回 400；业务异常映射为 Problem Details")
    void dispatch_validationAndProblemMapping() throws Exception {
        mockMvc.perform(post("/api/v1/shipments/9001/dispatch")
                        .with(user(senderOperator)).with(csrf())
                        .header("Idempotency-Key", "idem-shp-dispatch-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest());

        when(shipmentService.dispatchShipment(eq(9001L), any(ShipmentDispatchRequest.class), eq("idem-shp-dispatch-02"), any()))
                .thenThrow(new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权", "仅运输任务指定的承运组织有权确认装载发运与到达"));
        mockMvc.perform(post("/api/v1/shipments/9001/dispatch")
                        .with(user(senderOperator)).with(csrf())
                        .header("Idempotency-Key", "idem-shp-dispatch-02")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ShipmentDispatchRequest(OffsetDateTime.now(ZoneOffset.UTC), 1L))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_SCOPE_DENIED"));
    }

    @Test
    @DisplayName("到达：返回 DELIVERED 运输任务")
    void arrive_success() throws Exception {
        when(shipmentService.arriveShipment(eq(9001L), any(ShipmentArriveRequest.class), eq("idem-shp-arrive-001"), any()))
                .thenReturn(sample(ShipmentStatus.DELIVERED));
        mockMvc.perform(post("/api/v1/shipments/9001/arrive")
                        .with(user(senderOperator)).with(csrf())
                        .header("Idempotency-Key", "idem-shp-arrive-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ShipmentArriveRequest(OffsetDateTime.now(ZoneOffset.UTC), 2L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"));
    }

    @Test
    @DisplayName("取消：原因必填")
    void cancel_requiresReason() throws Exception {
        mockMvc.perform(post("/api/v1/shipments/9001/cancel")
                        .with(user(senderOperator)).with(csrf())
                        .header("Idempotency-Key", "idem-shp-cancel-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  \",\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest());
    }
}
