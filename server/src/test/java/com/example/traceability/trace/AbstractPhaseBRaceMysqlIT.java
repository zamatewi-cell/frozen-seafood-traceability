package com.example.traceability.trace;

import com.example.traceability.audit.mapper.AuditLogMapper;
import com.example.traceability.batch.mapper.BatchRiskTransitionMapper;
import com.example.traceability.quality.mapper.AlertActionMapper;
import com.example.traceability.quality.mapper.AlertBatchMapper;
import com.example.traceability.quality.mapper.AlertMapper;
import com.example.traceability.quality.mapper.TemperatureRecordMapper;
import com.example.traceability.trace.mapper.ShipmentMapper;
import com.example.traceability.trace.mapper.TransferMapper;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.ClassUtils;
import tools.jackson.databind.JsonNode;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B 确定性竞态共享夹具（仅测试上下文）。
 * <p>
 * {@link GateConfiguration} 注册一个 {@link BeanPostProcessor}，只把 {@link #GATED_MAPPERS} 中的 mapper 包进委托型 JDK 代理，
 * 只拦截布设的方法，且只在被标记的工作线程上生效；其余调用立即委托，生产代码不含任何钩子。另一个请求是否已阻塞在某张表的
 * 行锁上，通过 performance_schema 的锁等待视图确认（同步点），不使用 sleep。具体测试类以
 * {@code @Import(AbstractPhaseBRaceMysqlIT.GateConfiguration.class)} 引入门闩。
 * </p>
 */
abstract class AbstractPhaseBRaceMysqlIT extends AbstractPhaseBMysqlIT {

    /** 可被门闩拦截的 mapper（后续 PB 按需追加）。 */
    static final List<Class<?>> GATED_MAPPERS = new ArrayList<>(List.of(
            TemperatureRecordMapper.class, ShipmentMapper.class, TransferMapper.class, AuditLogMapper.class,
            AlertMapper.class, AlertBatchMapper.class, AlertActionMapper.class, BatchRiskTransitionMapper.class));

    interface AfterRelease {
        void run() throws Exception;
    }

    /** 当前布设的门闩：拦截哪个 mapper 的哪个方法、到达 / 放行同步点，以及放行后在被拦截线程上执行的探针。 */
    record GateSpec(Class<?> mapper, String method, CountDownLatch arrived, CountDownLatch release,
                    AtomicBoolean fired, AfterRelease afterRelease) {
    }

    static final AtomicReference<GateSpec> ARMED = new AtomicReference<>();
    /** 只有被标记的工作线程会被门闩拦截。 */
    static final ThreadLocal<Boolean> GATED_THREAD = new ThreadLocal<>();

    @TestConfiguration
    static class GateConfiguration {

        @Bean
        static BeanPostProcessor phaseBRaceGatePostProcessor() {
            return new GatePostProcessor();
        }
    }

    static final class GatePostProcessor implements BeanPostProcessor {

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof FactoryBean<?>) {
                return bean;
            }
            for (Class<?> mapper : GATED_MAPPERS) {
                if (mapper.isInstance(bean)) {
                    Class<?>[] interfaces = ClassUtils.getAllInterfacesForClass(bean.getClass(), mapper.getClassLoader());
                    return Proxy.newProxyInstance(mapper.getClassLoader(), interfaces, new GateHandler(mapper, bean));
                }
            }
            return bean;
        }
    }

    private static final class GateHandler implements InvocationHandler {

        private final Class<?> mapper;
        private final Object target;

        GateHandler(Class<?> mapper, Object target) {
            this.mapper = mapper;
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "PhaseBRaceGate[" + target + "]";
                    default -> invokeTarget(method, args);
                };
            }
            GateSpec spec = ARMED.get();
            if (spec != null && Boolean.TRUE.equals(GATED_THREAD.get()) && spec.mapper() == mapper
                    && spec.method().equals(method.getName()) && spec.fired().compareAndSet(false, true)) {
                spec.arrived().countDown();
                if (!spec.release().await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("phase B race gate was never released");
                }
                if (spec.afterRelease() != null) {
                    spec.afterRelease().run();
                }
            }
            return invokeTarget(method, args);
        }

        private Object invokeTarget(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    @AfterEach
    void disarmPhaseBGate() {
        ARMED.set(null);
    }

    // =========================================================================
    // 编排辅助
    // =========================================================================

    interface Call {
        MvcResult run() throws Exception;
    }

    interface WhileParked {
        void run() throws Exception;
    }

    final class Running {
        final Thread thread;
        final AtomicReference<MvcResult> result = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();

        Running(String name, boolean gated, Call call) {
            this.thread = new Thread(() -> {
                if (gated) {
                    GATED_THREAD.set(Boolean.TRUE);
                }
                try {
                    result.set(call.run());
                } catch (Throwable t) {
                    error.set(t);
                } finally {
                    GATED_THREAD.remove();
                }
            }, name);
        }

        Running start() {
            thread.start();
            return this;
        }

        MvcResult join() throws Exception {
            thread.join(60_000);
            assertThat(thread.isAlive()).as("race worker %s did not finish", thread.getName()).isFalse();
            if (error.get() != null) {
                throw new AssertionError(thread.getName() + " failed", error.get());
            }
            return result.get();
        }
    }

    /** 未标记的并发请求（会被行锁阻塞，由调用方确认同步点）。 */
    Running background(String name, Call call) {
        return new Running(name, false, call).start();
    }

    /**
     * 被标记的请求 A 在 {@code mapper.method} 调用前停住；主线程执行 {@code whileParked}（启动其他请求并确认同步点），
     * 再放行 A。门闩、ThreadLocal 与工作线程状态在 finally 中清理。
     */
    MvcResult gated(Class<?> mapper, String method, Call a, WhileParked whileParked) throws Exception {
        GateSpec spec = new GateSpec(mapper, method, new CountDownLatch(1), new CountDownLatch(1), new AtomicBoolean(false), null);
        Running running = new Running("phase-b-gated-A", true, a);
        ARMED.set(spec);
        try {
            running.thread.start();
            assertThat(spec.arrived().await(30, TimeUnit.SECONDS))
                    .as("request A reached the gate before %s.%s", mapper.getSimpleName(), method).isTrue();
            whileParked.run();
        } finally {
            spec.release().countDown();
            running.thread.join(60_000);
            ARMED.set(null);
        }
        return running.join();
    }

    static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    String code(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString()).path("code").asString();
    }

    JsonNode data(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("data");
    }
}
