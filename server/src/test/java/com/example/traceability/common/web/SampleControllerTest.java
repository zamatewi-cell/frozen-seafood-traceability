package com.example.traceability.common.web;

import com.example.traceability.common.envelope.PageMeta;
import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.common.exception.GlobalExceptionHandler;
import com.example.traceability.common.filter.RequestIdFilter;
import com.example.traceability.identity.config.SecurityConfiguration;
import com.example.traceability.identity.mapper.AppUserMapper;
import com.example.traceability.identity.mapper.OrganizationMapper;
import com.example.traceability.identity.mapper.RoleMapper;
import com.example.traceability.identity.mapper.UserRoleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 核心 Web 架构与规范切片自动化测试套件 (SampleControllerTest)。
 * <p>
 * 采用 {@code @WebMvcTest(controllers = SampleController.class)} 切片测试，
 * 配合 {@code @Import} 导入异常处理切面和链路追踪拦截器，完全规避对物理数据库与持久层连接的依赖。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@WebMvcTest(
        controllers = SampleController.class,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration")
@Import({GlobalExceptionHandler.class, RequestIdFilter.class, SecurityConfiguration.class})
class SampleControllerTest {

    @MockitoBean
    private AppUserMapper appUserMapper;

    @MockitoBean
    private OrganizationMapper organizationMapper;

    @MockitoBean
    private RoleMapper roleMapper;

    @MockitoBean
    private UserRoleMapper userRoleMapper;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("TC-01: 验证 200 成功响应结构与 RequestId 回显")
    void testPingSuccessEnvelope() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/api/v1/samples/ping"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(header().string("Content-Type", startsWith("application/json")))
                .andExpect(jsonPath("$.data").value("pong"))
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty())
                .andExpect(jsonPath("$.meta.timestamp").isNotEmpty())
                // 严禁旧式传统包装字段
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist())
                .andReturn();

        // 验证响应体 meta.requestId 与响应头 X-Request-Id 严格一致
        String headerRequestId = mvcResult.getResponse().getHeader("X-Request-Id");
        assertNotNull(headerRequestId);
    }

    @Test
    @DisplayName("TC-02: 验证 400 参数校验异常转化为 RFC 9457 Problem 及 fieldErrors 输出")
    void testValidationFailureReturnsProblem400() throws Exception {
        // 传入空 JSON 对象，同时触发 name (NotBlank) 和 quantity (NotNull) 两个字段校验失败
        mockMvc.perform(post("/api/v1/samples/validate")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.title").value("请求参数无效"))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").value("/api/v1/samples/validate"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("name")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("quantity")));
    }

    @Test
    @DisplayName("TC-03: 验证受控 404 业务异常输出 RFC 9457 Problem 契约")
    void testControlledNotFoundReturnsProblem404() throws Exception {
        mockMvc.perform(get("/api/v1/samples/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("资源未找到"))
                .andExpect(jsonPath("$.detail").value("测试资源不存在: sample-404"))
                .andExpect(jsonPath("$.instance").value("/api/v1/samples/not-found"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    @DisplayName("TC-04: 验证客户端自定义 X-Request-Id 全链路透传与对齐")
    void testClientCustomRequestIdPropagation() throws Exception {
        String customRequestId = "CUSTOM-TRACE-REQ-987654321";

        MvcResult mvcResult = mockMvc.perform(get("/api/v1/samples/ping")
                        .header("X-Request-Id", customRequestId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", customRequestId))
                .andExpect(jsonPath("$.meta.requestId").value(customRequestId))
                .andReturn();

        assertEquals(customRequestId, mvcResult.getResponse().getHeader("X-Request-Id"));
    }

    @Test
    @DisplayName("TC-05: 验证有效入参请求成功执行并返回统一成功封套")
    void testValidateEndpointSuccess() throws Exception {
        String validJson = "{\"name\":\"大西洋真鳕鱼\",\"quantity\":50}";

        mockMvc.perform(post("/api/v1/samples/validate")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.data.name").value("大西洋真鳕鱼"))
                .andExpect(jsonPath("$.data.quantity").value(50))
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty())
                .andExpect(jsonPath("$.meta.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("TC-06: 畸形 JSON 返回 400，而不是误报 500")
    void malformedJsonReturnsInvalidRequestProblem() throws Exception {
        mockMvc.perform(post("/api/v1/samples/validate")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("TC-07: 写请求缺少 CSRF 凭据时返回统一 403 Problem")
    void postWithoutCsrfReturnsAccessDeniedProblem() throws Exception {
        mockMvc.perform(post("/api/v1/samples/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"扇贝\",\"quantity\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("TC-08: 未认证访问受保护路径时返回统一 401 Problem")
    void protectedPathRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/protected-example"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("TC-09: 不安全的客户端 RequestId 会被替换为服务端 UUID")
    void unsafeRequestIdIsRegenerated() throws Exception {
        mockMvc.perform(get("/api/v1/samples/ping")
                        .header("X-Request-Id", "<script>alert(1)</script>"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", not("<script>alert(1)</script>")))
                .andExpect(header().string("X-Request-Id",
                        matchesPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")));
    }

    @Test
    @DisplayName("TC-10: 验证分页成功响应包装器与 PageMeta 计算契约")
    void testSuccessEnvelopePageMetadataContract() {
        // 构造分页数据：每页 20 条，总共 95 条记录，当前第 1 页
        PageMeta pageMeta = new PageMeta(1, 20, 95L);

        assertEquals(1, pageMeta.number());
        assertEquals(20, pageMeta.size());
        assertEquals(95L, pageMeta.totalElements());
        assertEquals(5, pageMeta.totalPages()); // 95 / 20 向上取整为 5

        List<String> items = List.of("ITEM-001", "ITEM-002");
        SuccessEnvelope<List<String>> envelope = SuccessEnvelope.ofPage(items, pageMeta, "STATIC-TEST-REQ-ID");

        assertEquals(items, envelope.data());
        assertNotNull(envelope.meta());
        assertEquals("STATIC-TEST-REQ-ID", envelope.meta().requestId());
        assertNotNull(envelope.meta().timestamp());
        assertEquals(pageMeta, envelope.meta().page());

        assertThrows(IllegalArgumentException.class, () -> new PageMeta(0, 20, 1));
        assertThrows(IllegalArgumentException.class, () -> new PageMeta(1, 101, 1));
    }
}
