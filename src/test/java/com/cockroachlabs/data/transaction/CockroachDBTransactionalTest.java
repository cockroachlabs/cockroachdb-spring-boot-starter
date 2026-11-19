package com.cockroachlabs.data.transaction;

import java.io.IOException;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import com.cockroachlabs.data.annotation.CockroachDBTransactional;
import com.cockroachlabs.data.annotation.EnableCockroachDBTransactionManagement;
import com.cockroachlabs.data.autoconfigure.CockroachDBAutoConfiguration;
import com.cockroachlabs.data.metrics.CockroachDBMetrics;
import com.cockroachlabs.data.multiregion.RegionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.config.TransactionManagementConfigUtils;

import static org.assertj.core.api.Assertions.*;

/**
 * Retries and session regions for {@code @Transactional} and {@code @CockroachDBTransactional}
 * methods, through a real Spring context: Spring's own transaction advisor, the starter's
 * auto-configuration, and a {@code DataSourceTransactionManager} over a mocked JDBC driver.
 */
class CockroachDBTransactionalTest {

    private final MockJdbc jdbc = new MockJdbc();
    private AnnotationConfigApplicationContext context;

    @AfterEach
    void close() {
        RegionContext.clear();
        if (context != null) {
            context.close();
        }
    }

    private AnnotationConfigApplicationContext start(String... properties) {
        return start(Config.class, properties);
    }

    private AnnotationConfigApplicationContext start(Class<?> config, String... properties) {
        context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of("cockroachdb.retry.delay=0", "cockroachdb.retry.jitter=0")
                .and(properties)
                .applyTo(context);
        context.registerBean(DataSource.class, () -> jdbc.dataSource);
        context.register(config);
        context.refresh();
        return context;
    }

    // ---- application code under test -------------------------------------------------

    /** Fails the next {@code remaining} calls with a 40001. */
    public static class Conflicts {
        final AtomicInteger attempts = new AtomicInteger();
        int remaining;

        void attempt() {
            attempts.incrementAndGet();
            if (remaining-- > 0) {
                throw MockJdbc.serializationFailure();
            }
        }
    }

    public static class Bank {
        final Conflicts conflicts;
        // The region visible via RegionContext inside each transaction attempt, in order.
        // Read through a method: fields of a CGLIB proxy are never initialized.
        final List<String> regionsSeen = new CopyOnWriteArrayList<>();

        public Bank(Conflicts conflicts) {
            this.conflicts = conflicts;
        }

        @Transactional
        public String plain() {
            regionsSeen.add(RegionContext.current().orElse(null));
            conflicts.attempt();
            return "ok";
        }

        @CockroachDBTransactional
        public String annotated() {
            conflicts.attempt();
            return "ok";
        }

        @CockroachDBTransactional(maxRetries = 1)
        public String oneRetry() {
            conflicts.attempt();
            return "ok";
        }

        @CockroachDBTransactional(isolation = Isolation.READ_COMMITTED)
        public String readCommitted() {
            return "ok";
        }

        @CockroachDBTransactional(region = "us-east1")
        public String inRegion() {
            regionsSeen.add(RegionContext.current().orElse(null));
            return "ok";
        }

        @Transactional
        public void bug() {
            conflicts.attempt();
            throw new IllegalStateException("bug");
        }

        @Transactional
        public void checked() throws IOException {
            conflicts.attempt();
            throw new IOException("checked");
        }

        public List<String> regionsSeen() {
            return regionsSeen;
        }
    }

    @Transactional
    public static class ClassLevel {
        final Conflicts conflicts;

        public ClassLevel(Conflicts conflicts) {
            this.conflicts = conflicts;
        }

        public String call() {
            conflicts.attempt();
            return "ok";
        }
    }

    public interface Api {
        @Transactional
        String call();
    }

    public static class ApiImpl implements Api {
        final Conflicts conflicts;

        public ApiImpl(Conflicts conflicts) {
            this.conflicts = conflicts;
        }

        @Override
        public String call() {
            conflicts.attempt();
            return "ok";
        }
    }

    public static class Inner {
        final Conflicts conflicts;

        public Inner(Conflicts conflicts) {
            this.conflicts = conflicts;
        }

        @Transactional
        public void alwaysConflicts() {
            conflicts.attempt();
            throw MockJdbc.serializationFailure();
        }
    }

    public static class Outer {
        final Inner inner;
        final AtomicInteger attempts = new AtomicInteger();

        public Outer(Inner inner) {
            this.inner = inner;
        }

        @Transactional
        public void run() {
            attempts.incrementAndGet();
            inner.alwaysConflicts();
        }

        // Read through a method: fields of a CGLIB proxy are never initialized.
        public int attempts() {
            return attempts.get();
        }
    }

    /** Spring's transaction management with its default order, as Spring Boot sets it up. */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import({CockroachDBAutoConfiguration.class, Beans.class})
    static class Config {
    }

