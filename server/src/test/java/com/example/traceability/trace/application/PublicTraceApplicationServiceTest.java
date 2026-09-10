package com.example.traceability.trace.application;

import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.domain.BatchStatus;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.masterdata.domain.Product;
import com.example.traceability.masterdata.mapper.ProductMapper;
import com.example.traceability.trace.domain.PublicTraceCode;
import com.example.traceability.trace.domain.PublicTraceCodeIdempotency;
import com.example.traceability.trace.domain.PublicTraceCodeStatus;
import com.example.traceability.trace.domain.PublicTraceIdGenerator;
import com.example.traceability.trace.domain.TraceDataMasker;
import com.example.traceability.trace.domain.TraceEvent;
import com.example.traceability.trace.dto.PublicTraceCodeResponse;
import com.example.traceability.trace.dto.PublicTraceProjectionResponse;
import com.example.traceability.trace.mapper.PublicTraceCodeIdempotencyMapper;
import com.example.traceability.trace.mapper.PublicTraceCodeMapper;
import com.example.traceability.trace.mapper.TraceEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 公开追溯码与消费者投影应用服务纯单元测试。
 * <p>
 * 覆盖安全随机 Base32 编码、SHA-256 哈希、数据脱敏掩码、批次状态校验、
 * 组织隔离（禁止窥探他组织实体）、统一幂等绑定与冲突拦截、并发当前读恢复及消费者投影白名单。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("公开追溯码应用服务业务逻辑单元测试")
class PublicTraceApplicationServiceTest {

    @Mock
    private BatchMapper batchMapper;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private TraceEventMapper traceEventMapper;

    @Mock
    private PublicTraceCodeMapper publicTraceCodeMapper;

    @Mock
    private PublicTraceCodeIdempotencyMapper idempotencyMapper;

    @InjectMocks
    private PublicTraceApplicationService service;

    private TraceSecurityPrincipal operatorPrincipal;
    private TraceSecurityPrincipal platformPrincipal;
    private TraceSecurityPrincipal viewerPrincipal;

    @BeforeEach
    void setUp() {
        operatorPrincipal = new TraceSecurityPrincipal(
                101L, "operator1", "操作员", "{noop}pwd",
                201L, "ORG001", "东海捕捞集团", "SOURCE",
                List.of("OPERATOR"), List.of("ORG_ONLY"), true, true
        );
        platformPrincipal = new TraceSecurityPrincipal(
                999L, "sysadmin", "平台管理员", "{noop}pwd",
                1L, "SYS_ORG", "监管平台", "PLATFORM",
                List.of("ADMIN"), List.of("PLATFORM"), true, true
        );
        viewerPrincipal = new TraceSecurityPrincipal(
                102L, "viewer1", "查看员", "{noop}pwd",
                201L, "ORG001", "东海捕捞集团", "SOURCE",
                List.of("VIEWER"), List.of("ORG_ONLY"), true, true
        );
    }

    private static final String VALID_TEST_PUBLIC_ID = "ABCDEF234567ABCDEF234567AB";
    private static final String UNKNOWN_TEST_PUBLIC_ID = "UNKNOWN234567ABCDEF234567A";
    private static final String DISABLED_TEST_PUBLIC_ID = "DISABLED234567ABCDEF234567";

