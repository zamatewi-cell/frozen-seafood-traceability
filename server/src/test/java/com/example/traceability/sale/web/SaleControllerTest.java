package com.example.traceability.sale.web;

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
import com.example.traceability.sale.application.SaleApplicationService;
import com.example.traceability.sale.dto.SaleCreateRequest;
import com.example.traceability.sale.dto.SaleResponse;
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

@WebMvcTest(SaleController.class)
@Import({GlobalExceptionHandler.class, RequestIdFilter.class, SecurityConfiguration.class})
@DisplayName("终端销售 Web 接口与安全契约测试")
class SaleControllerTest {

    private static final String URL = "/api/v1/batches/3000/sales";
    private static final String KEY = "sale-ctl-key-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SaleApplicationService saleService;

    @MockitoBean
    private AppUserMapper appUserMapper;

    @MockitoBean
    private OrganizationMapper organizationMapper;

    @MockitoBean
    private RoleMapper roleMapper;

    @MockitoBean
    private UserRoleMapper userRoleMapper;

    private TraceSecurityPrincipal retailer;

    @BeforeEach
    void setUp() {
        retailer = new TraceSecurityPrincipal(401L, "ret_op", "零售操作员", "{noop}pwd",
                40L, "ORG_RET", "零售企业", "RETAILER", List.of("OPERATOR"), List.of("ORG_ONLY"), true, true);
        AppUser u = new AppUser();
        u.setId(401L);
        u.setOrgId(40L);
        u.setStatus("ACTIVE");
        u.setIsDeleted(0);
        when(appUserMapper.selectById(401L)).thenReturn(u);
        Organization org = new Organization();
        org.setId(40L);
        org.setStatus("ACTIVE");
        org.setIsDeleted(0);
        when(organizationMapper.selectById(40L)).thenReturn(org);
        Role role = new Role();
        role.setRoleCode("OPERATOR");
        role.setScopeType("ORG_ONLY");
        role.setStatus("ACTIVE");
        role.setIsDeleted(0);
        when(roleMapper.findActiveRolesByUserId(401L)).thenReturn(List.of(role));
    }

    private static String body(String qty) {
        return "{\"siteId\":800,\"quantity\":" + qty + ",\"occurredAt\":\"2026-09-23T09:30:15.123456+08:00\"}";
    }

    private static SaleResponse response() {
        OffsetDateTime t = OffsetDateTime.of(2026, 9, 23, 1, 30, 15, 123456000, ZoneOffset.UTC);
        return new SaleResponse(9001L, 3000L, 40L, 800L, "零售门店A", new BigDecimal("200.000"), "kg", t, "SUBMITTED", 401L, t);
    }

    @Test
    @DisplayName("匿名提交 401 AUTH_REQUIRED")
    void anonymous_unauthorized() throws Exception {
        mockMvc.perform(post(URL).with(csrf()).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body("200")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        verify(saleService, never()).createSale(any(), any(), any(), any());
    }

    @Test
    @DisplayName("缺失 CSRF 403")
    void missingCsrf_forbidden() throws Exception {
        mockMvc.perform(post(URL).with(user(retailer)).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body("200")))
                .andExpect(status().isForbidden());
        verify(saleService, never()).createSale(any(), any(), any(), any());
    }

    @Test
    @DisplayName("提交成功 201，微秒精度业务时间原样返回，不暴露幂等键与请求哈希")
    void create_created() throws Exception {
        when(saleService.createSale(eq(3000L), any(SaleCreateRequest.class), eq(KEY), any())).thenReturn(response());
        mockMvc.perform(post(URL).with(user(retailer)).with(csrf()).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body("200")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(9001))
                .andExpect(jsonPath("$.data.batchId").value(3000))
                .andExpect(jsonPath("$.data.siteName").value("零售门店A"))
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.occurredAt").value("2026-09-23T01:30:15.123456Z"))
                .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.data.requestHash").doesNotExist());
    }

    @Test
    @DisplayName("缺少必填字段 / 数量小数超过 3 位 400 INVALID_REQUEST，不调用服务")
    void validation_badRequest() throws Exception {
        mockMvc.perform(post(URL).with(user(retailer)).with(csrf()).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1,\"occurredAt\":\"2026-09-23T09:30:15+08:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(post(URL).with(user(retailer)).with(csrf()).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body("1.0001")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verify(saleService, never()).createSale(any(), any(), any(), any());
    }

    @Test
    @DisplayName("业务异常原样映射为 Problem（422 SALE_QUANTITY_EXCEEDS_REMAINING）")
    void businessError_problem() throws Exception {
        when(saleService.createSale(eq(3000L), any(SaleCreateRequest.class), eq(KEY), any()))
                .thenThrow(new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "SALE_QUANTITY_EXCEEDS_REMAINING", "销售数量超过剩余量", "超卖"));
        mockMvc.perform(post(URL).with(user(retailer)).with(csrf()).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body("700")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SALE_QUANTITY_EXCEEDS_REMAINING"));
    }

    @Test
    @DisplayName("查询销售记录 200")
    void list_ok() throws Exception {
        when(saleService.listSales(eq(3000L), any())).thenReturn(List.of(response()));
        mockMvc.perform(get(URL).with(user(retailer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].quantity").value(200.000));
    }
}
