package com.example.traceability.sale.application;

import com.example.traceability.batch.application.BatchQuantityService;
import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.batch.mapper.BatchOperationItemMapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.domain.Site;
import com.example.traceability.identity.mapper.SiteMapper;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.sale.domain.Sale;
import com.example.traceability.sale.dto.SaleCreateRequest;
import com.example.traceability.sale.dto.SaleResponse;
import com.example.traceability.sale.mapper.SaleMapper;
import com.example.traceability.trace.application.TraceEventApplicationService;
import com.example.traceability.trace.application.TraceEventApplicationService.SaleProjection;
import com.example.traceability.trace.mapper.TransferMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 终端销售应用服务单元测试（Phase A Slice 5）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("终端销售应用服务：权限、校验顺序、STORE、状态、超卖、售罄关闭、幂等与 SALE 事件")
class SaleApplicationServiceTest {

    private static final long RETAILER_ORG = 40L;
    private static final long OTHER_ORG = 50L;
    private static final long BATCH_ID = 3000L;
    private static final long STORE_ID = 800L;
    private static final String KEY = "sale-key-0000000000000001";
    private static final OffsetDateTime OCCURRED = OffsetDateTime.of(2026, 9, 23, 9, 30, 15, 123456789, ZoneOffset.ofHours(8));

    @Mock private SaleMapper saleMapper;
    @Mock private BatchMapper batchMapper;
    @Mock private SiteMapper siteMapper;
    @Mock private TransferMapper transferMapper;
    @Mock private BatchOperationItemMapper itemMapper;
    @Mock private TraceEventApplicationService traceEventService;

    private SaleApplicationService service;
    private TraceSecurityPrincipal retailer;
    private Batch batch;

    @BeforeEach
    void setUp() {
        service = new SaleApplicationService(saleMapper, batchMapper, siteMapper, transferMapper,
                new BatchQuantityService(itemMapper, saleMapper), traceEventService);
        retailer = principal(RETAILER_ORG, "RETAILER", List.of("OPERATOR"), List.of("ORG_ONLY"));
        batch = new Batch();
        batch.setId(BATCH_ID);
        batch.setOrgId(RETAILER_ORG);
        batch.setTraceBatchNo("TB-B2");
        batch.setQuantity(new BigDecimal("600.000"));
        batch.setUnitCode("kg");
        batch.setFlowStatus("ACTIVE");
        batch.setRiskStatus("NORMAL");
        batch.setVersion(3L);
        when(batchMapper.selectByIdIgnoreTenantForUpdate(BATCH_ID)).thenReturn(batch);
        when(batchMapper.selectByIdIgnoreTenant(BATCH_ID)).thenReturn(batch);
        when(siteMapper.selectByIdIgnoreTenant(STORE_ID)).thenReturn(site(STORE_ID, RETAILER_ORG, "STORE", "ACTIVE"));
        when(itemMapper.sumSubmittedInputQuantityByBatchId(BATCH_ID)).thenReturn(BigDecimal.ZERO);
        when(saleMapper.sumSubmittedQuantityByBatchId(BATCH_ID)).thenReturn(BigDecimal.ZERO);
        when(transferMapper.countActiveTransfersByBatchId(BATCH_ID)).thenReturn(0);
        when(saleMapper.insert(any(Sale.class))).thenAnswer(inv -> {
            inv.<Sale>getArgument(0).setId(9001L);
            return 1;
        });
        when(batchMapper.applySale(anyLong(), anyLong(), anyLong(), anyBoolean(), any(), anyLong())).thenReturn(1);
    }

    private static TraceSecurityPrincipal principal(long orgId, String orgType, List<String> roles, List<String> scopes) {
        return new TraceSecurityPrincipal(401L, "user_" + orgId, "操作员", "hash",
                orgId, "ORG-" + orgId, "组织" + orgId, orgType, roles, scopes, true, true);
    }

    private static Site site(long id, long orgId, String type, String status) {
        Site s = new Site();
        s.setId(id);
        s.setOrgId(orgId);
        s.setName("场所" + id);
        s.setSiteType(type);
        s.setStatus(status);
        return s;
    }

