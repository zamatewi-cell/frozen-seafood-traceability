package com.example.traceability.masterdata.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.masterdata.domain.Product;
import com.example.traceability.masterdata.domain.ProductStatus;
import com.example.traceability.masterdata.domain.RuleStageCode;
import com.example.traceability.masterdata.domain.RuleStatus;
import com.example.traceability.masterdata.domain.TemperatureRule;
import com.example.traceability.masterdata.domain.TemperatureRuleIntervalValidator;
import com.example.traceability.masterdata.domain.TemperatureRuleStage;
import com.example.traceability.masterdata.domain.TemperatureUnit;
import com.example.traceability.masterdata.dto.TemperatureRuleCreateRequest;
import com.example.traceability.masterdata.dto.TemperatureRuleResponse;
import com.example.traceability.masterdata.dto.TemperatureRuleStageCreateRequest;
import com.example.traceability.masterdata.mapper.ProductMapper;
import com.example.traceability.masterdata.mapper.TemperatureRuleMapper;
import com.example.traceability.masterdata.mapper.TemperatureRuleStageMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 分阶段温控基准规则应用服务。
 * <p>
 * 编排温控规则版本查询、草稿规则创建以及不可变规则发布。
 * 遵循半开区间 [effectiveFrom, effectiveTo) 语义进行冲突防重叠校验。
 * 采用行锁 SELECT ... FOR UPDATE 与更新影响行数防御发布并发竞态。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Service
public class TemperatureRuleApplicationService {

    private final ProductMapper productMapper;
    private final TemperatureRuleMapper temperatureRuleMapper;
    private final TemperatureRuleStageMapper temperatureRuleStageMapper;

    public TemperatureRuleApplicationService(
            ProductMapper productMapper,
            TemperatureRuleMapper temperatureRuleMapper,
            TemperatureRuleStageMapper temperatureRuleStageMapper
    ) {
        this.productMapper = productMapper;
        this.temperatureRuleMapper = temperatureRuleMapper;
        this.temperatureRuleStageMapper = temperatureRuleStageMapper;
    }

