package com.example.traceability.masterdata.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.masterdata.domain.Product;
import com.example.traceability.masterdata.domain.ProductStatus;
import com.example.traceability.masterdata.domain.RuleStatus;
import com.example.traceability.masterdata.domain.TemperatureRule;
import com.example.traceability.masterdata.domain.TemperatureRuleStage;
import com.example.traceability.masterdata.dto.TemperatureRuleCreateRequest;
import com.example.traceability.masterdata.dto.TemperatureRuleResponse;
import com.example.traceability.masterdata.dto.TemperatureRuleStageCreateRequest;
import com.example.traceability.masterdata.mapper.ProductMapper;
import com.example.traceability.masterdata.mapper.TemperatureRuleMapper;
import com.example.traceability.masterdata.mapper.TemperatureRuleStageMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("温控规则应用服务业务逻辑单元测试")
class TemperatureRuleApplicationServiceTest {

    @Mock
    private ProductMapper productMapper;

    @Mock
    private TemperatureRuleMapper temperatureRuleMapper;

    @Mock
    private TemperatureRuleStageMapper temperatureRuleStageMapper;

    @InjectMocks
    private TemperatureRuleApplicationService ruleService;

    private TraceSecurityPrincipal platformPrincipal;
    private TraceSecurityPrincipal operatorPrincipal;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), TemperatureRule.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), TemperatureRuleStage.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Product.class);

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
    @DisplayName("非 PLATFORM 用户创建规则草稿抛出 403 ACCESS_DENIED")
    void nonPlatformUserCannotCreateRule() {
        TemperatureRuleCreateRequest req = new TemperatureRuleCreateRequest(
                "冷冻金枪鱼温控基准",
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                "GB 10136",
                List.of(new TemperatureRuleStageCreateRequest("STORAGE", new BigDecimal("-25"), new BigDecimal("-18"), "CELSIUS", 0, 1))
        );

        assertThatThrownBy(() -> ruleService.createDraftRule(100L, req, operatorPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(be.getCode()).isEqualTo("ACCESS_DENIED");
                });
    }

    @Test
    @DisplayName("规则版本自增：调用 selectByIdForUpdate 锁定产品行，初次创建递增生成版本号")
    void ruleVersionShouldIncrementPerProductWithRowLock() {
        Product p = new Product();
        p.setId(100L);
        p.setStatus(ProductStatus.ACTIVE.name());
        when(productMapper.selectByIdForUpdate(100L)).thenReturn(p);
        when(temperatureRuleMapper.findMaxVersionNoByProductId(100L)).thenReturn(2);

        when(temperatureRuleMapper.insert(any(TemperatureRule.class))).thenAnswer(invocation -> {
            TemperatureRule rule = invocation.getArgument(0);
            rule.setId(201L);
            return 1;
        });

        // 客户端传入带 +08:00 偏移的时间
        OffsetDateTime from8 = OffsetDateTime.of(2026, 6, 1, 8, 0, 0, 0, ZoneOffset.ofHours(8));
        TemperatureRuleCreateRequest req = new TemperatureRuleCreateRequest(
                "三文鱼温控方案v3",
                from8,
                null,
                "行业温控规范",
                List.of(new TemperatureRuleStageCreateRequest("STORAGE", new BigDecimal("-22"), new BigDecimal("-18"), "CELSIUS", 0, 1))
        );

        TemperatureRuleResponse resp = ruleService.createDraftRule(100L, req, platformPrincipal);
        assertThat(resp.versionNo()).isEqualTo(3);
        assertThat(resp.status()).isEqualTo("DRAFT");

        // 验证调用了 selectByIdForUpdate 串行化锁
        verify(productMapper).selectByIdForUpdate(100L);
    }

    @Test
    @DisplayName("创建规则时温度阈值反向 (lowerLimit > upperLimit) 应抛出 422 TEMPERATURE_RULE_INVALID_LIMITS")
    void invertedLimitsShouldThrowException() {
        Product p = new Product();
        p.setId(100L);
        when(productMapper.selectByIdForUpdate(100L)).thenReturn(p);

        TemperatureRuleCreateRequest req = new TemperatureRuleCreateRequest(
                "异常阈值规则",
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                null,
                List.of(new TemperatureRuleStageCreateRequest(
                        "STORAGE",
                        new BigDecimal("-15"), // 下限 -15 高于 上限 -18
                        new BigDecimal("-18"),
                        "CELSIUS", 0, 1
                ))
        );

        assertThatThrownBy(() -> ruleService.createDraftRule(100L, req, platformPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(be.getCode()).isEqualTo("TEMPERATURE_RULE_INVALID_LIMITS");
                });
    }

    @Test
    @DisplayName("创建规则时温标非 CELSIUS 应抛出 422 INVALID_TEMPERATURE_UNIT")
    void nonCelsiusUnitShouldThrowException() {
        Product p = new Product();
        p.setId(100L);
        when(productMapper.selectByIdForUpdate(100L)).thenReturn(p);

        TemperatureRuleCreateRequest req = new TemperatureRuleCreateRequest(
                "华氏度规则",
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                null,
                List.of(new TemperatureRuleStageCreateRequest(
                        "STORAGE",
                        new BigDecimal("-20"),
                        new BigDecimal("-18"),
                        "FAHRENHEIT", 0, 1
                ))
        );

        assertThatThrownBy(() -> ruleService.createDraftRule(100L, req, platformPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(be.getCode()).isEqualTo("INVALID_TEMPERATURE_UNIT");
                });
    }

    @Test
    @DisplayName("创建规则时同一规则方案内 stageCode 重复应抛出 422 DUPLICATE_STAGE_CODE")
    void duplicateStageCodeShouldThrowException() {
        Product p = new Product();
        p.setId(100L);
        when(productMapper.selectByIdForUpdate(100L)).thenReturn(p);

        TemperatureRuleCreateRequest req = new TemperatureRuleCreateRequest(
                "重复环节规则",
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                null,
                List.of(
                        new TemperatureRuleStageCreateRequest("STORAGE", new BigDecimal("-25"), new BigDecimal("-18"), "CELSIUS", 0, 1),
                        new TemperatureRuleStageCreateRequest("storage", new BigDecimal("-30"), new BigDecimal("-20"), "CELSIUS", 0, 2)
                )
        );

        assertThatThrownBy(() -> ruleService.createDraftRule(100L, req, platformPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(be.getCode()).isEqualTo("DUPLICATE_STAGE_CODE");
                });
    }

    @Test
    @DisplayName("创建规则时 allowedDurationSeconds 小于 0 应抛出 400 INVALID_REQUEST")
    void negativeDurationShouldThrowException() {
        Product p = new Product();
        p.setId(100L);
        when(productMapper.selectByIdForUpdate(100L)).thenReturn(p);

        TemperatureRuleCreateRequest req = new TemperatureRuleCreateRequest(
                "负数缓冲秒数规则",
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                null,
                List.of(new TemperatureRuleStageCreateRequest("STORAGE", new BigDecimal("-25"), new BigDecimal("-18"), "CELSIUS", -1, 1))
        );

        assertThatThrownBy(() -> ruleService.createDraftRule(100L, req, platformPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_REQUEST");
                });
    }

    @Test
    @DisplayName("创建规则时 sequenceNo 小于 1 应抛出 400 INVALID_REQUEST")
    void invalidSequenceNoShouldThrowException() {
        Product p = new Product();
        p.setId(100L);
        when(productMapper.selectByIdForUpdate(100L)).thenReturn(p);

        TemperatureRuleCreateRequest req = new TemperatureRuleCreateRequest(
                "非法次序规则",
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                null,
                List.of(new TemperatureRuleStageCreateRequest("STORAGE", new BigDecimal("-25"), new BigDecimal("-18"), "CELSIUS", 0, 0))
        );

        assertThatThrownBy(() -> ruleService.createDraftRule(100L, req, platformPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_REQUEST");
                });
    }

    @Test
    @DisplayName("创建规则时 effectiveTo 早于或等于 effectiveFrom 应抛出 422 INVALID_RULE_EFFECTIVE_PERIOD")
    void invalidEffectivePeriodShouldThrowException() {
        Product p = new Product();
        p.setId(100L);
        when(productMapper.selectByIdForUpdate(100L)).thenReturn(p);

        OffsetDateTime from = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime to = OffsetDateTime.of(2026, 5, 1, 0, 0, 0, 0, ZoneOffset.UTC); // 晚于 from 不满足

        TemperatureRuleCreateRequest req = new TemperatureRuleCreateRequest(
                "倒置有效期规则", from, to, null,
                List.of(new TemperatureRuleStageCreateRequest("TRANSPORT", new BigDecimal("-22"), new BigDecimal("-18"), "CELSIUS", 0, 1))
        );

        assertThatThrownBy(() -> ruleService.createDraftRule(100L, req, platformPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(be.getCode()).isEqualTo("INVALID_RULE_EFFECTIVE_PERIOD");
                });
    }

    @Test
    @DisplayName("停用或草稿状态产品不能发布规则，抛出 422 PRODUCT_NOT_ACTIVE")
    void inactiveProductCannotPublishRule() {
        TemperatureRule draftRule = new TemperatureRule();
        draftRule.setId(301L);
        draftRule.setProductId(100L);
        draftRule.setStatus(RuleStatus.DRAFT.name());
        when(temperatureRuleMapper.selectById(301L)).thenReturn(draftRule);

        Product inactiveProduct = new Product();
        inactiveProduct.setId(100L);
        inactiveProduct.setStatus(ProductStatus.INACTIVE.name()); // 停用状态
        when(productMapper.selectByIdForUpdate(100L)).thenReturn(inactiveProduct);

        assertThatThrownBy(() -> ruleService.publishRule(301L, platformPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(be.getCode()).isEqualTo("PRODUCT_NOT_ACTIVE");
                });
    }

    @Test
    @DisplayName("待发布规则无环节明细时不能发布，抛出 422 TEMPERATURE_RULE_EMPTY_STAGES")
    void ruleWithoutStagesCannotBePublished() {
        TemperatureRule draftRule = new TemperatureRule();
        draftRule.setId(301L);
        draftRule.setProductId(100L);
        draftRule.setStatus(RuleStatus.DRAFT.name());
        when(temperatureRuleMapper.selectById(301L)).thenReturn(draftRule);

        Product activeProduct = new Product();
        activeProduct.setId(100L);
        activeProduct.setStatus(ProductStatus.ACTIVE.name());
        when(productMapper.selectByIdForUpdate(100L)).thenReturn(activeProduct);

        when(temperatureRuleStageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        assertThatThrownBy(() -> ruleService.publishRule(301L, platformPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(be.getCode()).isEqualTo("TEMPERATURE_RULE_EMPTY_STAGES");
                });
    }

    @Test
    @DisplayName("重新读取非 DRAFT 状态规则抛出 409 INVALID_STATE_TRANSITION")
    void publishingActiveRuleThrowsConflict() {
        TemperatureRule activeRule = new TemperatureRule();
        activeRule.setId(301L);
        activeRule.setProductId(100L);
        activeRule.setStatus(RuleStatus.ACTIVE.name()); // 已是 ACTIVE
        when(temperatureRuleMapper.selectById(301L)).thenReturn(activeRule);

        Product activeProduct = new Product();
        activeProduct.setId(100L);
        activeProduct.setStatus(ProductStatus.ACTIVE.name());
        when(productMapper.selectByIdForUpdate(100L)).thenReturn(activeProduct);

        assertThatThrownBy(() -> ruleService.publishRule(301L, platformPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("INVALID_STATE_TRANSITION");
                });
    }

    @Test
    @DisplayName("发布规则时时间区间重叠冲突抛出 422 TEMPERATURE_RULE_EFFECTIVE_CONFLICT")
    void overlappingRuleConflictThrowsException() {
        TemperatureRule draftRule = new TemperatureRule();
        draftRule.setId(301L);
        draftRule.setProductId(100L);
        draftRule.setVersionNo(2);
        draftRule.setStatus(RuleStatus.DRAFT.name());
        draftRule.setEffectiveFrom(LocalDateTime.of(2026, 5, 1, 0, 0));
        draftRule.setEffectiveTo(LocalDateTime.of(2026, 9, 1, 0, 0));
        when(temperatureRuleMapper.selectById(301L)).thenReturn(draftRule);

        Product activeProduct = new Product();
        activeProduct.setId(100L);
        activeProduct.setStatus(ProductStatus.ACTIVE.name());
        when(productMapper.selectByIdForUpdate(100L)).thenReturn(activeProduct);

        TemperatureRuleStage stage = new TemperatureRuleStage();
        stage.setId(1L);
        stage.setRuleId(301L);
        stage.setStageCode("STORAGE");
        stage.setLowerLimit(new BigDecimal("-25"));
        stage.setUpperLimit(new BigDecimal("-18"));
        when(temperatureRuleStageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(stage));

        // 已存在 ACTIVE 规则：2026-01-01 至 2026-06-01，与待发布规则 [2026-05-01, 2026-09-01) 发生重叠
        TemperatureRule activeExisting = new TemperatureRule();
        activeExisting.setId(201L);
        activeExisting.setProductId(100L);
        activeExisting.setVersionNo(1);
        activeExisting.setName("已有规则v1");
        activeExisting.setStatus(RuleStatus.ACTIVE.name());
        activeExisting.setEffectiveFrom(LocalDateTime.of(2026, 1, 1, 0, 0));
        activeExisting.setEffectiveTo(LocalDateTime.of(2026, 6, 1, 0, 0));
        when(temperatureRuleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(activeExisting));

        assertThatThrownBy(() -> ruleService.publishRule(301L, platformPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(be.getCode()).isEqualTo("TEMPERATURE_RULE_EFFECTIVE_CONFLICT");
                });
    }

    @Test
    @DisplayName("发布规则时若并发导致 update 返回 0 行，必须抛出 409 INVALID_STATE_TRANSITION 拒绝假成功")
    void concurrentPublishReturningZeroRowsShouldThrowConflict() {
        TemperatureRule draftRule = new TemperatureRule();
        draftRule.setId(301L);
        draftRule.setProductId(100L);
        draftRule.setVersionNo(2);
        draftRule.setStatus(RuleStatus.DRAFT.name());
        draftRule.setEffectiveFrom(LocalDateTime.of(2026, 7, 1, 0, 0));
        when(temperatureRuleMapper.selectById(301L)).thenReturn(draftRule);

        Product activeProduct = new Product();
        activeProduct.setId(100L);
        activeProduct.setStatus(ProductStatus.ACTIVE.name());
        when(productMapper.selectByIdForUpdate(100L)).thenReturn(activeProduct);

        TemperatureRuleStage stage = new TemperatureRuleStage();
        stage.setId(1L);
        stage.setRuleId(301L);
        stage.setStageCode("STORAGE");
        stage.setLowerLimit(new BigDecimal("-25"));
        stage.setUpperLimit(new BigDecimal("-18"));
        when(temperatureRuleStageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(stage));
        when(temperatureRuleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        // 模拟并发更新导致 update 影响行数为 0
        when(temperatureRuleMapper.updateStatusIfDraft(eq(301L), eq(RuleStatus.ACTIVE.name()), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> ruleService.publishRule(301L, platformPrincipal))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("INVALID_STATE_TRANSITION");
                });
    }

    @Test
    @DisplayName("相邻但不重叠的时间区间发布成功，状态变为 ACTIVE")
    void adjacentRulePublishSuccess() {
        TemperatureRule draftRule = new TemperatureRule();
        draftRule.setId(302L);
        draftRule.setProductId(100L);
        draftRule.setVersionNo(2);
        draftRule.setStatus(RuleStatus.DRAFT.name());
        // [2026-06-01, 2026-12-01)
        draftRule.setEffectiveFrom(LocalDateTime.of(2026, 6, 1, 0, 0));
        draftRule.setEffectiveTo(LocalDateTime.of(2026, 12, 1, 0, 0));
        draftRule.setVersion(0L);
        when(temperatureRuleMapper.selectById(302L)).thenReturn(draftRule);

        Product activeProduct = new Product();
        activeProduct.setId(100L);
        activeProduct.setStatus(ProductStatus.ACTIVE.name());
        when(productMapper.selectByIdForUpdate(100L)).thenReturn(activeProduct);

        TemperatureRuleStage stage = new TemperatureRuleStage();
        stage.setId(2L);
        stage.setRuleId(302L);
        stage.setStageCode("TRANSPORT");
        stage.setLowerLimit(new BigDecimal("-25"));
        stage.setUpperLimit(new BigDecimal("-18"));
        when(temperatureRuleStageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(stage));

        // 已有规则：[2026-01-01, 2026-06-01)，正好在 2026-06-01 结束，与待发布规则相邻不重叠
        TemperatureRule activeExisting = new TemperatureRule();
        activeExisting.setId(201L);
        activeExisting.setProductId(100L);
        activeExisting.setVersionNo(1);
        activeExisting.setName("已有规则v1");
        activeExisting.setStatus(RuleStatus.ACTIVE.name());
        activeExisting.setEffectiveFrom(LocalDateTime.of(2026, 1, 1, 0, 0));
        activeExisting.setEffectiveTo(LocalDateTime.of(2026, 6, 1, 0, 0));
        when(temperatureRuleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(activeExisting));

        when(temperatureRuleMapper.updateStatusIfDraft(eq(302L), eq(RuleStatus.ACTIVE.name()), any(), any())).thenReturn(1);

        TemperatureRuleResponse resp = ruleService.publishRule(302L, platformPrincipal);
        assertThat(resp.status()).isEqualTo(RuleStatus.ACTIVE.name());
        assertThat(resp.version()).isEqualTo(1L);
    }
}
