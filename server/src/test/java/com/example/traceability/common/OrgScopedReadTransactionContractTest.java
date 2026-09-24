package com.example.traceability.common;

import com.example.traceability.batch.application.BatchApplicationService;
import com.example.traceability.batch.application.BatchRiskService;
import com.example.traceability.batch.dto.BatchQueryCriteria;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.sale.application.SaleApplicationService;
import com.example.traceability.trace.application.TraceEventApplicationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 企业端按组织范围读取的事务契约（结构性守卫）。
 * <p>
 * 这些读路径先读取可变的责任组织事实（{@code batch.org_id}）并据此授权，再读取构造响应所需的数据或派生数量；
 * 两者必须属于同一个 InnoDB 一致性快照，因此方法必须显式声明只读 REPEATABLE READ 事务（READ COMMITTED 逐语句取新读视图，
 * 不满足要求；依赖连接 / 服务器默认隔离级别也不满足要求）。方法须为 public 且非 final，才能经 Spring 代理生效。
 * 行为层面的证明见 {@code OrgScopedReadSnapshotMysqlIntegrationTest} 的确定性竞态。
 * </p>
 */
@DisplayName("企业端组织范围读取：显式只读 REPEATABLE READ 快照事务契约")
class OrgScopedReadTransactionContractTest {

    static Stream<Arguments> orgScopedReads() throws NoSuchMethodException {
        return Stream.of(
                Arguments.of(BatchRiskService.class.getMethod("listTransitions", Long.class, TraceSecurityPrincipal.class)),
                Arguments.of(TraceEventApplicationService.class.getMethod("listEvents", Long.class, TraceSecurityPrincipal.class)),
                Arguments.of(SaleApplicationService.class.getMethod("listSales", Long.class, TraceSecurityPrincipal.class)),
                Arguments.of(BatchApplicationService.class.getMethod("getBatchById", Long.class, TraceSecurityPrincipal.class)),
                Arguments.of(BatchApplicationService.class.getMethod("listBatches", BatchQueryCriteria.class, TraceSecurityPrincipal.class))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orgScopedReads")
    @DisplayName("方法显式声明 @Transactional(readOnly = true, isolation = REPEATABLE_READ)，且可被 Spring 代理")
    void declaresReadOnlyRepeatableReadSnapshot(Method method) {
        Transactional tx = method.getAnnotation(Transactional.class);
        assertThat(tx).as("%s must declare @Transactional", method).isNotNull();
        assertThat(tx.readOnly()).as("%s readOnly", method).isTrue();
        assertThat(tx.isolation()).as("%s isolation", method).isEqualTo(Isolation.REPEATABLE_READ);
        assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(method.getModifiers())).isFalse();
        assertThat(Modifier.isFinal(method.getDeclaringClass().getModifiers())).isFalse();
        assertThat(method.getDeclaringClass().getAnnotation(Transactional.class))
                .as("no class-level transaction on %s", method.getDeclaringClass().getSimpleName()).isNull();
    }
}
