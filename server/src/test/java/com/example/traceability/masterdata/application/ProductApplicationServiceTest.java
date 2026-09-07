package com.example.traceability.masterdata.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.masterdata.domain.Product;
import com.example.traceability.masterdata.dto.ProductCreateRequest;
import com.example.traceability.masterdata.dto.ProductPatchRequest;
import com.example.traceability.masterdata.dto.ProductQueryCriteria;
import com.example.traceability.masterdata.dto.ProductResponse;
import com.example.traceability.masterdata.mapper.ProductMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("产品主数据应用服务业务逻辑单元测试")
class ProductApplicationServiceTest {

    @Mock
    private ProductMapper productMapper;

    @InjectMocks
    private ProductApplicationService productService;

    private TraceSecurityPrincipal platformPrincipal;
    private TraceSecurityPrincipal operatorPrincipal;

    @BeforeEach
    void setUp() {
        platformPrincipal = new TraceSecurityPrincipal(
                1L, "admin", "平台管理员", "{noop}pwd",
                10L, "ORG_PLATFORM", "溯源监管中心", "SOURCE",
                List.of("ADMIN"), List.of("PLATFORM"), true, true
        );

        operatorPrincipal = new TraceSecurityPrincipal(
                2L, "operator", "普通操作员", "{noop}pwd",
                20L, "ORG_FISHERY", "东海远洋渔业", "SOURCE",
                List.of("OPERATOR"), List.of("ORG_ONLY"), true, true
        );
    }

    @Test
    @DisplayName("非 PLATFORM 用户创建产品主数据应抛出 403 ACCESS_DENIED")
    void nonPlatformUserCannotCreateProduct() {
        ProductCreateRequest req = new ProductCreateRequest(
                "PRD-SALMON-001", "大西洋三文鱼", "Salmo salar",
                "FISH", "整条 4-5kg 冷冻", "IMPORT", "kg", "ACTIVE"
        );

        assertThatThrownBy(() -> productService.createProduct(req, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(be.getCode()).isEqualTo("ACCESS_DENIED");
                });
    }

    @Test
    @DisplayName("PLATFORM 用户正常创建产品主数据，自动填充审计人与初始版本")
    void platformUserCanCreateProductSuccessfully() {
        ProductCreateRequest req = new ProductCreateRequest(
                "PRD-SALMON-001", "大西洋三文鱼", "Salmo salar",
                "FISH", "整条 4-5kg 冷冻", "IMPORT", "kg", "ACTIVE"
        );

        when(productMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(productMapper.insert(any(Product.class))).thenAnswer(invocation -> {
            Product p = invocation.getArgument(0);
            p.setId(100L);
            return 1;
        });

        ProductResponse response = productService.createProduct(req, platformPrincipal);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.productCode()).isEqualTo("PRD-SALMON-001");
        assertThat(response.version()).isEqualTo(0L);
        assertThat(response.createdBy()).isEqualTo(1L);
        assertThat(response.updatedBy()).isEqualTo(1L);
    }

    @Test
    @DisplayName("重复产品编码创建时应抛出 409 PRODUCT_CODE_CONFLICT")
    void duplicateProductCodeThrowsConflict() {
        ProductCreateRequest req = new ProductCreateRequest(
                "PRD-SALMON-001", "大西洋三文鱼", "Salmo salar",
                "FISH", "整条 4-5kg 冷冻", "IMPORT", "kg", "ACTIVE"
        );

        when(productMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        assertThatThrownBy(() -> productService.createProduct(req, platformPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("PRODUCT_CODE_CONFLICT");
                });
    }

    @Test
    @DisplayName("非法大类或来源类型创建时应抛出 400 INVALID_REQUEST")
    void invalidCategoryOrSourceThrowsInvalidRequest() {
        ProductCreateRequest invalidCategoryReq = new ProductCreateRequest(
                "PRD-001", "测试水产", null,
                "UNKNOWN_CAT", "500g", "IMPORT", "kg", "ACTIVE"
        );

        assertThatThrownBy(() -> productService.createProduct(invalidCategoryReq, platformPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_REQUEST");
                });
    }

    @Test
    @DisplayName("查询产品详情：存在则返回，不存在抛出 404 RESOURCE_NOT_FOUND")
    void getProductByIdBehavior() {
        when(productMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> productService.getProductById(999L))
                .isInstanceOf(ResourceNotFoundException.class);

        Product p = new Product();
        p.setId(100L);
        p.setProductCode("PRD-COD-001");
        p.setPublicName("银鳕鱼排");
        p.setCategory("FISH");
        p.setSpecification("300g/包");
        p.setSourceType("IMPORT");
        p.setBaseUnitCode("kg");
        p.setStatus("ACTIVE");
        p.setVersion(1L);
        when(productMapper.selectById(100L)).thenReturn(p);

        ProductResponse response = productService.getProductById(100L);
        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.publicName()).isEqualTo("银鳕鱼排");
    }