    private static SaleCreateRequest req(String qty) {
        return new SaleCreateRequest(STORE_ID, new BigDecimal(qty), OCCURRED);
    }

    private static void assertBusiness(Throwable ex, HttpStatus status, String code) {
        assertThat(ex).isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) ex;
        assertThat(be.getStatus()).isEqualTo(status);
        assertThat(be.getCode()).isEqualTo(code);
    }

    private void expectFailure(SaleCreateRequest request, TraceSecurityPrincipal who, HttpStatus status, String code) {
        assertThatThrownBy(() -> service.createSale(BATCH_ID, request, KEY, who))
                .satisfies(ex -> assertBusiness(ex, status, code));
        assertNoWrites();
    }

    private void assertNoWrites() {
        verify(saleMapper, never()).insert(any(Sale.class));
        verify(batchMapper, never()).applySale(anyLong(), anyLong(), anyLong(), anyBoolean(), any(), anyLong());
        verify(traceEventService, never()).appendSaleEvent(any(), any(), any());
    }

    private Sale persisted(String qty, String hash) {
        Sale s = new Sale();
        s.setId(9001L);
        s.setOrgId(RETAILER_ORG);
        s.setBatchId(BATCH_ID);
        s.setSiteId(STORE_ID);
        s.setQuantity(new BigDecimal(qty));
        s.setUnitCode("kg");
        s.setOccurredAt(OCCURRED.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime().withNano(123456000));
        s.setStatus("SUBMITTED");
        s.setIdempotencyKey(KEY);
        s.setRequestHash(hash);
        s.setCreatedBy(401L);
        return s;
    }

    private static String hashOf(String qty) {
        return SaleApplicationService.computeRequestHash(BATCH_ID, STORE_ID, new BigDecimal(qty),
                OCCURRED.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime().withNano(123456000));
    }

    @Nested
    @DisplayName("成功路径")
    class Success {

        @Test
        @DisplayName("部分销售 200/600：写入 Sale（业务时间 UTC 微秒）、批次标记不关闭、SALE 事件剩余 400")
        void partialSale() {
            SaleResponse resp = service.createSale(BATCH_ID, req("200"), KEY, retailer);

            ArgumentCaptor<Sale> sale = ArgumentCaptor.forClass(Sale.class);
            verify(saleMapper).insert(sale.capture());
            Sale s = sale.getValue();
            assertThat(s.getOrgId()).isEqualTo(RETAILER_ORG);
            assertThat(s.getBatchId()).isEqualTo(BATCH_ID);
            assertThat(s.getSiteId()).isEqualTo(STORE_ID);
            assertThat(s.getQuantity()).isEqualByComparingTo("200");
            assertThat(s.getUnitCode()).isEqualTo("kg");
            assertThat(s.getStatus()).isEqualTo("SUBMITTED");
            assertThat(s.getOccurredAt()).isEqualTo(LocalDateTime.of(2026, 9, 23, 1, 30, 15, 123456000));
            assertThat(s.getRequestHash()).isEqualTo(hashOf("200"));
            verify(batchMapper).applySale(eq(BATCH_ID), eq(RETAILER_ORG), eq(9001L), eq(false), any(), eq(401L));

            ArgumentCaptor<SaleProjection> projection = ArgumentCaptor.forClass(SaleProjection.class);
            verify(traceEventService, times(1)).appendSaleEvent(projection.capture(), eq(401L), any());
            assertThat(projection.getValue().remainingAfter()).isEqualByComparingTo("400");
            assertThat(projection.getValue().soldOut()).isFalse();
            assertThat(projection.getValue().occurredAtUtc()).isEqualTo(s.getOccurredAt());
            assertThat(projection.getValue().siteId()).isEqualTo(STORE_ID);

            assertThat(resp.id()).isEqualTo(9001L);
            assertThat(resp.occurredAt()).isEqualTo(s.getOccurredAt().atOffset(ZoneOffset.UTC));
            verify(batchMapper, never()).updateOrgIdByIdAndVersion(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("销售使剩余量恰好归零（400 = 600 - 已售 200）：closeNow=true，SALE 事件 soldOut")
        void sellToZero_closes() {
            when(saleMapper.sumSubmittedQuantityByBatchId(BATCH_ID)).thenReturn(new BigDecimal("200.000"));
            batch.setFirstSaleId(9000L);

            service.createSale(BATCH_ID, req("400.000"), KEY, retailer);

            verify(batchMapper).applySale(eq(BATCH_ID), eq(RETAILER_ORG), eq(9001L), eq(true), any(), eq(401L));
            ArgumentCaptor<SaleProjection> projection = ArgumentCaptor.forClass(SaleProjection.class);
            verify(traceEventService).appendSaleEvent(projection.capture(), eq(401L), any());
            assertThat(projection.getValue().soldOut()).isTrue();
            assertThat(projection.getValue().remainingAfter()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("首次销售后仍可继续部分销售（首次销售只锁交接 / 批次操作，不锁销售）")
        void subsequentPartialSale_allowed() {
            batch.setFirstSaleId(9000L);
            when(saleMapper.sumSubmittedQuantityByBatchId(BATCH_ID)).thenReturn(new BigDecimal("100"));
            service.createSale(BATCH_ID, req("100"), KEY, retailer);
            verify(batchMapper).applySale(eq(BATCH_ID), eq(RETAILER_ORG), eq(9001L), eq(false), any(), eq(401L));
        }
    }

    @Nested
    @DisplayName("权限与请求形状（先于任何数据库访问）")
    class Permission {

        @Test
        @DisplayName("平台角色 403 ADMIN_RESTRICTED")
        void platform_restricted() {
            expectFailure(req("1"), principal(RETAILER_ORG, "RETAILER", List.of("OPERATOR"), List.of("PLATFORM")), HttpStatus.FORBIDDEN, "ADMIN_RESTRICTED");
            verifyNoInteractions(batchMapper);
        }

        @Test
        @DisplayName("非 OPERATOR 403 ACCESS_DENIED")
        void qualityManager_denied() {
            expectFailure(req("1"), principal(RETAILER_ORG, "RETAILER", List.of("QUALITY_MANAGER"), List.of("ORG_ONLY")), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        @ParameterizedTest
        @ValueSource(strings = {"PROCESSOR", "SOURCE", "DISTRIBUTOR", "WAREHOUSE", "CARRIER"})
        @DisplayName("非 RETAILER 组织 403 ORG_TYPE_NOT_ALLOWED（企业间流转必须用交接）")
        void nonRetailer_forbidden(String orgType) {
            expectFailure(req("1"), principal(RETAILER_ORG, orgType, List.of("OPERATOR"), List.of("ORG_ONLY")), HttpStatus.FORBIDDEN, "ORG_TYPE_NOT_ALLOWED");
            verifyNoInteractions(batchMapper);
        }

        @ParameterizedTest
        @ValueSource(strings = {"short", "has space in key 1234567"})
        @DisplayName("非法 Idempotency-Key 400")
        void invalidKey(String key) {
            assertThatThrownBy(() -> service.createSale(BATCH_ID, req("1"), key, retailer))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));
            assertThatThrownBy(() -> service.createSale(BATCH_ID, req("1"), null, retailer))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));
            assertNoWrites();
        }

        @Test
        @DisplayName("夹带 remainingQuantity 等未声明字段 400（服务端从不信任客户端剩余量）")
        void unknownField_rejected() {
            SaleCreateRequest r = new SaleCreateRequest(STORE_ID, new BigDecimal("1"), OCCURRED, Map.of("remainingQuantity", 0));
            expectFailure(r, retailer, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "0.000", "-1", "1.0001"})
        @DisplayName("数量必须大于 0 且最多 3 位小数")
        void badQuantity(String qty) {
            expectFailure(req(qty), retailer, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }

        @Test
        @DisplayName("业务时间晚于当前 5 分钟以上 400")
        void futureOccurredAt() {
            expectFailure(new SaleCreateRequest(STORE_ID, BigDecimal.ONE, OffsetDateTime.now(ZoneOffset.UTC).plusHours(1)),
                    retailer, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
    }

    @Nested
    @DisplayName("批次校验先于场所校验")
    class BatchChecks {

        @Test
        @DisplayName("批次不存在 404")
        void missingBatch() {
            when(batchMapper.selectByIdIgnoreTenantForUpdate(BATCH_ID)).thenReturn(null);
            assertThatThrownBy(() -> service.createSale(BATCH_ID, req("1"), KEY, retailer)).isInstanceOf(ResourceNotFoundException.class);
            assertNoWrites();
        }

        @Test
        @DisplayName("他组织批次 403 ORG_SCOPE_DENIED，且在探测任何场所之前拒绝")
        void foreignBatch_beforeSiteLookup() {
            batch.setOrgId(OTHER_ORG);
            when(siteMapper.selectByIdIgnoreTenant(STORE_ID)).thenReturn(null);
            expectFailure(req("1"), retailer, HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED");
            verifyNoInteractions(siteMapper);
        }

        @ParameterizedTest
        @ValueSource(strings = {"DRAFT/NORMAL", "CLOSED/NORMAL", "ACTIVE/FROZEN", "ACTIVE/RECALLED", "CLOSED/RECALLED"})
        @DisplayName("非 ACTIVE+NORMAL 422 BATCH_FLOW_BLOCKED（场所未被探测）")
        void blockedStates(String combo) {
            String[] parts = combo.split("/");
            batch.setFlowStatus(parts[0]);
            batch.setRiskStatus(parts[1]);
            expectFailure(req("1"), retailer, HttpStatus.UNPROCESSABLE_ENTITY, "BATCH_FLOW_BLOCKED");
            verifyNoInteractions(siteMapper);
        }

        @Test
        @DisplayName("已被批次操作全量消耗标记 409 BATCH_ALREADY_CONSUMED")
        void consumedMarker() {
            batch.setConsumedByOperationId(66L);
            expectFailure(req("1"), retailer, HttpStatus.CONFLICT, "BATCH_ALREADY_CONSUMED");
        }

        @Test
        @DisplayName("历史部分 INPUT 用量（无全量消耗标记）409 BATCH_ALREADY_CONSUMED：Sale 不得在部分投入状态上开始")
        void legacyPartialInput() {
            when(itemMapper.sumSubmittedInputQuantityByBatchId(BATCH_ID)).thenReturn(new BigDecimal("100.000"));
            expectFailure(req("1"), retailer, HttpStatus.CONFLICT, "BATCH_ALREADY_CONSUMED");
        }
    }

    @Nested
    @DisplayName("STORE 场所")
    class StoreSite {

        @Test
        @DisplayName("场所不存在 404")
        void missing() {
            when(siteMapper.selectByIdIgnoreTenant(STORE_ID)).thenReturn(null);
            assertThatThrownBy(() -> service.createSale(BATCH_ID, req("1"), KEY, retailer)).isInstanceOf(ResourceNotFoundException.class);
            assertNoWrites();
        }

        @Test
        @DisplayName("他组织门店 403 ORG_SCOPE_DENIED")
        void foreignStore() {
            when(siteMapper.selectByIdIgnoreTenant(STORE_ID)).thenReturn(site(STORE_ID, OTHER_ORG, "STORE", "ACTIVE"));
            expectFailure(req("1"), retailer, HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED");
        }

        @Test
        @DisplayName("停用门店 422 SITE_NOT_ACTIVE")
        void inactiveStore() {
            when(siteMapper.selectByIdIgnoreTenant(STORE_ID)).thenReturn(site(STORE_ID, RETAILER_ORG, "STORE", "INACTIVE"));
            expectFailure(req("1"), retailer, HttpStatus.UNPROCESSABLE_ENTITY, "SITE_NOT_ACTIVE");
        }

        @ParameterizedTest
        @ValueSource(strings = {"FACTORY", "COLD_STORE", "PORT", "LOGISTICS_HUB", "FARM"})
        @DisplayName("非 STORE 场所 422 SALE_SITE_TYPE_INVALID")
        void nonStore(String type) {
            when(siteMapper.selectByIdIgnoreTenant(STORE_ID)).thenReturn(site(STORE_ID, RETAILER_ORG, type, "ACTIVE"));
            expectFailure(req("1"), retailer, HttpStatus.UNPROCESSABLE_ENTITY, "SALE_SITE_TYPE_INVALID");
        }
    }

    @Nested
    @DisplayName("交接、超卖与并发回写")
    class Quantity {

        @Test
        @DisplayName("存在未结束交接 409 BATCH_TRANSFER_OPEN")
        void openTransfer() {
            when(transferMapper.countActiveTransfersByBatchId(BATCH_ID)).thenReturn(1);
            expectFailure(req("1"), retailer, HttpStatus.CONFLICT, "BATCH_TRANSFER_OPEN");
        }

        @Test
        @DisplayName("超卖 422 SALE_QUANTITY_EXCEEDS_REMAINING（已售 500，再售 100.001）")
        void oversell() {
            when(saleMapper.sumSubmittedQuantityByBatchId(BATCH_ID)).thenReturn(new BigDecimal("500"));
            expectFailure(req("100.001"), retailer, HttpStatus.UNPROCESSABLE_ENTITY, "SALE_QUANTITY_EXCEEDS_REMAINING");
        }

        @Test
        @DisplayName("批次回写未命中（并发修改）409 BATCH_CONCURRENT_CONFLICT，事件不写入")
        void applySaleMiss() {
            when(batchMapper.applySale(anyLong(), anyLong(), anyLong(), anyBoolean(), any(), anyLong())).thenReturn(0);
            assertThatThrownBy(() -> service.createSale(BATCH_ID, req("1"), KEY, retailer))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.CONFLICT, "BATCH_CONCURRENT_CONFLICT"));
            verify(traceEventService, never()).appendSaleEvent(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("幂等")
    class Idempotency {

        @Test
        @DisplayName("锁前命中同语义：返回原 Sale，先于一切状态校验（批次已 CLOSED 仍重放），不加锁不写入")
        void replayBeforeStateChecks() {
            batch.setFlowStatus("CLOSED");
            when(saleMapper.selectByOrgIdAndIdempotencyKey(RETAILER_ORG, KEY)).thenReturn(persisted("200", hashOf("200")));

            SaleResponse resp = service.createSale(BATCH_ID, req("200.000"), KEY, retailer);

            assertThat(resp.id()).isEqualTo(9001L);
            verify(batchMapper, never()).selectByIdIgnoreTenantForUpdate(anyLong());
            assertNoWrites();
        }

        @Test
        @DisplayName("同键不同语义 409 IDEMPOTENCY_CONFLICT")
        void conflict() {
            when(saleMapper.selectByOrgIdAndIdempotencyKey(RETAILER_ORG, KEY)).thenReturn(persisted("200", hashOf("200")));
            expectFailure(req("201"), retailer, HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
        }

        @Test
        @DisplayName("锁后复读命中（同键并发的后到者）：重放原 Sale，不写入")
        void replayAfterLock() {
            when(saleMapper.selectByOrgIdAndIdempotencyKey(RETAILER_ORG, KEY)).thenReturn(null, persisted("200", hashOf("200")));
            SaleResponse resp = service.createSale(BATCH_ID, req("200"), KEY, retailer);
            assertThat(resp.id()).isEqualTo(9001L);
            assertNoWrites();
        }

        @Test
        @DisplayName("唯一键冲突后当前读恢复：同语义重放，不同语义 409")
        void duplicateKeyRecovery() {
            when(saleMapper.insert(any(Sale.class))).thenThrow(new DuplicateKeyException("uk_sale_org_idempotency"));
            when(saleMapper.selectByOrgIdAndIdempotencyKeyForUpdate(RETAILER_ORG, KEY)).thenReturn(persisted("200", hashOf("200")));
            assertThat(service.createSale(BATCH_ID, req("200"), KEY, retailer).id()).isEqualTo(9001L);
            assertThatThrownBy(() -> service.createSale(BATCH_ID, req("201"), KEY, retailer))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT"));
            verify(batchMapper, never()).applySale(anyLong(), anyLong(), anyLong(), anyBoolean(), any(), anyLong());
        }

        @Test
        @DisplayName("请求哈希：数量尾随零与等价时区视为同语义；仅亚毫秒（微秒）不同的业务时间视为不同语义")
        void hashPrecision() {
            LocalDateTime t = LocalDateTime.of(2026, 9, 23, 1, 30, 15, 123456000);
            String base = SaleApplicationService.computeRequestHash(BATCH_ID, STORE_ID, new BigDecimal("200"), t);
            assertThat(SaleApplicationService.computeRequestHash(BATCH_ID, STORE_ID, new BigDecimal("200.000"), t)).isEqualTo(base);
            assertThat(SaleApplicationService.computeRequestHash(BATCH_ID, STORE_ID, new BigDecimal("200"), t.plusNanos(1000)))
                    .isNotEqualTo(base);
            assertThat(SaleApplicationService.computeRequestHash(BATCH_ID + 1, STORE_ID, new BigDecimal("200"), t)).isNotEqualTo(base);
            assertThat(SaleApplicationService.computeRequestHash(BATCH_ID, STORE_ID + 1, new BigDecimal("200"), t)).isNotEqualTo(base);
        }

        @Test
        @DisplayName("同键重试仅相差 1 微秒的业务时间 → 409（哈希与落库使用同一微秒精度）")
        void microsecondDifference_conflict() {
            when(saleMapper.selectByOrgIdAndIdempotencyKey(RETAILER_ORG, KEY)).thenReturn(persisted("200", hashOf("200")));
            SaleCreateRequest shifted = new SaleCreateRequest(STORE_ID, new BigDecimal("200"), OCCURRED.plusNanos(1000));
            expectFailure(shifted, retailer, HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
            // 纳秒级（亚微秒）差异在规范化后等价，重放成功
            SaleCreateRequest subMicro = new SaleCreateRequest(STORE_ID, new BigDecimal("200"), OCCURRED.withNano(123456001));
            assertThat(service.createSale(BATCH_ID, subMicro, KEY, retailer).id()).isEqualTo(9001L);
        }
    }

    @Nested
    @DisplayName("查询")
    class ListSales {

        @Test
        @DisplayName("当前责任组织可读，返回门店名；他组织 403；平台只读可读")
        void scope() {
            when(saleMapper.selectByBatchId(BATCH_ID)).thenReturn(List.of(persisted("200", "h")));
            assertThat(service.listSales(BATCH_ID, retailer)).singleElement()
                    .satisfies(s -> assertThat(s.siteName()).isEqualTo("场所" + STORE_ID));
            assertThatThrownBy(() -> service.listSales(BATCH_ID, principal(OTHER_ORG, "PROCESSOR", List.of("OPERATOR"), List.of("ORG_ONLY"))))
                    .satisfies(ex -> assertBusiness(ex, HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED"));
            assertThat(service.listSales(BATCH_ID, principal(1L, "PLATFORM", List.of("SYSTEM_ADMIN"), List.of("PLATFORM")))).hasSize(1);
        }
    }

    @Test
    @DisplayName("持锁后幂等复读每次真正读库：幂等键查询清空会话缓存，不能返回预读时缓存的 null（确定性竞态见 SaleMysqlIntegrationTest）")
    void idempotencyReReadBypassesSessionCache() throws NoSuchMethodException {
        org.apache.ibatis.annotations.Options options = SaleMapper.class
                .getDeclaredMethod("selectByOrgIdAndIdempotencyKey", Long.class, String.class)
                .getAnnotation(org.apache.ibatis.annotations.Options.class);
        assertThat(options).isNotNull();
        assertThat(options.flushCache()).isEqualTo(org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE);
    }
}
