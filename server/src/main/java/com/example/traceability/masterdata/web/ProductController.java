package com.example.traceability.masterdata.web;

import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.masterdata.application.ProductApplicationService;
import com.example.traceability.masterdata.dto.ProductCreateRequest;
import com.example.traceability.masterdata.dto.ProductPatchRequest;
import com.example.traceability.masterdata.dto.ProductQueryCriteria;
import com.example.traceability.masterdata.dto.ProductResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 产品主数据控制器。
 * <p>
 * 提供海鲜水产基础信息的查询、详情、新增与基于乐观锁的版本更新能力。
 * 读操作需已认证用户，写操作必须具有 PLATFORM 权限作用域并校验 CSRF。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Validated
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductApplicationService productService;

    public ProductController(ProductApplicationService productService) {
        this.productService = productService;
    }

    /**
     * 分页查询产品主数据列表。
     *
     * @param keyword    编码或名称关键词（可选）
     * @param category   水产大类（可选）
     * @param sourceType 来源类型（可选）
     * @param status     产品状态（可选）
     * @param page       页码（从 1 开始，默认 1）
     * @param size       每页条数（默认 20，范围 1~100）
     * @return 分页产品列表
     */
    @GetMapping
    public SuccessEnvelope<List<ProductResponse>> listProducts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码 page 最小值为 1") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "分页大小 size 最小值为 1") @Max(value = 100, message = "分页大小 size 最大值为 100") int size
    ) {
        ProductQueryCriteria criteria = new ProductQueryCriteria(keyword, category, sourceType, status, page, size);
        return productService.listProducts(criteria);
    }

    /**
     * 查询指定 ID 的产品详情。
     *
     * @param productId 产品ID
     * @return 产品详情封套
     */
    @GetMapping("/{productId}")
    public SuccessEnvelope<ProductResponse> getProduct(@PathVariable Long productId) {
        return SuccessEnvelope.of(productService.getProductById(productId));
    }

    /**
     * 创建海产品主数据（仅限全平台管理角色）。
     *
     * @param request   创建参数
     * @param principal 当前认证主体
     * @return 创建后的产品详情
     */
    @PostMapping
    public ResponseEntity<SuccessEnvelope<ProductResponse>> createProduct(
            @Valid @RequestBody ProductCreateRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        ProductResponse response = productService.createProduct(request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(SuccessEnvelope.of(response));
    }

    /**
     * 乐观锁更新海产品主数据（仅限全平台管理角色）。
     *
     * @param productId 产品ID
     * @param request   更新参数（必须携带 version）
     * @param principal 当前认证主体
     * @return 更新后的产品详情
     */
    @PatchMapping("/{productId}")
    public SuccessEnvelope<ProductResponse> patchProduct(
            @PathVariable Long productId,
            @Valid @RequestBody ProductPatchRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(productService.patchProduct(productId, request, principal));
    }
}