    /**
     * 查询指定海产品下的所有温控规则版本及其环节明细。
     *
     * @param productId 产品ID
     * @return 规则方案列表包装封套
     */
    public SuccessEnvelope<List<TemperatureRuleResponse>> listRulesByProductId(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + productId + " 的海产品主数据");
        }

        // 1. 查询该产品下的所有规则列表，按版本升序排列
        List<TemperatureRule> rules = temperatureRuleMapper.selectList(
                new LambdaQueryWrapper<TemperatureRule>()
                        .eq(TemperatureRule::getProductId, productId)
                        .orderByAsc(TemperatureRule::getVersionNo)
        );

        if (rules.isEmpty()) {
            return SuccessEnvelope.of(List.of());
        }

        // 2. 批量查询环节明细并按 ruleId 分组
        List<Long> ruleIds = rules.stream().map(TemperatureRule::getId).toList();
        List<TemperatureRuleStage> allStages = temperatureRuleStageMapper.selectList(
                new LambdaQueryWrapper<TemperatureRuleStage>()
                        .in(TemperatureRuleStage::getRuleId, ruleIds)
                        .orderByAsc(TemperatureRuleStage::getSequenceNo)
                        .orderByAsc(TemperatureRuleStage::getId)
        );
        Map<Long, List<TemperatureRuleStage>> stageMap = allStages.stream()
                .collect(Collectors.groupingBy(TemperatureRuleStage::getRuleId));

        // 3. 组装响应对象
        List<TemperatureRuleResponse> responseList = rules.stream()
                .map(r -> TemperatureRuleResponse.of(r, stageMap.getOrDefault(r.getId(), List.of())))
                .toList();

        return SuccessEnvelope.of(responseList);
    }

    /**
     * 为指定产品创建温控基准规则草稿 (DRAFT)。
     * <p>
     * 通过 product 行锁排他分配版本号。规则版本号由服务端按当前产品自增生成。同一规则内 stageCode 唯一，且温标必须为 CELSIUS。
     * </p>
     *
     * @param productId 产品ID
     * @param req       创建规则请求
     * @param principal 当前认证主体
     * @return 创建完成的规则详情（含环节明细）
     */
    @Transactional
    public TemperatureRuleResponse createDraftRule(
            Long productId,
            TemperatureRuleCreateRequest req,
            TraceSecurityPrincipal principal
    ) {
        checkPlatformScope(principal);

        // 1. 排他锁定对应产品行，串行化版本号分配
        Product product = productMapper.selectByIdForUpdate(productId);
        if (product == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + productId + " 的海产品主数据");
        }

        // 2. 环节明细有效性与规范化校验
        if (req.stages() == null || req.stages().isEmpty()) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "TEMPERATURE_RULE_EMPTY_STAGES",
                    "温控规则环节明细为空",
                    "温控基准规则必须至少包含一个供应链环节明细"
            );
        }

        Set<String> seenStageCodes = new HashSet<>();
        for (TemperatureRuleStageCreateRequest stage : req.stages()) {
            if (!RuleStageCode.isValid(stage.stageCode())) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_REQUEST",
                        "参数校验失败",
                        "不支持的冷链环节代码: " + stage.stageCode()
                );
            }
            String canonicalStage = RuleStageCode.fromCode(stage.stageCode()).name();
            if (!seenStageCodes.add(canonicalStage)) {
                throw new BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "DUPLICATE_STAGE_CODE",
                        "环节代码重复",
                        "同一温控规则方案内环节代码必须唯一: " + stage.stageCode()
                );
            }
            if (!TemperatureUnit.CELSIUS.name().equalsIgnoreCase(stage.unitCode())) {
                throw new BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "INVALID_TEMPERATURE_UNIT",
                        "温标单位不合法",
                        "温标单位仅支持摄氏度 (CELSIUS)"
                );
            }
            if (stage.lowerLimit().compareTo(stage.upperLimit()) > 0) {
                throw new BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "TEMPERATURE_RULE_INVALID_LIMITS",
                        "温控阈值非法",
                        "温度下限阈值不得高于上限阈值: " + stage.lowerLimit() + " > " + stage.upperLimit()
                );
            }
            if (stage.allowedDurationSeconds() != null && stage.allowedDurationSeconds() < 0) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_REQUEST",
                        "参数校验失败",
                        "允许越界缓冲秒数必须大于或等于0"
                );
            }
            if (stage.sequenceNo() != null && stage.sequenceNo() < 1) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_REQUEST",
                        "参数校验失败",
                        "环节展示次序必须大于或等于1"
                );
            }
        }

        // 3. 生效时间区间校验：转换为 UTC LocalDateTime 入库
        LocalDateTime fromUtc = req.effectiveFrom().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime toUtc = req.effectiveTo() != null ? req.effectiveTo().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime() : null;

        if (toUtc != null && !toUtc.isAfter(fromUtc)) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_RULE_EFFECTIVE_PERIOD",
                    "规则生效区间非法",
                    "规则失效时间 effectiveTo 必须晚于生效起始时间 effectiveFrom"
            );
        }

        // 4. 服务端按产品原子生成自增版本号 versionNo
        int maxVersionNo = temperatureRuleMapper.findMaxVersionNoByProductId(productId);
        int nextVersionNo = maxVersionNo + 1;

        // 5. 创建规则主表记录
        TemperatureRule rule = new TemperatureRule();
        rule.setProductId(productId);
        rule.setVersionNo(nextVersionNo);
        rule.setName(req.name().trim());
        rule.setEffectiveFrom(fromUtc);
        rule.setEffectiveTo(toUtc);
        rule.setStatus(RuleStatus.DRAFT.name());
        rule.setBasisNote(req.basisNote() != null && !req.basisNote().isBlank() ? req.basisNote().trim() : null);
        rule.setVersion(0L);
        rule.setIsDeleted(0);
        rule.setCreatedBy(principal.getUserId());
        rule.setUpdatedBy(principal.getUserId());
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        rule.setCreatedAt(nowUtc);
        rule.setUpdatedAt(nowUtc);

        try {
            temperatureRuleMapper.insert(rule);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "VERSION_NO_CONFLICT",
                    "版本序号冲突",
                    "该产品下的温控规则版本号已存在，请重试"
            );
        }

        // 6. 插入各环节明细记录
        List<TemperatureRuleStage> stageEntities = new ArrayList<>();
        for (TemperatureRuleStageCreateRequest stageReq : req.stages()) {
            TemperatureRuleStage stage = new TemperatureRuleStage();
            stage.setRuleId(rule.getId());
            stage.setStageCode(RuleStageCode.fromCode(stageReq.stageCode()).name());
            stage.setLowerLimit(stageReq.lowerLimit());
            stage.setUpperLimit(stageReq.upperLimit());
            stage.setUnitCode(TemperatureUnit.CELSIUS.name());
            stage.setAllowedDurationSeconds(stageReq.allowedDurationSeconds() != null ? stageReq.allowedDurationSeconds() : 0);
            stage.setSequenceNo(stageReq.sequenceNo() != null ? stageReq.sequenceNo() : 1);
            stage.setVersion(0L);
            stage.setIsDeleted(0);
            stage.setCreatedBy(principal.getUserId());
            stage.setUpdatedBy(principal.getUserId());
            stage.setCreatedAt(nowUtc);
            stage.setUpdatedAt(nowUtc);

            try {
                temperatureRuleStageMapper.insert(stage);
            } catch (DuplicateKeyException e) {
                throw new BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "DUPLICATE_STAGE_CODE",
                        "环节代码重复",
                        "同一规则中环节代码必须唯一"
                );
            }
            stageEntities.add(stage);
        }

        return TemperatureRuleResponse.of(rule, stageEntities);
    }

    /**
     * 发布温控基准规则。
     * <p>
     * 发布先决条件：
     * 1. 对应产品状态必须为 ACTIVE（草稿或停用产品不能发布规则）；
     * 2. 待发布规则自身状态必须仍为 DRAFT（不可重复发布）；
     * 3. 规则必须包含至少一个环节明细；
     * 4. 在半开区间 [effectiveFrom, effectiveTo) 语义下，与当前产品下已有的 ACTIVE 规则区间不得重叠。
     * 5. 采用产品行锁加锁串行化，更新时带条件判断影响行数，影响 0 行立即报错拒绝。
     * </p>
     *
     * @param ruleId    规则ID
     * @param principal 当前认证主体
     * @return 发布成功后的规则详情
     */
    @Transactional
    public TemperatureRuleResponse publishRule(Long ruleId, TraceSecurityPrincipal principal) {
        checkPlatformScope(principal);

        TemperatureRule rule = temperatureRuleMapper.selectById(ruleId);
        if (rule == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + ruleId + " 的温控规则");
        }

        // 1. 排他锁定所属产品行，串行化并发发布检查
        Product product = productMapper.selectByIdForUpdate(rule.getProductId());
        if (product == null) {
            throw new ResourceNotFoundException("未找到关联的海产品主数据");
        }
        if (!ProductStatus.ACTIVE.name().equals(product.getStatus())) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "PRODUCT_NOT_ACTIVE",
                    "产品未启用",
                    "所属海产品主数据尚未启用或已被停用，无法为其发布温控规则"
            );
        }

        // 2. 加锁后重新读取规则最新数据，严格校验状态仍为 DRAFT
        rule = temperatureRuleMapper.selectById(ruleId);
        if (!RuleStatus.DRAFT.name().equals(rule.getStatus())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVALID_STATE_TRANSITION",
                    "非法状态流转",
                    "当前规则状态为 " + rule.getStatus() + "，只有 DRAFT 草稿状态的规则才允许发布"
            );
        }

        // 3. 校验环节明细非空
        List<TemperatureRuleStage> stages = temperatureRuleStageMapper.selectList(
                new LambdaQueryWrapper<TemperatureRuleStage>()
                        .eq(TemperatureRuleStage::getRuleId, ruleId)
                        .orderByAsc(TemperatureRuleStage::getSequenceNo)
                        .orderByAsc(TemperatureRuleStage::getId)
        );
        if (stages.isEmpty()) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "TEMPERATURE_RULE_EMPTY_STAGES",
                    "温控规则环节明细为空",
                    "待发布的温控规则必须包含至少一个供应链环节明细"
            );
        }

        // 4. 生效时间半开区间冲突防重叠校验
        List<TemperatureRule> activeRules = temperatureRuleMapper.selectList(
                new LambdaQueryWrapper<TemperatureRule>()
                        .eq(TemperatureRule::getProductId, rule.getProductId())
                        .eq(TemperatureRule::getStatus, RuleStatus.ACTIVE.name())
                        .ne(TemperatureRule::getId, ruleId)
        );

        for (TemperatureRule active : activeRules) {
            if (TemperatureRuleIntervalValidator.isOverlapping(
                    active.getEffectiveFrom(), active.getEffectiveTo(),
                    rule.getEffectiveFrom(), rule.getEffectiveTo()
            )) {
                throw new BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "TEMPERATURE_RULE_EFFECTIVE_CONFLICT",
                        "温控规则生效时间冲突",
                        "当前待发布规则的生效区间与已生效规则 (版本: v" + active.getVersionNo()
                                + ", 名称: " + active.getName() + ") 存在重叠冲突"
                );
            }
        }

        // 5. 原子条件更新变更规则状态为 ACTIVE，并检查影响行数防止并发伪装成功
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        int affectedRows = temperatureRuleMapper.updateStatusIfDraft(
                ruleId, RuleStatus.ACTIVE.name(), principal.getUserId(), nowUtc
        );

        if (affectedRows == 0) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVALID_STATE_TRANSITION",
                    "非法状态流转",
                    "规则状态已被并发修改，无法重复发布"
            );
        }

        rule.setStatus(RuleStatus.ACTIVE.name());
        rule.setVersion(rule.getVersion() != null ? rule.getVersion() + 1 : 1L);
        rule.setUpdatedBy(principal.getUserId());
        rule.setUpdatedAt(nowUtc);

        return TemperatureRuleResponse.of(rule, stages);
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