    @Test
    @DisplayName("PATCH 更新产品主数据：乐观锁版本不一致抛出 409 VERSION_CONFLICT")
    void patchProductWithOutdatedVersionThrowsVersionConflict() {
        Product existing = new Product();
        existing.setId(100L);
        existing.setProductCode("PRD-001");
        existing.setVersion(2L); // 库中当前版本为 2

        when(productMapper.selectById(100L)).thenReturn(existing);

        ProductPatchRequest patchReq = new ProductPatchRequest(
                1L, // 客户端传了旧版本 1
                null, "新名称", null, null, null, null, null, null
        );

        assertThatThrownBy(() -> productService.patchProduct(100L, patchReq, platformPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("VERSION_CONFLICT");
                });
    }

    @Test
    @DisplayName("PATCH 更新产品主数据：乐观锁匹配更新成功并自增版本")
    void patchProductSuccess() {
        Product existing = new Product();
        existing.setId(100L);
        existing.setProductCode("PRD-001");
        existing.setPublicName("原名称");
        existing.setCategory("FISH");
        existing.setSpecification("1kg");
        existing.setSourceType("IMPORT");
        existing.setBaseUnitCode("kg");
        existing.setStatus("ACTIVE");
        existing.setVersion(1L);

        when(productMapper.selectById(100L)).thenReturn(existing);
        when(productMapper.updateById(any(Product.class))).thenReturn(1);

        ProductPatchRequest patchReq = new ProductPatchRequest(
                1L, null, "修改后的公开名称", null, null, null, null, null, null
        );

        ProductResponse response = productService.patchProduct(100L, patchReq, platformPrincipal);
        assertThat(response.publicName()).isEqualTo("修改后的公开名称");
        assertThat(response.updatedBy()).isEqualTo(1L);
    }

    @Test
    @DisplayName("创建产品时输入小写枚举（fish/import/active），实体应自动规范化为大写 CANONICAL CODE 入库")
    void createProductWithLowerCaseEnumsShouldNormalizeToCanonicalCode() {
        ProductCreateRequest req = new ProductCreateRequest(
                "PRD-LOWER-001", "大黄鱼", null,
                "fish", "500g", "import", "kg", "active"
        );

        when(productMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(productMapper.insert(any(Product.class))).thenAnswer(invocation -> {
            Product p = invocation.getArgument(0);
            assertThat(p.getCategory()).isEqualTo("FISH");
            assertThat(p.getSourceType()).isEqualTo("IMPORT");
            assertThat(p.getStatus()).isEqualTo("ACTIVE");
            p.setId(101L);
            return 1;
        });

        ProductResponse response = productService.createProduct(req, platformPrincipal);
        assertThat(response).isNotNull();
        assertThat(response.category()).isEqualTo("FISH");
        assertThat(response.sourceType()).isEqualTo("IMPORT");
        assertThat(response.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("创建产品时传入非 kg 的基准单位应抛出 400 INVALID_REQUEST")
    void createProductWithNonKgUnitThrowsInvalidRequest() {
        ProductCreateRequest req = new ProductCreateRequest(
                "PRD-UNIT-001", "海虾", null,
                "CRUSTACEAN", "1kg", "DOMESTIC_FARMED", "ton", "ACTIVE"
        );

        assertThatThrownBy(() -> productService.createProduct(req, platformPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_REQUEST");
                    assertThat(be.getMessage()).contains("kg");
                });
    }

    @Test
    @DisplayName("PATCH 更新产品时传入非 kg 的基准单位应抛出 400 INVALID_REQUEST")
    void patchProductWithNonKgUnitThrowsInvalidRequest() {
        Product existing = new Product();
        existing.setId(100L);
        existing.setProductCode("PRD-001");
        existing.setVersion(0L);

        when(productMapper.selectById(100L)).thenReturn(existing);

        ProductPatchRequest patchReq = new ProductPatchRequest(
                0L, null, null, null, null, null, null, "g", null
        );

        assertThatThrownBy(() -> productService.patchProduct(100L, patchReq, platformPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_REQUEST");
                });
    }
}
