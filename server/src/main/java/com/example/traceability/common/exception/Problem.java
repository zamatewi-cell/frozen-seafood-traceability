package com.example.traceability.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * RFC 9457 统一错误响应模型 (Application Problem Details)。
 * <p>
 * 严格遵循 IETF RFC 9457 与 OpenAPI 3.1 规范，响应头媒介类型锁定为
 * {@code application/problem+json; charset=utf-8}。
 * 包含 type, title, status, code, detail, instance, requestId 与 fieldErrors 等字段。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Problem {

    /**
     * 错误类别标识 URI，指向标准问题类型文档。
     */
    private URI type;

    /**
     * 该错误类型的标准人类可读摘要。
     */
    private String title;

    /**
     * HTTP 状态码（必须与实际 HTTP 响应码保持一致）。
     */
    private int status;

    /**
     * 系统内稳定、全局唯一的大写蛇形业务错误码（供客户端条件分支做精确判定）。
     */
    private String code;

    /**
     * 针对本次具体发生的详细上下文说明。
     */
    private String detail;

    /**
     * 触发此错误的当前请求 URI。
     */
    private String instance;

    /**
     * 当前请求链路追踪 ID（与响应头 X-Request-Id 一致）。
     */
    private String requestId;

    /**
     * 参数校验失败时的字段级错误列表；若非校验错误则输出空列表 []。
     */
    private List<FieldErrorItem> fieldErrors = new ArrayList<>();

    public Problem() {
    }

    public Problem(URI type, String title, int status, String code, String detail, String instance, String requestId, List<FieldErrorItem> fieldErrors) {
        this.type = type;
        this.title = title;
        this.status = status;
        this.code = code;
        this.detail = detail;
        this.instance = instance;
        this.requestId = requestId;
        this.fieldErrors = (fieldErrors != null) ? new ArrayList<>(fieldErrors) : new ArrayList<>();
    }

    public static Builder builder() {
        return new Builder();
    }

    public URI getType() {
        return type;
    }

    public void setType(URI type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getInstance() {
        return instance;
    }

    public void setInstance(String instance) {
        this.instance = instance;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public List<FieldErrorItem> getFieldErrors() {
        return fieldErrors;
    }

    public void setFieldErrors(List<FieldErrorItem> fieldErrors) {
        this.fieldErrors = (fieldErrors != null) ? new ArrayList<>(fieldErrors) : new ArrayList<>();
    }

    /**
     * 流式构建器。
     */
    public static final class Builder {
        private URI type;
        private String title;
        private int status;
        private String code;
        private String detail;
        private String instance;
        private String requestId;
        private List<FieldErrorItem> fieldErrors = new ArrayList<>();

        private Builder() {
        }

        public Builder type(URI type) {
            this.type = type;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder status(int status) {
            this.status = status;
            return this;
        }

        public Builder code(String code) {
            this.code = code;
            return this;
        }

        public Builder detail(String detail) {
            this.detail = detail;
            return this;
        }

        public Builder instance(String instance) {
            this.instance = instance;
            return this;
        }

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder fieldErrors(List<FieldErrorItem> fieldErrors) {
            if (fieldErrors != null) {
                this.fieldErrors = new ArrayList<>(fieldErrors);
            }
            return this;
        }

        public Builder addFieldError(FieldErrorItem errorItem) {
            if (errorItem != null) {
                this.fieldErrors.add(errorItem);
            }
            return this;
        }

        public Problem build() {
            return new Problem(type, title, status, code, detail, instance, requestId, fieldErrors);
        }
    }
}
