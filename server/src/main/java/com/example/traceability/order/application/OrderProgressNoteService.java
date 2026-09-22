package com.example.traceability.order.application;

import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.order.domain.OrderProgressNote;
import com.example.traceability.order.domain.PurchaseOrder;
import com.example.traceability.order.domain.SalesOrder;
import com.example.traceability.order.dto.OrderNoteCreateRequest;
import com.example.traceability.order.dto.OrderNoteResponse;
import com.example.traceability.order.mapper.OrderProgressNoteMapper;
import com.example.traceability.order.mapper.PurchaseOrderMapper;
import com.example.traceability.order.mapper.SalesOrderMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 订单进度备注应用服务。
 * <p>
 * 订单级备注时间线，买卖双方组织均可查看，实现上下游实时可见。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.3.0
 */
@Service
public class OrderProgressNoteService {

    private final OrderProgressNoteMapper noteMapper;
    private final PurchaseOrderMapper purchaseOrderMapper;
    private final SalesOrderMapper salesOrderMapper;

    public OrderProgressNoteService(OrderProgressNoteMapper noteMapper,
                                    PurchaseOrderMapper purchaseOrderMapper,
                                    SalesOrderMapper salesOrderMapper) {
        this.noteMapper = noteMapper;
        this.purchaseOrderMapper = purchaseOrderMapper;
        this.salesOrderMapper = salesOrderMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderNoteResponse addNote(String orderType, Long orderId, OrderNoteCreateRequest request,
                                     TraceSecurityPrincipal principal) {
        Long orgId = principal.getOrgId();
        if ("PURCHASE".equals(orderType)) {
            PurchaseOrder order = purchaseOrderMapper.selectById(orderId);
            if (order == null) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND",
                        "订单不存在", "采购进货单不存在");
            }
            if (!order.getBuyerOrgId().equals(orgId) && !order.getSellerOrgId().equals(orgId)) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "ORDER_ACCESS_DENIED",
                        "无权访问", "当前组织与该采购单无关");
            }
        } else if ("SALES".equals(orderType)) {
            SalesOrder order = salesOrderMapper.selectById(orderId);
            if (order == null) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND",
                        "订单不存在", "销售订单不存在");
            }
            if (!order.getSellerOrgId().equals(orgId)) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "ORDER_ACCESS_DENIED",
                        "无权访问", "当前组织与该销售单无关");
            }
        } else {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ORDER_TYPE",
                    "订单类型非法", "仅支持 PURCHASE 或 SALES");
        }

        OrderProgressNote note = new OrderProgressNote();
        note.setOrderId(orderId);
        note.setOrderType(orderType);
        note.setNoteText(request.noteText());
        note.setStatusAt(request.statusAt());
        note.setIsTerminalVisible(request.isTerminalVisible() == null ? 1 : request.isTerminalVisible());
        note.setOrgId(orgId);
        note.setCreatedBy(principal.getUserId());
        note.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        note.setVersion(0L);
        note.setIsDeleted(0);
        noteMapper.insert(note);
        return toResponse(note);
    }

    public List<OrderNoteResponse> listNotes(String orderType, Long orderId, TraceSecurityPrincipal principal) {
        return noteMapper.selectByOrder(orderId, orderType).stream()
                .map(this::toResponse).toList();
    }

    private OrderNoteResponse toResponse(OrderProgressNote note) {
        return new OrderNoteResponse(
                note.getId(),
                note.getOrderId(),
                note.getOrderType(),
                note.getNoteText(),
                note.getStatusAt(),
                note.getIsTerminalVisible(),
                note.getOrgId(),
                note.getCreatedBy(),
                note.getCreatedAt()
        );
    }
}