    /** No annotation-driven transaction management: @Transactional does nothing here. */
    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import({CockroachDBAutoConfiguration.class, Beans.class})
    static class WithoutTransactionManagement {
    }

    /** Spring's transaction advisor ordered outside the retry advisor. */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(order = Ordered.HIGHEST_PRECEDENCE)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import({CockroachDBAutoConfiguration.class, Beans.class})
    static class TransactionAdvisorOutside {
    }

    /** The application declares the annotation itself, with its own order. */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableCockroachDBTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 10)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import({CockroachDBAutoConfiguration.class, Beans.class})
    static class ExplicitlyEnabled {
    }

    /** The retry advisor ordered at Spring's transaction advisor, so not outside it. */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableCockroachDBTransactionManagement(order = Ordered.LOWEST_PRECEDENCE)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import({CockroachDBAutoConfiguration.class, Beans.class})
    static class RetryAdvisorInside {
    }

    /** Explicitly enabled, but without Spring's transaction management. */
    @Configuration(proxyBeanMethods = false)
    @EnableCockroachDBTransactionManagement
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import({CockroachDBAutoConfiguration.class, Beans.class})
    static class ExplicitlyEnabledWithoutTransactionManagement {
    }

    @Configuration(proxyBeanMethods = false)
    static class Beans {

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        Conflicts conflicts() {
            return new Conflicts();
        }

        @Bean
        Bank bank(Conflicts conflicts) {
            return new Bank(conflicts);
        }

        @Bean
        ClassLevel classLevel(Conflicts conflicts) {
            return new ClassLevel(conflicts);
        }

        @Bean
        ApiImpl api(Conflicts conflicts) {
            return new ApiImpl(conflicts);
        }

        @Bean
        Inner inner(Conflicts conflicts) {
            return new Inner(conflicts);
        }

        @Bean
        Outer outer(Inner inner) {
            return new Outer(inner);
        }
    }

    private Conflicts conflicts() {
        return context.getBean(Conflicts.class);
    }

    // ---- retries ------------------------------------------------------------------

    @Test
    void plainTransactionalMethodIsRetried() {
        start();
        conflicts().remaining = 2;

        assertThat(context.getBean(Bank.class).plain()).isEqualTo("ok");

        assertThat(conflicts().attempts).hasValue(3);
        assertThat(jdbc.rollbacks()).isEqualTo(2);
        assertThat(jdbc.commits()).isEqualTo(1);
        assertThat(context.getBean(CockroachDBMetrics.class).getRetryCount()).isEqualTo(2);
    }

    @Test
    void classLevelTransactionalIsRetried() {
        start();
        conflicts().remaining = 1;

        assertThat(context.getBean(ClassLevel.class).call()).isEqualTo("ok");
        assertThat(conflicts().attempts).hasValue(2);
    }

    @Test
    void interfaceLevelTransactionalIsRetried() {
        start();
        conflicts().remaining = 1;

        assertThat(context.getBean(Api.class).call()).isEqualTo("ok");
        assertThat(conflicts().attempts).hasValue(2);
    }

    @Test
    void cockroachDBTransactionalIsRetried() {
        start();
        conflicts().remaining = 2;

        assertThat(context.getBean(Bank.class).annotated()).isEqualTo("ok");
        assertThat(conflicts().attempts).hasValue(3);
    }

    @Test
    void annotationOverridesMaxRetries() {
        start();
        conflicts().remaining = Integer.MAX_VALUE;

        assertThatExceptionOfType(CannotAcquireLockException.class)
                .isThrownBy(() -> context.getBean(Bank.class).oneRetry());
        assertThat(conflicts().attempts).hasValue(2);
        assertThat(context.getBean(CockroachDBMetrics.class).getFailureCount()).isEqualTo(1);
    }

    @Test
    void stopsAtMaxRetriesFromProperties() {
        start("cockroachdb.retry.max-retries=4");
        conflicts().remaining = Integer.MAX_VALUE;

        assertThatExceptionOfType(CannotAcquireLockException.class)
                .isThrownBy(() -> context.getBean(Bank.class).plain());
        assertThat(conflicts().attempts).hasValue(5);
    }

    @Test
    void propertyTurnsOffRetriesForPlainTransactionalOnly() {
        start("cockroachdb.retry.apply-to-transactional-methods=false");
        Bank bank = context.getBean(Bank.class);

        conflicts().remaining = 1;
        assertThatExceptionOfType(CannotAcquireLockException.class).isThrownBy(bank::plain);
        assertThat(conflicts().attempts).hasValue(1);

        conflicts().remaining = 1;
        assertThat(bank.annotated()).isEqualTo("ok");
        assertThat(conflicts().attempts).hasValue(3);
    }

    @Test
    void otherExceptionsAreNotRetried() {
        start();

        assertThatIllegalStateException().isThrownBy(() -> context.getBean(Bank.class).bug());
        assertThat(conflicts().attempts).hasValue(1);
        assertThat(jdbc.rollbacks()).isEqualTo(1);
    }

    @Test
    void checkedExceptionsKeepTransactionalSemantics() {
        start();

        assertThatThrownBy(() -> context.getBean(Bank.class).checked())
                .isExactlyInstanceOf(IOException.class);
        assertThat(conflicts().attempts).hasValue(1);
        // @Transactional commits on checked exceptions by default.
        assertThat(jdbc.commits()).isEqualTo(1);
    }

    @Test
    void onlyTheOutermostTransactionalCallRetries() {
        start("cockroachdb.retry.max-retries=3");

        assertThatExceptionOfType(CannotAcquireLockException.class)
                .isThrownBy(() -> context.getBean(Outer.class).run());

        // The outer call ran 4 times (1 + 3 retries); the inner call ran once per outer attempt.
        assertThat(context.getBean(Outer.class).attempts()).isEqualTo(4);
        assertThat(conflicts().attempts).hasValue(4);
    }

    // ---- working with Spring's transaction advisor -------------------------------------

    @Test
    void retryAdvisorWrapsSpringsTransactionAdvisor() {
        start();

        assertThat(context.getBeansOfType(CockroachDBTransactionAdvisor.class)).hasSize(1);
        assertThat(context.containsBean(TransactionManagementConfigUtils.TRANSACTION_ADVISOR_BEAN_NAME)).isTrue();
    }

    @Test
    void noRetryAdvisorWithoutSpringsTransactionManagement() {
        start(WithoutTransactionManagement.class);
        conflicts().remaining = 1;

        assertThat(context.getBeansOfType(CockroachDBTransactionAdvisor.class)).isEmpty();
        assertThatExceptionOfType(CannotAcquireLockException.class)
                .isThrownBy(() -> context.getBean(Bank.class).plain());
        assertThat(conflicts().attempts).hasValue(1);
    }

    @Test
    void startupFailsWhenSpringsTransactionAdvisorIsOrderedOutside() {
        assertThatThrownBy(() -> start(TransactionAdvisorOutside.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must run outside it")
                .hasMessageContaining("@EnableTransactionManagement");
    }

    @Test
    void explicitAnnotationReplacesTheAutoConfiguredOne() {
        start(ExplicitlyEnabled.class);
        conflicts().remaining = 1;

        assertThat(context.getBeansOfType(CockroachDBTransactionAdvisor.class).values())
                .singleElement()
                .satisfies(advisor -> assertThat(advisor.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE - 10));
        assertThat(context.getBean(Bank.class).plain()).isEqualTo("ok");
        assertThat(conflicts().attempts).hasValue(2);
    }

    @Test
    void startupFailsWhenTheRetryAdvisorIsNotOutsideSpringsAdvisor() {
        assertThatThrownBy(() -> start(RetryAdvisorInside.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must run outside it");
    }

    @Test
    void explicitAnnotationNeedsSpringsTransactionManagement() {
        assertThatThrownBy(() -> start(ExplicitlyEnabledWithoutTransactionManagement.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needs Spring's annotation-driven transaction management");
    }

    // ---- transaction attributes ------------------------------------------------------

    @Test
    void cockroachDBTransactionalDefaultsToSerializable() {
        start();

        context.getBean(Bank.class).annotated();
        context.getBean(Bank.class).readCommitted();

        // The transaction manager restores the connection's previous level after each
        // transaction, so the levels it set are interleaved with resets.
        assertThat(jdbc.isolationLevels())
                .containsSubsequence(Connection.TRANSACTION_SERIALIZABLE, Connection.TRANSACTION_READ_COMMITTED);
    }

    // ---- session region ------------------------------------------------------------

    @Test
    void annotationRegionIsSetAtTheStartOfTheTransaction() {
        start();

        context.getBean(Bank.class).inRegion();

        assertThat(context.getBean(Bank.class).regionsSeen()).containsExactly("us-east1");
    }

    @Test
    void plainTransactionalUsesTheRegionChain() {
        start();
        RegionContext.set("eu-west1");

        context.getBean(Bank.class).plain();

        assertThat(context.getBean(Bank.class).regionsSeen()).containsExactly("eu-west1");
    }

    @Test
    void defaultRegionPropertyIsTheLastFallback() {
        start("cockroachdb.region.default-region=ap-south1");

        context.getBean(Bank.class).plain();

        assertThat(context.getBean(Bank.class).regionsSeen()).containsExactly("ap-south1");
    }

    @Test
    void noRegionMeansNoSet() {
        start();

        context.getBean(Bank.class).plain();

        assertThat(context.getBean(Bank.class).regionsSeen()).containsExactly((String) null);
        assertThat(jdbc.executedSql()).isEmpty();
    }

    @Test
    void everyRetryGetsTheRegion() {
        start();
        RegionContext.set("eu-west1");
        conflicts().remaining = 2;

        context.getBean(Bank.class).plain();

        assertThat(context.getBean(Bank.class).regionsSeen())
                .containsExactly("eu-west1", "eu-west1", "eu-west1");
    }
}
