package com.example.traceability.quality.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 质检清单模板项。
 * <p>每个出货环节(CAPTURE_OUT/PROCESS_OUT/DIST_OUT)对应一组清单项，
 * 质检员需逐项打钩确认后才能提交质检通过。</p>
 */
@TableName("quality_checklist_template")
public class QualityChecklistTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("stage_code")
    private String stageCode;

    @TableField("org_type")
    private String orgType;

    @TableField("item_name")
    private String itemName;

    @TableField("item_desc")
    private String itemDesc;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("is_required")
    private Integer isRequired;

    @TableField("status")
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStageCode() { return stageCode; }
    public void setStageCode(String stageCode) { this.stageCode = stageCode; }
    public String getOrgType() { return orgType; }
    public void setOrgType(String orgType) { this.orgType = orgType; }
    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }
    public String getItemDesc() { return itemDesc; }
    public void setItemDesc(String itemDesc) { this.itemDesc = itemDesc; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Integer getIsRequired() { return isRequired; }
    public void setIsRequired(Integer isRequired) { this.isRequired = isRequired; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
