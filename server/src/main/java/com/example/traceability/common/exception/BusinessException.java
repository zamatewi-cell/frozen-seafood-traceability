package com.example.traceability.common.exception;

import org.springframework.http.HttpStatus;

import java.net.URI;

/**
 * 业务通用运行时异常基类 (Base Business Exception)。
 * <p>
 * 所有受控业务异常（如资源未找到、版本并发冲突、物料不平衡等）均应继承此类。
 * 支持指定对应 HTTP 状态码、稳定业务错误码、标准标题、详细信息与类型 URI。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String title;
    private final URI type;

    public BusinessException(HttpStatus status, String code, String title, String detail, URI type) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
        this.type = type;
    }

    public BusinessException(HttpStatus status, String code, String title, String detail) {
        this(status, code, title, detail, URI.create("https://example.invalid/problems/" + code.toLowerCase().replace('_', '-')));
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public URI getType() {
        return type;
    }
}
