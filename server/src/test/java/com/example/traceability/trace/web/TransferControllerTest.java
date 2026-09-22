package com.example.traceability.trace.web;

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
import com.example.traceability.trace.application.TransferApplicationService;
import com.example.traceability.trace.domain.TransferStatus;
import com.example.traceability.trace.dto.TransferAcceptRequest;
import com.example.traceability.trace.dto.TransferCreateRequest;
import com.example.traceability.trace.dto.TransferPatchRequest;
import com.example.traceability.trace.dto.TransferRejectRequest;
import com.example.traceability.trace.dto.TransferResponse;
import com.example.traceability.trace.dto.TransferSubmitRequest;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransferController.class)
@Import({GlobalExceptionHandler.class, RequestIdFilter.class, SecurityConfiguration.class})
@DisplayName("企业间整批交接 Web 接口与权限契约测试")
class TransferControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TransferApplicationService transferService;

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

    @MockitoBean
    private com.example.traceability.trace.mapper.TransferMapper transferMapper;

    @MockitoBean
    private com.example.traceability.trace.mapper.TransferIdempotencyMapper transferIdempotencyMapper;

    @MockitoBean
    private com.example.traceability.audit.mapper.AuditLogMapper auditLogMapper;

    private TraceSecurityPrincipal senderOperator;
    private TraceSecurityPrincipal receiverOperator;

    @BeforeEach
    void setUp() {
        senderOperator = new TraceSecurityPrincipal(
                101L, "sender_op", "发送操作员", "hash",
                10L, "ORG-SENDER", "发送企业", "PROCESSOR",
                List.of("OPERATOR"), List.of("OWN_ORG"), true, true
        );

        receiverOperator = new TraceSecurityPrincipal(
                201L, "receiver_op", "接收操作员", "hash",
                20L, "ORG-RECEIVER", "接收企业", "WAREHOUSE",
                List.of("OPERATOR"), List.of("OWN_ORG"), true, true
        );

        // Mock 安全活性校验
        AppUser senderUser = new AppUser();
        senderUser.setId(101L);
        senderUser.setOrgId(10L);
        senderUser.setStatus("ACTIVE");
        senderUser.setIsDeleted(0);
        when(appUserMapper.selectById(101L)).thenReturn(senderUser);

        Organization senderOrg = new Organization();
        senderOrg.setId(10L);
        senderOrg.setStatus("ACTIVE");
        senderOrg.setIsDeleted(0);
        when(organizationMapper.selectById(10L)).thenReturn(senderOrg);

        Role opRole = new Role();
        opRole.setId(1L);
        opRole.setRoleCode("OPERATOR");
        opRole.setScopeType("OWN_ORG");
        opRole.setStatus("ACTIVE");
        opRole.setIsDeleted(0);
        when(roleMapper.findActiveRolesByUserId(101L)).thenReturn(List.of(opRole));

        AppUser receiverUser = new AppUser();
        receiverUser.setId(201L);
        receiverUser.setOrgId(20L);
        receiverUser.setStatus("ACTIVE");
        receiverUser.setIsDeleted(0);
        when(appUserMapper.selectById(201L)).thenReturn(receiverUser);

        Organization receiverOrg = new Organization();
        receiverOrg.setId(20L);
        receiverOrg.setStatus("ACTIVE");
        receiverOrg.setIsDeleted(0);
        when(organizationMapper.selectById(20L)).thenReturn(receiverOrg);
        when(roleMapper.findActiveRolesByUserId(201L)).thenReturn(List.of(opRole));
    }

    @Test
    @DisplayName("未登录访问受保护端点应返回 401 Unauthorized")
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/transfers"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("缺少 CSRF Token 访问写操作端点应返回 403 Forbidden")
    void missingCsrf_returns403() throws Exception {
        TransferCreateRequest req = new TransferCreateRequest(1001L, 20L);
        mockMvc.perform(post("/api/v1/transfers")
                        .with(user(senderOperator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("创建交接草稿成功返回 201 Created 与白名单契约")
    void createDraft_success() throws Exception {
        TransferCreateRequest req = new TransferCreateRequest(1001L, 20L);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        TransferResponse expected = new TransferResponse(
                5001L, "TRF-20260914-001", 1001L, 10L, 20L,
                new BigDecimal("500.000"), "kg", TransferStatus.DRAFT,
                null, null, null, null, null, null,
                null, null, null, 0L, now, now
        );

        when(transferService.createDraft(any(TransferCreateRequest.class), eq("idem-create-12345678"), any()))
                .thenReturn(expected);

        mockMvc.perform(post("/api/v1/transfers")
                        .with(user(senderOperator))
                        .with(csrf())
                        .header("Idempotency-Key", "idem-create-12345678")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.data.id").value(5001))
                .andExpect(jsonPath("$.data.transferNo").value("TRF-20260914-001"))
                .andExpect(jsonPath("$.data.batchId").value(1001))
                .andExpect(jsonPath("$.data.senderOrgId").value(10))
                .andExpect(jsonPath("$.data.receiverOrgId").value(20))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.isDeleted").doesNotExist())
                .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist());
    }

    @Test
    @DisplayName("分页查询交接列表返回 200 OK 与分页元数据")
    void listTransfers_success() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        TransferResponse item = new TransferResponse(
                5001L, "TRF-20260914-001", 1001L, 10L, 20L,
                new BigDecimal("500.000"), "kg", TransferStatus.PENDING,
                now, now, 101L, null, null, null,
                null, null, null, 1L, now, now
        );

        when(transferService.listTransfers(eq("SENT"), eq("PENDING"), eq(1L), eq(20), any()))
                .thenReturn(List.of(item));
        when(transferService.countTransfers(eq("SENT"), eq("PENDING"), any()))
                .thenReturn(1L);

        mockMvc.perform(get("/api/v1/transfers")
                        .with(user(senderOperator))
                        .param("direction", "SENT")
                        .param("status", "PENDING")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(5001))
                .andExpect(jsonPath("$.meta.page.number").value(1))
                .andExpect(jsonPath("$.meta.page.size").value(20))
                .andExpect(jsonPath("$.meta.page.totalElements").value(1));
    }

    @Test
    @DisplayName("删除草稿缺少必填查询参数 expectedVersion 时返回 400 INVALID_REQUEST 而不是 500")
    void deleteDraft_missingExpectedVersion_returnsBadRequest() throws Exception {
        mockMvc.perform(delete("/api/v1/transfers/5001")
                        .with(user(senderOperator))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("列表查询将大小写无关的合法筛选值规范化后传给 SQL 层")
    void listTransfers_normalizesSupportedFilters() throws Exception {
        when(transferService.listTransfers(eq("SENT"), eq("PENDING"), eq(1L), eq(20), any()))
                .thenReturn(List.of());
        when(transferService.countTransfers(eq("SENT"), eq("PENDING"), any()))
                .thenReturn(0L);

        mockMvc.perform(get("/api/v1/transfers")
                        .with(user(senderOperator))
                        .param("direction", " sent ")
                        .param("status", " pending "))
                .andExpect(status().isOk());

        verify(transferService).listTransfers(eq("SENT"), eq("PENDING"), eq(1L), eq(20), any());
        verify(transferService).countTransfers(eq("SENT"), eq("PENDING"), any());
    }

    @Test
    @DisplayName("查询交接详情成功返回 200 OK")
    void getTransferDetail_success() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        TransferResponse item = new TransferResponse(
                5001L, "TRF-20260914-001", 1001L, 10L, 20L,
                new BigDecimal("500.000"), "kg", TransferStatus.DRAFT,
                null, null, null, null, null, null,
                null, null, null, 0L, now, now
        );

        when(transferService.getTransferDetail(eq(5001L), any())).thenReturn(item);

        mockMvc.perform(get("/api/v1/transfers/5001")
                        .with(user(senderOperator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(5001))
                .andExpect(jsonPath("$.data.transferNo").value("TRF-20260914-001"));
    }

    @Test
    @DisplayName("修改交接草稿返回 200 OK")
    void patchDraft_success() throws Exception {
        TransferPatchRequest req = new TransferPatchRequest(25L, 0L);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        TransferResponse item = new TransferResponse(
                5001L, "TRF-20260914-001", 1001L, 10L, 25L,
                new BigDecimal("500.000"), "kg", TransferStatus.DRAFT,
                null, null, null, null, null, null,
                null, null, null, 1L, now, now
        );

        when(transferService.patchDraft(eq(5001L), any(), any())).thenReturn(item);

        mockMvc.perform(patch("/api/v1/transfers/5001")
                        .with(user(senderOperator))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receiverOrgId").value(25));
    }

    @Test
    @DisplayName("逻辑删除交接草稿返回 204 No Content")
    void deleteDraft_success() throws Exception {
        doNothing().when(transferService).deleteDraft(eq(5001L), eq(0L), any());

        mockMvc.perform(delete("/api/v1/transfers/5001")
                        .with(user(senderOperator))
                        .with(csrf())
                        .param("expectedVersion", "0"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("提交交接返回 200 OK 并流转为 PENDING")
    void submitTransfer_success() throws Exception {
        OffsetDateTime shippedAt = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        TransferSubmitRequest req = new TransferSubmitRequest(shippedAt, 0L);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        TransferResponse item = new TransferResponse(
                5001L, "TRF-20260914-001", 1001L, 10L, 20L,
                new BigDecimal("500.000"), "kg", TransferStatus.PENDING,
                shippedAt, now, 101L, null, null, null,
                null, null, null, 1L, now, now
        );

        when(transferService.submitTransfer(eq(5001L), any(), eq("idem-submit-12345678"), any())).thenReturn(item);

        mockMvc.perform(post("/api/v1/transfers/5001/submit")
                        .with(user(senderOperator))
                        .with(csrf())
                        .header("Idempotency-Key", "idem-submit-12345678")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.submittedBy").value(101));
    }

    @Test
    @DisplayName("接收方确认接受返回 200 OK 并流转为 ACCEPTED")
    void acceptTransfer_success() throws Exception {
        OffsetDateTime receivedAt = OffsetDateTime.now(ZoneOffset.UTC);
        TransferAcceptRequest req = new TransferAcceptRequest(
                new BigDecimal("495.000"), "kg", receivedAt, "冷链干耗5kg", 1L
        );
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        TransferResponse item = new TransferResponse(
                5001L, "TRF-20260914-001", 1001L, 10L, 20L,
                new BigDecimal("500.000"), "kg", TransferStatus.ACCEPTED,
                receivedAt.minusHours(2), now.minusHours(2), 101L, receivedAt, now, 201L,
                new BigDecimal("495.000"), "冷链干耗5kg", null, 2L, now, now
        );

        when(transferService.acceptTransfer(eq(5001L), any(), eq("idem-accept-12345678"), any())).thenReturn(item);

        mockMvc.perform(post("/api/v1/transfers/5001/accept")
                        .with(user(receiverOperator))
                        .with(csrf())
                        .header("Idempotency-Key", "idem-accept-12345678")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.receivedQuantity").value(495.0))
                .andExpect(jsonPath("$.data.differenceReason").value("冷链干耗5kg"))
                .andExpect(jsonPath("$.data.decidedBy").value(201));
    }

    @Test
    @DisplayName("接收方拒收返回 200 OK 并流转为 REJECTED")
    void rejectTransfer_success() throws Exception {
        OffsetDateTime rejectedAt = OffsetDateTime.now(ZoneOffset.UTC);
        TransferRejectRequest req = new TransferRejectRequest("货物温度偏高解冻", rejectedAt, 1L);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        TransferResponse item = new TransferResponse(
                5001L, "TRF-20260914-001", 1001L, 10L, 20L,
                new BigDecimal("500.000"), "kg", TransferStatus.REJECTED,
                rejectedAt.minusHours(2), now.minusHours(2), 101L, rejectedAt, now, 201L,
                null, null, "货物温度偏高解冻", 2L, now, now
        );

        when(transferService.rejectTransfer(eq(5001L), any(), eq("idem-reject-12345678"), any())).thenReturn(item);

        mockMvc.perform(post("/api/v1/transfers/5001/reject")
                        .with(user(receiverOperator))
                        .with(csrf())
                        .header("Idempotency-Key", "idem-reject-12345678")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectionReason").value("货物温度偏高解冻"))
                .andExpect(jsonPath("$.data.decidedBy").value(201));
    }

    @Test
    @DisplayName("业务异常被全局处理器捕获并转化为 RFC 9457 Problem Details")
    void businessException_mappedToProblemDetails() throws Exception {
        when(transferService.createDraft(any(), any(), any()))
                .thenThrow(new BusinessException(
                        HttpStatus.CONFLICT,
                        "BATCH_NOT_ACTIVE",
                        "批次状态不可交接",
                        "批次状态非 ACTIVE"
                ));

        TransferCreateRequest req = new TransferCreateRequest(1001L, 20L);

        mockMvc.perform(post("/api/v1/transfers")
                        .with(user(senderOperator))
                        .with(csrf())
                        .header("Idempotency-Key", "idem-conflict-123456")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_ACTIVE"))
                .andExpect(jsonPath("$.title").value("批次状态不可交接"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("列表查询：拒绝 OpenAPI 未声明的历史 direction 别名")
    void listTransfers_withInvalidDirection_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/transfers")
                        .with(user(senderOperator))
                        .param("direction", "OUTGOING"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("列表查询：传非法 status 参数时直接拒绝并返回 400 Bad Request")
    void listTransfers_withInvalidStatus_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/transfers")
                        .with(user(senderOperator))
                        .param("status", "UNKNOWN_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("列表查询：page 小于 1 时按 OpenAPI 契约返回 400")
    void listTransfers_withInvalidPage_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/transfers")
                        .with(user(senderOperator))
                        .param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("列表查询：size 超过 100 时按 OpenAPI 契约返回 400")
    void listTransfers_withInvalidSize_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/transfers")
                        .with(user(senderOperator))
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("接受交接：实收数量超过三位小数时拒绝进入应用服务")
    void acceptTransfer_withExcessiveQuantityScale_returnsBadRequest() throws Exception {
        TransferAcceptRequest req = new TransferAcceptRequest(
                new BigDecimal("1.0001"), "kg", OffsetDateTime.now(ZoneOffset.UTC), null, 1L
        );

        mockMvc.perform(post("/api/v1/transfers/5001/accept")
                        .with(user(receiverOperator))
                        .with(csrf())
                        .header("Idempotency-Key", "idem-accept-scale-1234")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("提交交接：expectedVersion 为负数时拒绝进入应用服务")
    void submitTransfer_withNegativeExpectedVersion_returnsBadRequest() throws Exception {
        TransferSubmitRequest req = new TransferSubmitRequest(OffsetDateTime.now(ZoneOffset.UTC), -1L);

        mockMvc.perform(post("/api/v1/transfers/5001/submit")
                        .with(user(senderOperator))
                        .with(csrf())
                        .header("Idempotency-Key", "idem-submit-version-12")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
