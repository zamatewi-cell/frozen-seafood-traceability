package com.example.traceability.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 全局统一异常处理器 (Global Exception Handler)。
 * <p>
 * 拦截系统内抛出的所有受控业务异常与未知系统异常，严格按照 RFC 9457 Problem Details 规范
 * 输出媒体类型为 {@code application/problem+json} 的统一错误结构。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * RFC 9457 规范媒介类型：application/problem+json
     */
    public static final MediaType PROBLEM_MEDIA_TYPE = MediaType.parseMediaType("application/problem+json;charset=UTF-8");

    public static final String MDC_REQUEST_ID_KEY = "requestId";
    public static final String ATTR_REQUEST_ID_KEY = "requestId";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    /**
     * 1. 捕获实体属性参数校验异常 (MethodArgumentNotValidException)。
     * <p>
     * 触发场景：Controller 请求体标记了 @Valid 或 @Validated 但入参不合规。
     * </p>
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Problem> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        List<FieldErrorItem> fieldErrors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.add(new FieldErrorItem(
                    fe.getField(),
                    fe.getCode() != null ? fe.getCode() : "Invalid",
                    fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "参数校验失败"
            ));
        }

        Problem problem = Problem.builder()
                .type(URI.create("https://example.invalid/problems/invalid-request"))
                .title("请求参数无效")
                .status(HttpStatus.BAD_REQUEST.value())
                .code("INVALID_REQUEST")
                .detail("请求体参数校验不通过，存在 " + fieldErrors.size() + " 处字段错误")
                .instance(request.getRequestURI())
                .requestId(resolveRequestId(request))
                .fieldErrors(fieldErrors)
                .build();

        log.warn("参数校验未通过: uri={}, fieldErrorsCount={}", request.getRequestURI(), fieldErrors.size());
        return buildResponseEntity(problem, HttpStatus.BAD_REQUEST);
    }

    /** 捕获方法级单参数校验异常 (HandlerMethodValidationException - Spring 6+/Boot 3+)。 */
    @ExceptionHandler(org.springframework.web.method.annotation.HandlerMethodValidationException.class)
    public ResponseEntity<Problem> handleHandlerMethodValidationException(
            org.springframework.web.method.annotation.HandlerMethodValidationException ex, HttpServletRequest request) {

        List<FieldErrorItem> fieldErrors = new ArrayList<>();
        for (var result : ex.getParameterValidationResults()) {
            String paramName = result.getMethodParameter().getParameterName();
            for (var resolvable : result.getResolvableErrors()) {
                fieldErrors.add(new FieldErrorItem(
                        paramName != null ? paramName : "param",
                        "Invalid",
                        resolvable.getDefaultMessage() != null ? resolvable.getDefaultMessage() : "参数校验失败"
                ));
            }
        }

        Problem problem = Problem.builder()
                .type(URI.create("https://example.invalid/problems/invalid-request"))
                .title("请求参数无效")
                .status(HttpStatus.BAD_REQUEST.value())
                .code("INVALID_REQUEST")
                .detail("请求参数校验不通过，存在 " + fieldErrors.size() + " 处校验错误")
                .instance(request.getRequestURI())
                .requestId(resolveRequestId(request))
                .fieldErrors(fieldErrors)
                .build();

        log.warn("方法级参数校验未通过: uri={}, fieldErrorsCount={}", request.getRequestURI(), fieldErrors.size());
        return buildResponseEntity(problem, HttpStatus.BAD_REQUEST);
    }

    /** 捕获 Bean Validation 约束违规异常 (ConstraintViolationException)。 */
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<Problem> handleConstraintViolationException(
            jakarta.validation.ConstraintViolationException ex, HttpServletRequest request) {

        List<FieldErrorItem> fieldErrors = new ArrayList<>();
        for (var cv : ex.getConstraintViolations()) {
            String field = cv.getPropertyPath() != null ? cv.getPropertyPath().toString() : "param";
            fieldErrors.add(new FieldErrorItem(
                    field,
                    "Invalid",
                    cv.getMessage() != null ? cv.getMessage() : "参数校验失败"
            ));
        }

        Problem problem = Problem.builder()
                .type(URI.create("https://example.invalid/problems/invalid-request"))
                .title("请求参数无效")
                .status(HttpStatus.BAD_REQUEST.value())
                .code("INVALID_REQUEST")
                .detail("请求参数校验不通过，存在 " + fieldErrors.size() + " 处校验错误")
                .instance(request.getRequestURI())
                .requestId(resolveRequestId(request))
                .fieldErrors(fieldErrors)
                .build();

        log.warn("约束违规校验未通过: uri={}, fieldErrorsCount={}", request.getRequestURI(), fieldErrors.size());
        return buildResponseEntity(problem, HttpStatus.BAD_REQUEST);
    }

    /** 捕获空请求体、畸形 JSON 和无法反序列化的字段值。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Problem> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        Problem problem = Problem.builder()
                .type(URI.create("https://example.invalid/problems/invalid-request"))
                .title("请求参数无效")
                .status(HttpStatus.BAD_REQUEST.value())
                .code("INVALID_REQUEST")
                .detail("请求体不是有效的 JSON，或字段类型与接口契约不匹配")
                .instance(request.getRequestURI())
                .requestId(resolveRequestId(request))
                .fieldErrors(List.of())
                .build();

        log.warn("请求体无法读取: uri={}", request.getRequestURI());
        return buildResponseEntity(problem, HttpStatus.BAD_REQUEST);
    }

    /** 捕获缺失的必填查询参数（例如 DELETE 的 expectedVersion）与无法转换类型的路径 / 查询参数。 */
    @ExceptionHandler({
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Problem> handleRequestParameterException(Exception ex, HttpServletRequest request) {
        String detail = ex instanceof org.springframework.web.bind.MissingServletRequestParameterException missing
                ? "缺少必填请求参数: " + missing.getParameterName()
                : "请求参数类型与接口契约不匹配";
        Problem problem = Problem.builder()
                .type(URI.create("https://example.invalid/problems/invalid-request"))
                .title("请求参数无效")
                .status(HttpStatus.BAD_REQUEST.value())
                .code("INVALID_REQUEST")
                .detail(detail)
                .instance(request.getRequestURI())
                .requestId(resolveRequestId(request))
                .fieldErrors(List.of())
                .build();

        log.warn("请求参数无效: uri={}, detail={}", request.getRequestURI(), detail);
        return buildResponseEntity(problem, HttpStatus.BAD_REQUEST);
    }

    /**
     * 2. 捕获受控未找到异常 (ResourceNotFoundException)。
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Problem> handleResourceNotFoundException(
            ResourceNotFoundException ex, HttpServletRequest request) {

        Problem problem = Problem.builder()
                .type(ex.getType())
                .title(ex.getTitle())
                .status(ex.getStatus().value())
                .code(ex.getCode())
                .detail(ex.getMessage())
                .instance(request.getRequestURI())
                .requestId(resolveRequestId(request))
                .fieldErrors(new ArrayList<>())
                .build();

        log.info("受控资源未找到: uri={}, code={}, detail={}", request.getRequestURI(), ex.getCode(), ex.getMessage());
        return buildResponseEntity(problem, ex.getStatus());
    }

    /**
     * 3. 捕获 Spring 静态资源/无匹配路由异常 (NoResourceFoundException)。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Problem> handleNoResourceFoundException(
            NoResourceFoundException ex, HttpServletRequest request) {

        Problem problem = Problem.builder()
                .type(URI.create("https://example.invalid/problems/resource-not-found"))
                .title("资源未找到")
                .status(HttpStatus.NOT_FOUND.value())
                .code("RESOURCE_NOT_FOUND")
                .detail(ex.getMessage())
                .instance(request.getRequestURI())
                .requestId(resolveRequestId(request))
                .fieldErrors(new ArrayList<>())
                .build();

        log.info("请求路径不存在: uri={}", request.getRequestURI());
        return buildResponseEntity(problem, HttpStatus.NOT_FOUND);
    }

    /**
     * 4. 捕获通用受控业务异常 (BusinessException)。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Problem> handleBusinessException(
            BusinessException ex, HttpServletRequest request) {

        Problem problem = Problem.builder()
                .type(ex.getType())
                .title(ex.getTitle())
                .status(ex.getStatus().value())
                .code(ex.getCode())
                .detail(ex.getMessage())
                .instance(request.getRequestURI())
                .requestId(resolveRequestId(request))
                .fieldErrors(new ArrayList<>())
                .build();

        log.warn("业务异常触发: uri={}, code={}, detail={}", request.getRequestURI(), ex.getCode(), ex.getMessage());
        return buildResponseEntity(problem, ex.getStatus());
    }

    /**
     * 5. 兜底捕获系统未预期异常 (Exception)。
     * <p>
     * 严格禁止向客户端输出原始 Java 异常堆栈，输出统一的 INTERNAL_ERROR。
     * </p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Problem> handleUnhandledException(
            Exception ex, HttpServletRequest request) {

        String reqId = resolveRequestId(request);
        log.error("系统发生未处理异常: requestId={}, uri={}", reqId, request.getRequestURI(), ex);

        Problem problem = Problem.builder()
                .type(URI.create("https://example.invalid/problems/internal-error"))
                .title("服务器内部错误")
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .code("INTERNAL_ERROR")
                .detail("服务器处理请求时发生内部错误，请联系系统管理员")
                .instance(request.getRequestURI())
                .requestId(reqId)
                .fieldErrors(new ArrayList<>())
                .build();

        return buildResponseEntity(problem, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * 构造携带 application/problem+json 响应头的 ResponseEntity。
     */
    private ResponseEntity<Problem> buildResponseEntity(Problem problem, HttpStatus status) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CONTENT_TYPE, PROBLEM_MEDIA_TYPE.toString())
                .body(problem);
    }

    /**
     * 解析请求追踪 ID：优先从 MDC 获取，其次从 Request Attribute，再次从 Header，若皆无则自动生成。
     */
    private String resolveRequestId(HttpServletRequest request) {
        String reqId = MDC.get(MDC_REQUEST_ID_KEY);
        if (reqId != null && !reqId.isBlank()) {
            return reqId;
        }
        Object attrId = request.getAttribute(ATTR_REQUEST_ID_KEY);
        if (attrId instanceof String s && !s.isBlank()) {
            return s;
        }
        String headerId = request.getHeader(HEADER_REQUEST_ID);
        if (headerId != null && !headerId.isBlank()) {
            return headerId.trim();
        }
        return UUID.randomUUID().toString();
    }
}
