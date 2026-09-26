package com.example.traceability.masterdata.domain;

import java.math.BigDecimal;

/**
 * 按产品、环节与测量业务时间匹配到的已发布温控规则环节（只读投影，契约 v1.1 §10.1）。
 * <p>
 * 由 {@code TemperatureRuleStageMapper#selectApplicableStages} 填充：规则状态 ACTIVE、未删除、
 * 生效区间 {@code [effectiveFrom, effectiveTo)} 覆盖测量时间。已发布规则不可修改，新版本通过新增规则发布，
 * 因此 {@code ruleStageId} 连同规则版本号即为“匹配规则版本”。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public class TemperatureRuleStageMatch {

    private Long ruleStageId;
    private Long ruleId;
    private String ruleName;
    private Integer ruleVersionNo;
    private String stageCode;
    private BigDecimal lowerLimit;
    private BigDecimal upperLimit;
    private Integer allowedDurationSeconds;

    public Long getRuleStageId() {
        return ruleStageId;
    }

    public void setRuleStageId(Long ruleStageId) {
        this.ruleStageId = ruleStageId;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public void setRuleId(Long ruleId) {
        this.ruleId = ruleId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public Integer getRuleVersionNo() {
        return ruleVersionNo;
    }

    public void setRuleVersionNo(Integer ruleVersionNo) {
        this.ruleVersionNo = ruleVersionNo;
    }

    public String getStageCode() {
        return stageCode;
    }

    public void setStageCode(String stageCode) {
        this.stageCode = stageCode;
    }

    public BigDecimal getLowerLimit() {
        return lowerLimit;
    }

    public void setLowerLimit(BigDecimal lowerLimit) {
        this.lowerLimit = lowerLimit;
    }

    public BigDecimal getUpperLimit() {
        return upperLimit;
    }

    public void setUpperLimit(BigDecimal upperLimit) {
        this.upperLimit = upperLimit;
    }

    public Integer getAllowedDurationSeconds() {
        return allowedDurationSeconds;
    }

    public void setAllowedDurationSeconds(Integer allowedDurationSeconds) {
        this.allowedDurationSeconds = allowedDurationSeconds;
    }
}
