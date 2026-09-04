package com.example.traceability.common.exception;

import org.springframework.http.HttpStatus;

import java.net.URI;

/**
 * 资源未找到受控业务异常 (HTTP 404)。
 * <p>
 * 当查询的批次、主数据实体、温控规则或指定对象在系统中不存在或已被逻辑删除时抛出。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public class ResourceNotFoundException extends BusinessException {

    public static final String DEFAULT_CODE = "RESOURCE_NOT_FOUND";
    public static final String DEFAULT_TITLE = "资源未找到";
    public static final URI DEFAULT_TYPE = URI.create("https://example.invalid/problems/resource-not-found");

    public ResourceNotFoundException(String detail) {
        super(HttpStatus.NOT_FOUND, DEFAULT_CODE, DEFAULT_TITLE, detail, DEFAULT_TYPE);
    }

    public ResourceNotFoundException(String code, String title, String detail) {
        super(HttpStatus.NOT_FOUND, code, title, detail, DEFAULT_TYPE);
    }
}