    private String computeHash(String action, Long batchId) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((action + ":" + batchId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("1. 编码生成与脱敏工具测试")
    class UtilityTests {

        @Test
        @DisplayName("PublicTraceIdGenerator 生成 26 位大写 Base32 编码且无内部 ID 派生")
        void testPublicTraceIdGenerator() {
            Set<String> generated = new HashSet<>();
            for (int i = 0; i < 500; i++) {
                String id = PublicTraceIdGenerator.generatePublicId();
                assertThat(id).hasSize(26);
                assertThat(id).matches("^[A-Z2-7]{26}$");
                generated.add(id);

                String hash = PublicTraceIdGenerator.computeTokenHash(id);
                assertThat(hash).hasSize(64);
                assertThat(hash).matches("^[a-f0-9]{64}$");
            }
            // 500 次生成完全唯一无碰撞
            assertThat(generated).hasSize(500);
        }

        @Test
        @DisplayName("TraceDataMasker 对批次号与产地进行安全掩码且不泄露原文")
        void testTraceDataMasker() {
            String rawBatchNo = "BATCH-20260908-001";
            String maskedBatchNo = TraceDataMasker.maskBatchNo(rawBatchNo);
            assertThat(maskedBatchNo).doesNotContain(rawBatchNo);
            assertThat(maskedBatchNo).isEqualTo("BAT****001");

            assertThat(TraceDataMasker.maskBatchNo("ABC")).isEqualTo("****");
            assertThat(TraceDataMasker.maskBatchNo("ABCD1234")).isEqualTo("A****4");

            String rawOrigin = "东海舟山渔场3号捕捞作业区";
            String maskedOrigin = TraceDataMasker.maskOriginText(rawOrigin);
            assertThat(maskedOrigin).doesNotContain(rawOrigin);
            assertThat(maskedOrigin).isEqualTo("东海****业区");

            assertThat(TraceDataMasker.maskOriginText("舟山")).isEqualTo("**");
            assertThat(TraceDataMasker.maskOriginText("舟山渔场")).isEqualTo("舟***场");
        }
    }

    @Nested
    @DisplayName("2. 批次对外公开追溯码激活测试 (POST /activate)")
    class ActivationTests {

        @Test
        @DisplayName("正常首次激活 ACTIVE 批次成功：强制带 org_id 锁定读，持久化码与幂等记录")
        void testActivateSuccess() {
            Long batchId = 1000L;
            String key = "idem-activate-123456";

            Batch batch = createBatch(batchId, 201L, BatchStatus.ACTIVE.name());
            when(idempotencyMapper.selectByOrgIdAndKey(201L, key)).thenReturn(null);
            when(batchMapper.selectByIdAndOrgIdForUpdate(batchId, 201L)).thenReturn(batch);
            when(publicTraceCodeMapper.selectByBatchIdAndOrgId(batchId, 201L)).thenReturn(null);

            PublicTraceCodeResponse resp = service.activatePublicTraceCode(batchId, key, operatorPrincipal);

            assertThat(resp).isNotNull();
            assertThat(resp.batchId()).isEqualTo(batchId);
            assertThat(resp.publicId()).hasSize(26);
            assertThat(resp.status()).isEqualTo("ACTIVE");
            assertThat(resp.disabledAt()).isNull();

            // 验证必须调用带租户的批次排他锁，且绝不得调用无租户实体读取
            verify(batchMapper).selectByIdAndOrgIdForUpdate(batchId, 201L);
            verify(batchMapper, never()).selectByIdIgnoreTenantForUpdate(any());
            verify(publicTraceCodeMapper).selectByBatchIdAndOrgId(batchId, 201L);
            verify(publicTraceCodeMapper).insert(any(PublicTraceCode.class));
            verify(idempotencyMapper).insert(any(PublicTraceCodeIdempotency.class));
        }

        @Test
        @DisplayName("缺少或非法长度幂等键拦截 (400)")
        void testActivateInvalidKey() {
            BusinessException ex1 = assertThrows(BusinessException.class, () ->
                    service.activatePublicTraceCode(1000L, null, operatorPrincipal));
            assertThat(ex1.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);

            BusinessException ex2 = assertThrows(BusinessException.class, () ->
                    service.activatePublicTraceCode(1000L, "short-key", operatorPrincipal));
            assertThat(ex2.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("非 OPERATOR 角色或平台管理用户拒绝写操作 (403 ACCESS_DENIED)")
        void testActivateForbiddenRoles() {
            BusinessException ex1 = assertThrows(BusinessException.class, () ->
                    service.activatePublicTraceCode(1000L, "idem-activate-123456", viewerPrincipal));
            assertThat(ex1.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(ex1.getCode()).isEqualTo("ACCESS_DENIED");

            BusinessException ex2 = assertThrows(BusinessException.class, () ->
                    service.activatePublicTraceCode(1000L, "idem-activate-123456", platformPrincipal));
            assertThat(ex2.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(ex2.getCode()).isEqualTo("ACCESS_DENIED");
        }

        @Test
        @DisplayName("跨组织批次激活拒绝：仅通过组织标量判断 403 ORG_SCOPE_DENIED，绝不装载实体")
        void testActivateCrossOrgForbidden() {
            Long batchId = 1000L;
            String key = "idem-activate-123456";

            when(idempotencyMapper.selectByOrgIdAndKey(201L, key)).thenReturn(null);
            when(batchMapper.selectByIdAndOrgIdForUpdate(batchId, 201L)).thenReturn(null);
            when(batchMapper.selectOrgIdByIdIgnoreTenant(batchId)).thenReturn(999L); // 他组织批次标量

            BusinessException ex = assertThrows(BusinessException.class, () ->
                    service.activatePublicTraceCode(batchId, key, operatorPrincipal));
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(ex.getCode()).isEqualTo("ORG_SCOPE_DENIED");

            verify(batchMapper, never()).selectByIdIgnoreTenantForUpdate(any());
        }

        @Test
        @DisplayName("不存在批次激活抛出 404 RESOURCE_NOT_FOUND")
        void testActivateNotFoundBatch() {
            Long batchId = 9999L;
            String key = "idem-activate-123456";

            when(idempotencyMapper.selectByOrgIdAndKey(201L, key)).thenReturn(null);
            when(batchMapper.selectByIdAndOrgIdForUpdate(batchId, 201L)).thenReturn(null);
            when(batchMapper.selectOrgIdByIdIgnoreTenant(batchId)).thenReturn(null);

            assertThrows(ResourceNotFoundException.class, () ->
                    service.activatePublicTraceCode(batchId, key, operatorPrincipal));

            verify(batchMapper, never()).selectByIdIgnoreTenantForUpdate(any());
        }

        @Test
        @DisplayName("DRAFT / FROZEN / RECALLED / CLOSED 批次首次激活拒绝 (422 BATCH_FLOW_BLOCKED)")
        void testActivateNonActiveBatchRejection() {
            for (String disallowedStatus : List.of("DRAFT", "FROZEN", "RECALLED", "CLOSED")) {
                Long batchId = 1000L;
                String key = "idem-activate-123456";
                Batch batch = createBatch(batchId, 201L, disallowedStatus);

                when(idempotencyMapper.selectByOrgIdAndKey(201L, key)).thenReturn(null);
                when(batchMapper.selectByIdAndOrgIdForUpdate(batchId, 201L)).thenReturn(batch);
                when(publicTraceCodeMapper.selectByBatchIdAndOrgId(batchId, 201L)).thenReturn(null);

                BusinessException ex = assertThrows(BusinessException.class, () ->
                        service.activatePublicTraceCode(batchId, key, operatorPrincipal));
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(ex.getCode()).isEqualTo("BATCH_FLOW_BLOCKED");
            }
        }

        @Test
        @DisplayName("同 key 同语义重放：返回原码且不重复插入")
        void testActivateSameKeyReplay() {
            Long batchId = 1000L;
            String key = "idem-activate-123456";

            PublicTraceCodeIdempotency idem = new PublicTraceCodeIdempotency();
            idem.setOrgId(201L);
            idem.setIdempotencyKey(key);
            idem.setAction("ACTIVATE");
            idem.setBatchId(batchId);
            idem.setRequestHash(computeHash("ACTIVATE", batchId));

            PublicTraceCode existing = createCode(1L, batchId, 201L, VALID_TEST_PUBLIC_ID, "ACTIVE");
            when(idempotencyMapper.selectByOrgIdAndKey(201L, key)).thenReturn(idem);
            when(publicTraceCodeMapper.selectByBatchIdAndOrgId(batchId, 201L)).thenReturn(existing);

            PublicTraceCodeResponse resp = service.activatePublicTraceCode(batchId, key, operatorPrincipal);
            assertThat(resp.publicId()).isEqualTo(VALID_TEST_PUBLIC_ID);
            verify(publicTraceCodeMapper, never()).insert(any(PublicTraceCode.class));
            verify(idempotencyMapper, never()).insert(any(PublicTraceCodeIdempotency.class));
        }

        @Test
        @DisplayName("同 key 已用于停用或用于不同批次激活拒绝 (409 IDEMPOTENCY_KEY_REUSED)")
        void testActivateKeyReusedConflict() {
            Long batchId = 1000L;
            String key = "idem-activate-123456";

            // 1. 已用于停用
            PublicTraceCodeIdempotency disableIdem = new PublicTraceCodeIdempotency();
            disableIdem.setOrgId(201L);
            disableIdem.setIdempotencyKey(key);
            disableIdem.setAction("DISABLE");
            disableIdem.setBatchId(batchId);
            disableIdem.setRequestHash(computeHash("DISABLE", batchId));
            when(idempotencyMapper.selectByOrgIdAndKey(201L, key)).thenReturn(disableIdem);

            BusinessException ex1 = assertThrows(BusinessException.class, () ->
                    service.activatePublicTraceCode(batchId, key, operatorPrincipal));
            assertThat(ex1.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(ex1.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

            // 2. 已用于不同批次激活
            PublicTraceCodeIdempotency diffBatchIdem = new PublicTraceCodeIdempotency();
            diffBatchIdem.setOrgId(201L);
            diffBatchIdem.setIdempotencyKey(key);
            diffBatchIdem.setAction("ACTIVATE");
            diffBatchIdem.setBatchId(2000L); // 不同的批次
            diffBatchIdem.setRequestHash(computeHash("ACTIVATE", 2000L));
            when(idempotencyMapper.selectByOrgIdAndKey(201L, key)).thenReturn(diffBatchIdem);

            BusinessException ex2 = assertThrows(BusinessException.class, () ->
                    service.activatePublicTraceCode(batchId, key, operatorPrincipal));
            assertThat(ex2.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(ex2.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        }

        @Test
        @DisplayName("批次已处于 ACTIVE 码状态时，不同 key 激活属于语义等价调用，稳定返回原码并在同事务内持久绑定该 key")
        void testActivateDifferentKeySemanticallyEquivalentAndBindsKey() {
            Long batchId = 1000L;
            String key = "idem-activate-new-key1";

            Batch batch = createBatch(batchId, 201L, BatchStatus.ACTIVE.name());
            PublicTraceCode existingCode = createCode(1L, batchId, 201L, "EXISTINGPUB12345678901234", "ACTIVE");

            when(idempotencyMapper.selectByOrgIdAndKey(201L, key)).thenReturn(null);
            when(batchMapper.selectByIdAndOrgIdForUpdate(batchId, 201L)).thenReturn(batch);
            when(publicTraceCodeMapper.selectByBatchIdAndOrgId(batchId, 201L)).thenReturn(existingCode);

            PublicTraceCodeResponse resp = service.activatePublicTraceCode(batchId, key, operatorPrincipal);
            assertThat(resp.publicId()).isEqualTo("EXISTINGPUB12345678901234");

            // 必须在同一事务内将该新 key 绑定至统一幂等表！
            verify(idempotencyMapper).insert(any(PublicTraceCodeIdempotency.class));
            verify(publicTraceCodeMapper, never()).insert(any(PublicTraceCode.class));
        }

        @Test
        @DisplayName("批次已处于 DISABLED 终态时再次激活拒绝 (409 INVALID_STATE_TRANSITION)")
        void testActivateOnDisabledCodeRejection() {
            Long batchId = 1000L;
            String key = "idem-activate-new-key1";

            Batch batch = createBatch(batchId, 201L, BatchStatus.ACTIVE.name());
            PublicTraceCode disabledCode = createCode(1L, batchId, 201L, "EXISTINGPUB12345678901234", "DISABLED");

            when(idempotencyMapper.selectByOrgIdAndKey(201L, key)).thenReturn(null);
            when(batchMapper.selectByIdAndOrgIdForUpdate(batchId, 201L)).thenReturn(batch);
            when(publicTraceCodeMapper.selectByBatchIdAndOrgId(batchId, 201L)).thenReturn(disabledCode);

            BusinessException ex = assertThrows(BusinessException.class, () ->
                    service.activatePublicTraceCode(batchId, key, operatorPrincipal));
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(ex.getCode()).isEqualTo("INVALID_STATE_TRANSITION");
        }

        @Test
        @DisplayName("并发同 key 插入幂等表竞争时，捕获 DuplicateKeyException 并通过排他当前锁定读恢复")
        void testConcurrentSameKeyActivationRecoversViaCurrentLockingRead() {
            Long batchId = 1000L;
            String key = "idem-activate-123456";

            Batch batch = createBatch(batchId, 201L, BatchStatus.ACTIVE.name());
            when(idempotencyMapper.selectByOrgIdAndKey(201L, key)).thenReturn(null);
            when(batchMapper.selectByIdAndOrgIdForUpdate(batchId, 201L)).thenReturn(batch);
            when(publicTraceCodeMapper.selectByBatchIdAndOrgId(batchId, 201L)).thenReturn(null);

            PublicTraceCodeIdempotency lockedIdem = new PublicTraceCodeIdempotency();
            lockedIdem.setOrgId(201L);
            lockedIdem.setIdempotencyKey(key);
            lockedIdem.setAction("ACTIVATE");
            lockedIdem.setBatchId(batchId);
            lockedIdem.setRequestHash(computeHash("ACTIVATE", batchId));

            when(idempotencyMapper.insert(any(PublicTraceCodeIdempotency.class)))
                    .thenThrow(new DuplicateKeyException("uk_ptc_idem_org_key"));
            when(idempotencyMapper.selectByOrgIdAndKeyForUpdate(201L, key)).thenReturn(lockedIdem);

            PublicTraceCodeResponse resp = service.activatePublicTraceCode(batchId, key, operatorPrincipal);
            assertThat(resp.publicId()).isNotNull().hasSize(26);
            assertThat(resp.batchId()).isEqualTo(batchId);
            verify(idempotencyMapper).selectByOrgIdAndKeyForUpdate(201L, key);
        }
    }

    @Nested
    @DisplayName("3. 批次对外公开追溯码停用测试 (POST /disable)")
    class DisableTests {

        @Test
        @DisplayName("正常停用批次公开码成功并流转为 DISABLED：强制带 org_id 锁定，持久化绑定停用 key")
        void testDisableSuccess() {
            Long batchId = 1000L;
            String key = "idem-disable-123456";

            Batch batch = createBatch(batchId, 201L, BatchStatus.ACTIVE.name());
            PublicTraceCode code = createCode(1L, batchId, 201L, "PUB1234567890123456789012", "ACTIVE");

            when(idempotencyMapper.selectByOrgIdAndKey(201L, key)).thenReturn(null);
            when(batchMapper.selectByIdAndOrgIdForUpdate(batchId, 201L)).thenReturn(batch);
            when(publicTraceCodeMapper.selectByBatchIdAndOrgIdForUpdate(batchId, 201L)).thenReturn(code);
            when(publicTraceCodeMapper.disableTraceCode(eq(1L), eq(201L), any(), any(), eq(101L))).thenReturn(1);

            PublicTraceCodeResponse resp = service.disablePublicTraceCode(batchId, key, operatorPrincipal);
            assertThat(resp.status()).isEqualTo("DISABLED");
            assertThat(resp.disabledAt()).isNotNull();

            verify(batchMapper).selectByIdAndOrgIdForUpdate(batchId, 201L);
            verify(batchMapper, never()).selectByIdIgnoreTenantForUpdate(any());
            verify(publicTraceCodeMapper).selectByBatchIdAndOrgIdForUpdate(batchId, 201L);
            verify(idempotencyMapper).insert(any(PublicTraceCodeIdempotency.class));
        }

        @Test
        @DisplayName("同 key 再次停用幂等返回原结果")
        void testDisableSameKeyReplay() {
            Long batchId = 1000L;
            String key = "idem-disable-123456";

            PublicTraceCodeIdempotency idem = new PublicTraceCodeIdempotency();
            idem.setOrgId(201L);
            idem.setIdempotencyKey(key);
            idem.setAction("DISABLE");
            idem.setBatchId(batchId);
            idem.setRequestHash(computeHash("DISABLE", batchId));

            PublicTraceCode disabled = createCode(1L, batchId, 201L, DISABLED_TEST_PUBLIC_ID, "DISABLED");
            when(idempotencyMapper.selectByOrgIdAndKey(201L, key)).thenReturn(idem);
            when(publicTraceCodeMapper.selectByBatchIdAndOrgId(batchId, 201L)).thenReturn(disabled);

            PublicTraceCodeResponse resp = service.disablePublicTraceCode(batchId, key, operatorPrincipal);
            assertThat(resp.status()).isEqualTo("DISABLED");
            verify(publicTraceCodeMapper, never()).disableTraceCode(anyLong(), anyLong(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("已处于 DISABLED 终态时使用新 key 停用，成功返回原结果并在同一事务内持久绑定新 key")
        void testDisableOnAlreadyDisabledCodeWithNewKeyBindsKey() {
            Long batchId = 1000L;
            String key = "idem-disable-new-key";

            Batch batch = createBatch(batchId, 201L, BatchStatus.ACTIVE.name());
            PublicTraceCode disabledCode = createCode(1L, batchId, 201L, DISABLED_TEST_PUBLIC_ID, "DISABLED");

            when(idempotencyMapper.selectByOrgIdAndKey(201L, key)).thenReturn(null);
            when(batchMapper.selectByIdAndOrgIdForUpdate(batchId, 201L)).thenReturn(batch);
            when(publicTraceCodeMapper.selectByBatchIdAndOrgIdForUpdate(batchId, 201L)).thenReturn(disabledCode);

            PublicTraceCodeResponse resp = service.disablePublicTraceCode(batchId, key, operatorPrincipal);
            assertThat(resp.status()).isEqualTo("DISABLED");
            verify(idempotencyMapper).insert(any(PublicTraceCodeIdempotency.class));
            verify(publicTraceCodeMapper, never()).disableTraceCode(anyLong(), anyLong(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("同 key 已用于激活或用于不同批次停用冲突拦截 (409 IDEMPOTENCY_KEY_REUSED)")
        void testDisableKeyReusedConflict() {
            Long batchId = 1000L;
            String key = "idem-disable-123456";

            // 1. 已用于激活
            PublicTraceCodeIdempotency actIdem = new PublicTraceCodeIdempotency();
            actIdem.setOrgId(201L);
            actIdem.setIdempotencyKey(key);
            actIdem.setAction("ACTIVATE");
            actIdem.setBatchId(batchId);
            actIdem.setRequestHash(computeHash("ACTIVATE", batchId));
            when(idempotencyMapper.selectByOrgIdAndKey(201L, key)).thenReturn(actIdem);

            BusinessException ex1 = assertThrows(BusinessException.class, () ->
                    service.disablePublicTraceCode(batchId, key, operatorPrincipal));
            assertThat(ex1.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(ex1.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

            // 2. 已用于其他批次停用
            PublicTraceCodeIdempotency diffDisable = new PublicTraceCodeIdempotency();
            diffDisable.setOrgId(201L);
            diffDisable.setIdempotencyKey(key);
            diffDisable.setAction("DISABLE");
            diffDisable.setBatchId(2000L);
            diffDisable.setRequestHash(computeHash("DISABLE", 2000L));
            when(idempotencyMapper.selectByOrgIdAndKey(201L, key)).thenReturn(diffDisable);

            BusinessException ex2 = assertThrows(BusinessException.class, () ->
                    service.disablePublicTraceCode(batchId, key, operatorPrincipal));
            assertThat(ex2.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(ex2.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        }
    }

    @Nested
    @DisplayName("4. 消费者匿名公开追溯信息投影测试 (GET /traces/{publicTraceId})")
    class ConsumerProjectionTests {

        @Test
        @DisplayName("非法 publicTraceId 正则拦截：直接 404 PUBLIC_TRACE_NOT_FOUND 且绝不查询 Mapper")
        void testInvalidPublicTraceIdFormatRejectedWithoutDbQuery() {
            List<String> invalidIds = List.of(
                    "SHORT123",                      // 长度不足 26
                    "ABCDEF234567890ABCDEF2345_EXTRA",// 超过 26
                    "ABCDEF234567890ABCDEF2340",     // 包含非 RFC 4648 Base32 字符 '0'
                    "ABCDEF234567890ABCDEF2341",     // 包含非 RFC 4648 Base32 字符 '1'
                    "ABCDEF234567890ABCDEF2348",     // 包含非 RFC 4648 Base32 字符 '8'
                    "ABCDEF234567890ABCDEF2349",     // 包含非 RFC 4648 Base32 字符 '9'
                    "abcdef234567890abcdef2345",     // 包含小写字母
                    " " + VALID_TEST_PUBLIC_ID,      // 前导空格不得被 trim 后接受
                    VALID_TEST_PUBLIC_ID + " "       // 尾随空格不得被 trim 后接受
            );

            for (String invalidId : invalidIds) {
                BusinessException ex = assertThrows(BusinessException.class, () ->
                        service.getPublicTrace(invalidId));
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(ex.getCode()).isEqualTo("PUBLIC_TRACE_NOT_FOUND");
            }

            // 确保全程绝不触碰 publicTraceCodeMapper
            verifyNoInteractions(publicTraceCodeMapper);
        }

        @Test
        @DisplayName("正常查询有效公开码返回白名单投影，公开 event 仅为受控标签，严禁拼接 summary 等自由文本")
        void testGetPublicTraceSuccessAndNoSummaryLeak() {
            String publicId = VALID_TEST_PUBLIC_ID;
            Long batchId = 1000L;
            String secretSentinel = "CONFIDENTIAL_SENTINEL_SUMMARY_SECRET_987654321";

            PublicTraceCode code = createCode(1L, batchId, 201L, publicId, "ACTIVE");
            Batch batch = createBatch(batchId, 201L, BatchStatus.ACTIVE.name());
            batch.setBatchNo("BATCH-20260908-001");
            batch.setOriginText("东海舟山渔场3号捕捞作业区");
            batch.setProductionDate(LocalDate.of(2026, 9, 1));

            Product product = new Product();
            product.setId(10L);
            product.setPublicName("舟山大黄鱼");
            product.setCategory("FISH");
            product.setSpecification("500g-600g/条");

            TraceEvent event = new TraceEvent();
            event.setId(101L);
            event.setBatchId(batchId);
            event.setEventType("SOURCE");
            event.setOccurredAt(LocalDateTime.of(2026, 9, 1, 8, 0));
            event.setRecordedAt(LocalDateTime.of(2026, 9, 1, 8, 30));
            event.setDataSource("SIMULATED");
            event.setSummary(secretSentinel); // 填入机密哨兵字符串
            event.setStatus("SUBMITTED");

            when(publicTraceCodeMapper.selectByPublicId(publicId)).thenReturn(code);
            when(batchMapper.selectByIdIgnoreTenant(batchId)).thenReturn(batch);
            when(productMapper.selectById(10L)).thenReturn(product);
            when(traceEventMapper.selectEffectiveEventsByBatchId(batchId)).thenReturn(List.of(event));

            PublicTraceProjectionResponse resp = service.getPublicTrace(publicId);

            assertThat(resp).isNotNull();
            assertThat(resp.publicTraceId()).isEqualTo(publicId);
            assertThat(resp.product().name()).isEqualTo("舟山大黄鱼");
            assertThat(resp.batch().publicBatchNo()).isEqualTo("BAT****001");
            assertThat(resp.batch().maskedOrigin()).isEqualTo("东海****业区");
            assertThat(resp.batchStatus()).isEqualTo("ACTIVE");
            assertThat(resp.recallNotice()).isNull();
            assertThat(resp.temperatureSummary().result()).isEqualTo("INSUFFICIENT_DATA");
            assertThat(resp.timeline()).hasSize(1);

            // 验证公开 timeline 中的 event 仅为受控业务标签，绝不包含自由文本 summary！
            PublicTraceProjectionResponse.TimelineItem item = resp.timeline().get(0);
            assertThat(item.event()).isEqualTo("原料采收/出塘");
            assertThat(item.event()).doesNotContain(secretSentinel);
            assertThat(item.dataSourceLabel()).contains("SIMULATED");
        }

        @Test
        @DisplayName("未知码与停用码统一返回 404 PUBLIC_TRACE_NOT_FOUND (外部不可探测)")
        void testUnknownAndDisabledIndistinguishable() {
            // 未知码 (符合 26 位 Base32 正则)
            String unknownId = UNKNOWN_TEST_PUBLIC_ID;
            when(publicTraceCodeMapper.selectByPublicId(unknownId)).thenReturn(null);
            BusinessException ex1 = assertThrows(BusinessException.class, () ->
                    service.getPublicTrace(unknownId));
            assertThat(ex1.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(ex1.getCode()).isEqualTo("PUBLIC_TRACE_NOT_FOUND");

            // 停用码
            String disabledId = DISABLED_TEST_PUBLIC_ID;
            PublicTraceCode disabled = createCode(1L, 1000L, 201L, disabledId, "DISABLED");
            when(publicTraceCodeMapper.selectByPublicId(disabledId)).thenReturn(disabled);
            BusinessException ex2 = assertThrows(BusinessException.class, () ->
                    service.getPublicTrace(disabledId));
            assertThat(ex2.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(ex2.getCode()).isEqualTo("PUBLIC_TRACE_NOT_FOUND");
            assertThat(ex2.getMessage()).isEqualTo(ex1.getMessage());
            assertThat(ex2.getTitle()).isEqualTo(ex1.getTitle());
        }

        @Test
        @DisplayName("批次处于 RECALLED 状态时如实返回 200 并携带模拟召回声明")
        void testRecalledBatchNotice() {
            String publicId = VALID_TEST_PUBLIC_ID;
            Long batchId = 1000L;

            PublicTraceCode code = createCode(1L, batchId, 201L, publicId, "ACTIVE");
            Batch batch = createBatch(batchId, 201L, BatchStatus.RECALLED.name());

            when(publicTraceCodeMapper.selectByPublicId(publicId)).thenReturn(code);
            when(batchMapper.selectByIdIgnoreTenant(batchId)).thenReturn(batch);
            when(productMapper.selectById(anyLong())).thenReturn(null);
            when(traceEventMapper.selectEffectiveEventsByBatchId(batchId)).thenReturn(List.of());

            PublicTraceProjectionResponse resp = service.getPublicTrace(publicId);
            assertThat(resp.batchStatus()).isEqualTo("RECALLED");
            assertThat(resp.recallNotice()).contains("模拟召回演练");
        }
    }

    private Batch createBatch(Long batchId, Long orgId, String status) {
        Batch batch = new Batch();
        batch.setId(batchId);
        batch.setOrgId(orgId);
        batch.setProductId(10L);
        batch.setBatchNo("BATCH-TEST-001");
        batch.setBatchType("SOURCE");
        batch.setStatus(status);
        batch.setIsDeleted(0);
        return batch;
    }

    private PublicTraceCode createCode(
            Long id, Long batchId, Long orgId, String publicId, String status
    ) {
        PublicTraceCode code = new PublicTraceCode();
        code.setId(id);
        code.setBatchId(batchId);
        code.setOrgId(orgId);
        code.setPublicId(publicId);
        code.setTokenHash("a".repeat(64));
        code.setStatus(status);
        code.setActivatedAt(LocalDateTime.now(ZoneOffset.UTC));
        code.setDisabledAt("DISABLED".equals(status) ? LocalDateTime.now(ZoneOffset.UTC) : null);
        code.setVersion(0L);
        code.setIsDeleted(0);
        code.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        code.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return code;
    }
}
