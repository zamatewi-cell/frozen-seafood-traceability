package com.example.traceability.masterdata.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.traceability.common.envelope.PageMeta;
import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.masterdata.domain.Product;
import com.example.traceability.masterdata.domain.ProductCategory;
import com.example.traceability.masterdata.domain.ProductSourceType;
import com.example.traceability.masterdata.domain.ProductStatus;
import com.example.traceability.masterdata.dto.ProductCreateRequest;
import com.example.traceability.masterdata.dto.ProductPatchRequest;
import com.example.traceability.masterdata.dto.ProductQueryCriteria;
import com.example.traceability.masterdata.dto.ProductResponse;
import com.example.traceability.masterdata.mapper.ProductMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * 产品主数据应用服务。
 * <p>
 * 编排产品主数据分页查询、详情查看、新增以及基于乐观锁的增量更新。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Service
public class ProductApplicationService {

    private final ProductMapper productMapper;

    public ProductApplicationService(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

    /**
     * 分页多条件查询产品主数据列表。
     *
     * @param criteria 查询过滤条件
     * @return 分页成功封套
     */
    public SuccessEnvelope<List<ProductResponse>> listProducts(ProductQueryCriteria criteria) {
        LambdaQueryWrapper<Product> countWrapper = buildQueryWrapper(criteria);
        Long total = productMapper.selectCount(countWrapper);
        long totalCount = total != null ? total : 0L;

        LambdaQueryWrapper<Product> listWrapper = buildQueryWrapper(criteria);
        listWrapper.orderByDesc(Product::getId);
        long offset = (long) (criteria.page() - 1) * criteria.size();
        listWrapper.last("LIMIT " + offset + ", " + criteria.size());

        List<Product> records = productMapper.selectList(listWrapper);
        List<ProductResponse> dtos = records.stream()
                .map(ProductResponse::fromEntity)
                .toList();

        PageMeta pageMeta = new PageMeta(criteria.page(), criteria.size(), totalCount);
        return SuccessEnvelope.ofPage(dtos, pageMeta);
    }

    private LambdaQueryWrapper<Product> buildQueryWrapper(ProductQueryCriteria criteria) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        if (criteria.keyword() != null && !criteria.keyword().isBlank()) {
            String kw = criteria.keyword().trim();
            wrapper.and(w -> w.like(Product::getProductCode, kw).or().like(Product::getPublicName, kw));
        }
        if (criteria.category() != null && !criteria.category().isBlank()) {
            String cat = criteria.category().trim();
            if (ProductCategory.isValid(cat)) {
                wrapper.eq(Product::getCategory, ProductCategory.fromCode(cat).name());
            } else {
                wrapper.eq(Product::getCategory, cat.toUpperCase());
            }
        }
        if (criteria.sourceType() != null && !criteria.sourceType().isBlank()) {
            String st = criteria.sourceType().trim();
            if (ProductSourceType.isValid(st)) {
                wrapper.eq(Product::getSourceType, ProductSourceType.fromCode(st).name());
            } else {
                wrapper.eq(Product::getSourceType, st.toUpperCase());
            }
        }
        if (criteria.status() != null && !criteria.status().isBlank()) {
            String s = criteria.status().trim();
            if (ProductStatus.isValid(s)) {
                wrapper.eq(Product::getStatus, ProductStatus.fromCode(s).name());
            } else {
                wrapper.eq(Product::getStatus, s.toUpperCase());
            }
        }
        return wrapper;
    }

