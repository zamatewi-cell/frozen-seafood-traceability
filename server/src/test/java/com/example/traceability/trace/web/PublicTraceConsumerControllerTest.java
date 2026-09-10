package com.example.traceability.trace.web;

import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.GlobalExceptionHandler;
import com.example.traceability.common.filter.RequestIdFilter;
import com.example.traceability.identity.config.SecurityConfiguration;
import com.example.traceability.identity.mapper.AppUserMapper;
import com.example.traceability.identity.mapper.OrganizationMapper;
import com.example.traceability.identity.mapper.RoleMapper;
import com.example.traceability.identity.mapper.UserRoleMapper;
import com.example.traceability.trace.application.PublicTraceApplicationService;
import com.example.traceability.trace.dto.PublicTraceProjectionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 消费者匿名公开追溯查询控制器 Web 切片与白名单字典测试。
 * <p>
 * 严格验证免认证、免 CSRF 放行，并执行严格的“禁止字段字典 (forbidden-field dictionary)”校验。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@WebMvcTest(PublicTraceConsumerController.class)
@Import({GlobalExceptionHandler.class, RequestIdFilter.class, SecurityConfiguration.class})
@DisplayName("消费者匿名公开追溯查询 Web 切片与白名单测试")
class PublicTraceConsumerControllerTest {

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

    private static final String SAMPLE_PUBLIC_ID = "ABCDEF234567ABCDEF234567AB";
    private static final String RAW_BATCH_NO = "SECRET-INTERNAL-BATCH-999";
    private static final String RAW_ORIGIN = "SECRET-PORT-BERTH-42-AREA";

    @Test
    @DisplayName("匿名免认证免 CSRF 正常查询公开有效码返回 200 与白名单投影")
    void testAnonymousQuerySuccess() throws Exception {
        PublicTraceProjectionResponse resp = new PublicTraceProjectionResponse(
                SAMPLE_PUBLIC_ID,
                new PublicTraceProjectionResponse.ProductProjection("舟山大黄鱼", "FISH", "500g-600g/条"),
                new PublicTraceProjectionResponse.BatchProjection("SEC****999", "DOMESTIC_CAPTURE", "SE****EA", "2026-09-01"),
                List.of(
                        new PublicTraceProjectionResponse.TimelineItem("捕捞采收", "2026-09-01T08:00:00Z", "企业人工填报"),
                        new PublicTraceProjectionResponse.TimelineItem("速冻冷冻", "2026-09-01T12:00:00Z", "教学演练与仿真模拟数据（SIMULATED）")
                ),
                new PublicTraceProjectionResponse.TemperatureSummaryProjection("INSUFFICIENT_DATA", "当前切片尚未接入冷链实时温控采集流"),
                "ACTIVE",
                null,
                "2026-09-10T10:00:00Z",
                "本溯源信息仅反映供应链各节点企业申报登记的电子履历，不作为货物物理真实性或防伪验证凭证；系统相关模拟标识仅用于教学实训推演。"
        );

        when(publicTraceService.getPublicTrace(eq(SAMPLE_PUBLIC_ID))).thenReturn(resp);

        // 匿名无 Cookie、无 Authorization、无 CSRF Header 发起 GET 请求
        MvcResult result = mockMvc.perform(get("/api/public/v1/public/traces/" + SAMPLE_PUBLIC_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicTraceId").value(SAMPLE_PUBLIC_ID))
                .andExpect(jsonPath("$.data.product.name").value("舟山大黄鱼"))
                .andExpect(jsonPath("$.data.batch.publicBatchNo").value("SEC****999"))
                .andExpect(jsonPath("$.data.batchStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.temperatureSummary.result").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.data.timeline[1].dataSourceLabel").value(org.hamcrest.Matchers.containsString("SIMULATED")))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();

        // 严格禁止字段字典校验 (Forbidden-field dictionary checks)
        assertThat(responseBody).doesNotContain(RAW_BATCH_NO);
        assertThat(responseBody).doesNotContain(RAW_ORIGIN);
        assertThat(responseBody).doesNotContain("token_hash");
        assertThat(responseBody).doesNotContain("tokenHash");
        assertThat(responseBody).doesNotContain("detailsJson");
        assertThat(responseBody).doesNotContain("is_deleted");
        assertThat(responseBody).doesNotContain("isDeleted");
        assertThat(responseBody).doesNotContain("idempotencyKey");
        assertThat(responseBody).doesNotContain("activationIdempotencyKey");
        assertThat(responseBody).doesNotContain("disableIdempotencyKey");
        assertThat(responseBody).doesNotContain("orgId");
        assertThat(responseBody).doesNotContain("userId");
        assertThat(responseBody).doesNotContain("operatorId");
        assertThat(responseBody).doesNotContain("siteId");
        assertThat(responseBody).doesNotContain("createdBy");
        assertThat(responseBody).doesNotContain("updatedBy");
    }

    @Test
    @DisplayName("未知码或停用码统一返回 404 PUBLIC_TRACE_NOT_FOUND (且无敏感泄露)")
    void testUnknownOrDisabledNotFound() throws Exception {
        when(publicTraceService.getPublicTrace(eq("UNKNOWN1234567890ABCDEF23")))
                .thenThrow(new BusinessException(HttpStatus.NOT_FOUND, "PUBLIC_TRACE_NOT_FOUND", "公共追溯码未找到", "未找到对应的公开追溯信息或追溯码已失效"));

        mockMvc.perform(get("/api/public/v1/public/traces/UNKNOWN1234567890ABCDEF23"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PUBLIC_TRACE_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("未找到对应的公开追溯信息或追溯码已失效"));
    }

    @Test
    @DisplayName("匿名发起 POST /api/public/v1/public/traces/* 被安全拦截 (401/403)，防止非 GET 请求绕过 CSRF")
    void testAnonymousPostToPublicTraceEndpointForbidden() throws Exception {
        // 由于安全配置严格仅放行 GET /api/public/v1/public/traces/*，非 GET 请求需走认证与 CSRF
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/public/v1/public/traces/" + SAMPLE_PUBLIC_ID))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertThat(status).isIn(401, 403);
                });
    }

    @Test
    @DisplayName("非法格式 publicTraceId 统一返回 404 且结构一致")
    void testInvalidFormatPublicTraceIdNotFound() throws Exception {
        String invalidId = "INVALID_ID_SHORT";
        when(publicTraceService.getPublicTrace(eq(invalidId)))
                .thenThrow(new BusinessException(HttpStatus.NOT_FOUND, "PUBLIC_TRACE_NOT_FOUND", "公共追溯码未找到", "未找到对应的公开追溯信息或追溯码已失效"));

        mockMvc.perform(get("/api/public/v1/public/traces/" + invalidId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PUBLIC_TRACE_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("未找到对应的公开追溯信息或追溯码已失效"));
    }
}
