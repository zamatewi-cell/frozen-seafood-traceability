package com.example.traceability.quality.application;

import com.example.traceability.batch.application.BatchRiskService;
import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.quality.dto.BatchRiskHoldsResponse;
import com.example.traceability.quality.mapper.RecallBatchMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

import static com.example.traceability.quality.application.QualityWriteGuards.isPlatformScope;
import static com.example.traceability.quality.application.QualityWriteGuards.requireAuthenticated;

/**
 * 批次风险事项查询（Phase B 独立评审修复；统一业务契约 v1.1 §2.12 / §4.2 / §14）。
 * <p>
 * 风险事项与召回通知属于批次：批次的当前责任组织（任意角色）与平台只读角色查看批次尚未解除的告警风险事项、人工风险冻结，
 * 以及其他组织召回影响范围中对该批次的通知（批次交接后通知随批次转给新的当前责任组织）；其他组织（含已转出批次的历史持有方）403。
 * 只读：不产生任何写入，不获取任何锁。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Service
public class BatchRiskHoldService {

    private final BatchMapper batchMapper;
    private final BatchRiskService batchRiskService;
    private final RecallBatchMapper recallBatchMapper;

    public BatchRiskHoldService(BatchMapper batchMapper, BatchRiskService batchRiskService, RecallBatchMapper recallBatchMapper) {
        this.batchMapper = Objects.requireNonNull(batchMapper, "batchMapper 不能为空");
        this.batchRiskService = Objects.requireNonNull(batchRiskService, "batchRiskService 不能为空");
        this.recallBatchMapper = Objects.requireNonNull(recallBatchMapper, "recallBatchMapper 不能为空");
    }

    /**
     * 批次当前未解除的风险事项与上游召回通知。授权所依据的责任组织与响应数据来自同一只读 REPEATABLE READ 快照。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BatchRiskHoldsResponse getRiskHolds(Long batchId, TraceSecurityPrincipal principal) {
        requireAuthenticated(principal);
        Batch batch = batchMapper.selectByIdIgnoreTenant(batchId);
        if (batch == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + batchId + " 的批次");
        }
        if (!isPlatformScope(principal) && !Objects.equals(batch.getOrgId(), principal.getOrgId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权",
                    "只有批次当前责任组织可以查看批次的未解除风险事项与召回通知");
        }
        return BatchRiskHoldsResponse.of(batch, batchRiskService.openHolds(batchId, batch.getRiskStatus()),
                recallBatchMapper.selectNoticesByBatchId(batchId));
    }
}