    /**
     * 根据产品内部 ID 查询产品详情。
     *
     * @param productId 产品ID
     * @return 产品详情响应 DTO
     */
    public ProductResponse getProductById(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + productId + " 的海产品主数据");
        }
        return ProductResponse.fromEntity(product);
    }

    /**
     * 创建海产品主数据。
     * <p>
     * 必须具有 PLATFORM 权限范围，严格校验产品编码全局唯一性，规范化枚举。
     * </p>
     *
     * @param req       创建请求参数
     * @param principal 当前认证主体
     * @return 创建成功的产品详情
     */
    @Transactional
    public ProductResponse createProduct(ProductCreateRequest req, TraceSecurityPrincipal principal) {
        checkPlatformScope(principal);

        // 1. 枚举字段业务合法性与规范化校验
        if (!ProductCategory.isValid(req.category())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "不支持的水产大类: " + req.category());
        }
        if (!ProductSourceType.isValid(req.sourceType())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "不支持的来源类型: " + req.sourceType());
        }
        if (!ProductStatus.isValid(req.status())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "不支持的产品状态: " + req.status());
        }

        // 2. baseUnitCode 校验：Phase 1 MVP 仅允许 kg
        String baseUnit = req.baseUnitCode() != null && !req.baseUnitCode().isBlank() ? req.baseUnitCode().trim().toLowerCase() : "kg";
        if (!"kg".equals(baseUnit)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "产品基准计量单位仅允许 kg");
        }

        // 3. 校验 productCode 全局唯一性
        Long count = productMapper.selectCount(
                new LambdaQueryWrapper<Product>().eq(Product::getProductCode, req.productCode().trim())
        );
        if (count != null && count > 0) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "PRODUCT_CODE_CONFLICT",
                    "产品编码冲突",
                    "产品编码已存在: " + req.productCode()
            );
        }

        // 4. 构建并插入实体（统一规范化为定义的大写 canonical code）
        Product product = new Product();
        product.setProductCode(req.productCode().trim());
        product.setPublicName(req.publicName().trim());
        product.setScientificName(req.scientificName() != null && !req.scientificName().isBlank() ? req.scientificName().trim() : null);
        product.setCategory(ProductCategory.fromCode(req.category()).name());
        product.setSpecification(req.specification().trim());
        product.setSourceType(ProductSourceType.fromCode(req.sourceType()).name());
        product.setBaseUnitCode(baseUnit);
        product.setStatus(ProductStatus.fromCode(req.status()).name());
        product.setVersion(0L);
        product.setIsDeleted(0);
        product.setCreatedBy(principal.getUserId());
        product.setUpdatedBy(principal.getUserId());
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        product.setCreatedAt(nowUtc);
        product.setUpdatedAt(nowUtc);

        try {
            productMapper.insert(product);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "PRODUCT_CODE_CONFLICT",
                    "产品编码冲突",
                    "产品编码已存在: " + req.productCode()
            );
        }

        return ProductResponse.fromEntity(product);
    }

    /**
     * 基于 MyBatis-Plus 乐观锁更新海产品主数据。
     *
     * @param productId 产品ID
     * @param req       更新请求参数
     * @param principal 当前认证主体
     * @return 更新后的产品详情
     */
    @Transactional
    public ProductResponse patchProduct(Long productId, ProductPatchRequest req, TraceSecurityPrincipal principal) {
        checkPlatformScope(principal);

        Product existing = productMapper.selectById(productId);
        if (existing == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + productId + " 的海产品主数据");
        }

        // 1. 显式乐观锁检查：比较客户端携带版本与当前库内版本
        if (!Objects.equals(existing.getVersion(), req.version())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "VERSION_CONFLICT",
                    "资源版本冲突",
                    "当前产品版本号为 " + existing.getVersion() + "，请求提交的版本号为 " + req.version()
            );
        }

        // 2. 检查 productCode 变更时的唯一性
        if (req.productCode() != null && !req.productCode().isBlank()) {
            String newCode = req.productCode().trim();
            if (!newCode.equals(existing.getProductCode())) {
                Long count = productMapper.selectCount(
                        new LambdaQueryWrapper<Product>()
                                .eq(Product::getProductCode, newCode)
                                .ne(Product::getId, productId)
                );
                if (count != null && count > 0) {
                    throw new BusinessException(
                    HttpStatus.CONFLICT,
                            "PRODUCT_CODE_CONFLICT",
                            "产品编码冲突",
                            "产品编码已存在: " + newCode
                    );
                }
                existing.setProductCode(newCode);
            }
        }

        // 3. 校验并规范化更新其它字段
        if (req.publicName() != null && !req.publicName().isBlank()) {
            existing.setPublicName(req.publicName().trim());
        }
        if (req.scientificName() != null) {
            existing.setScientificName(req.scientificName().isBlank() ? null : req.scientificName().trim());
        }
        if (req.category() != null && !req.category().isBlank()) {
            if (!ProductCategory.isValid(req.category().trim())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "不支持的水产大类: " + req.category());
            }
            existing.setCategory(ProductCategory.fromCode(req.category().trim()).name());
        }
        if (req.specification() != null && !req.specification().isBlank()) {
            existing.setSpecification(req.specification().trim());
        }
        if (req.sourceType() != null && !req.sourceType().isBlank()) {
            if (!ProductSourceType.isValid(req.sourceType().trim())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "不支持的来源类型: " + req.sourceType());
            }
            existing.setSourceType(ProductSourceType.fromCode(req.sourceType().trim()).name());
        }
        if (req.baseUnitCode() != null && !req.baseUnitCode().isBlank()) {
            String unit = req.baseUnitCode().trim().toLowerCase();
            if (!"kg".equals(unit)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "产品基准计量单位仅允许 kg");
            }
            existing.setBaseUnitCode("kg");
        }
        if (req.status() != null && !req.status().isBlank()) {
            if (!ProductStatus.isValid(req.status().trim())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", "不支持的产品状态: " + req.status());
            }
            existing.setStatus(ProductStatus.fromCode(req.status().trim()).name());
        }

        existing.setUpdatedBy(principal.getUserId());
        existing.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));

        try {
            int affectedRows = productMapper.updateById(existing);
            if (affectedRows == 0) {
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "VERSION_CONFLICT",
                        "资源版本冲突",
                        "并发修改导致版本冲突，请刷新后重试"
                );
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "PRODUCT_CODE_CONFLICT",
                    "产品编码冲突",
                    "产品编码已存在"
            );
        }

        Product updated = productMapper.selectById(productId);
        return ProductResponse.fromEntity(updated);
    }

    private void checkPlatformScope(TraceSecurityPrincipal principal) {
        if (principal == null || principal.getScopes() == null || !principal.getScopes().contains("PLATFORM")) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED",
                    "无权访问",
                    "当前操作需要平台级管理权限 (PLATFORM scope)"
            );
        }
    }
}
