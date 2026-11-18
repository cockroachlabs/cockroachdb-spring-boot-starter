package com.cockroachlabs.data.template;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import com.cockroachlabs.data.autoconfigure.CockroachDBProperties;
import com.cockroachlabs.data.metrics.CockroachDBMetrics;
import com.cockroachlabs.data.multiregion.RegionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link CockroachDBTransactionTemplate} is used like {@link TransactionTemplate}: the same
 * callbacks and transaction settings, plus retry settings and a session region.
 */
class CockroachDBTransactionTemplateTest {

    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus status = mock(TransactionStatus.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CockroachDBProperties properties = new CockroachDBProperties();
    private CockroachDBTransactionTemplate template;

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any())).thenReturn(status);
        properties.getRetry().setDelay(0);
        properties.getRetry().setJitter(0);
        template = new CockroachDBTransactionTemplate(
                txManager, properties, new CockroachDBMetrics.InMemory(), jdbc, null);
    }

    @AfterEach
    void clearRegion() {
        RegionContext.clear();
    }

    private static CannotAcquireLockException conflict() {
        return new CannotAcquireLockException("conflict", new SQLException("simulated", "40001"));
    }

    @Test
    void isATransactionTemplate() {
        assertThat(template).isInstanceOf(TransactionTemplate.class)
                .isInstanceOf(CockroachDBTransactionOperations.class)
                .isInstanceOf(TransactionOperations.class);
        assertThat(template.getTransactionManager()).isSameAs(txManager);
    }

    @Test
    void operationsThroughTheInterfaceAreRetried() {
        CockroachDBTransactionOperations ops = template;
        AtomicInteger attempts = new AtomicInteger();

        String result = ops.execute(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw conflict();
            }
            return "ok";
        });
        ops.executeWithoutResult(() -> {
            if (attempts.incrementAndGet() == 3) {
                throw conflict();
            }
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(4);
    }

    @Test
    void inRegionThroughTheInterfaceSetsTheSessionRegion() {
        CockroachDBTransactionOperations ops = template;

        ops.inRegion("us-east1").executeWithoutResult(() -> { });

        verify(jdbc).execute("SET crdb_region = 'us-east1'");
    }

    @Test
    void defaultsToSerializable() {
        assertThat(template.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
        assertThat(template.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Test
    void transactionCallbackGetsTheStatusAndIsRetried() {
        AtomicInteger attempts = new AtomicInteger();

        String result = template.execute(s -> {
            assertThat(s).isSameAs(status);
            if (attempts.incrementAndGet() == 1) {
                throw conflict();
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void inheritedExecuteWithoutResultIsRetried() {
        AtomicInteger attempts = new AtomicInteger();

        template.executeWithoutResult(s -> {
            if (attempts.incrementAndGet() == 1) {
                throw conflict();
            }
        });

        assertThat(attempts).hasValue(2);
    }

    @Test
    void retrySettingsDefaultToPropertiesAndCanBeOverridden() {
        assertThat(template.getMaxRetries()).isEqualTo(properties.getRetry().getMaxRetries());
        assertThat(template.getMultiplier()).isEqualTo(properties.getRetry().getMultiplier());
        assertThat(template.getMaxDelay()).isEqualTo(properties.getRetry().getMaxDelay());

        template.setMaxRetries(1);
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> template.execute(() -> {
            attempts.incrementAndGet();
            throw conflict();
        })).isInstanceOf(CannotAcquireLockException.class);
        assertThat(attempts).hasValue(2);
    }

    @Test
    void rejectsNegativeRetrySettings() {
        assertThatIllegalArgumentException().isThrownBy(() -> template.setMaxRetries(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> template.setDelay(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> template.setMultiplier(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> template.setMaxDelay(-1));
    }

    @Test
    void copyHasTheSameSettingsAndChangesDoNotLeakBack() {
        template.setTimeout(5);
        template.setReadOnly(true);
        template.setMaxRetries(7);
        template.setRegion("us-east1");

        CockroachDBTransactionTemplate copy = new CockroachDBTransactionTemplate(template);
        assertThat(copy.getTransactionManager()).isSameAs(txManager);
        assertThat(copy.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
        assertThat(copy.getTimeout()).isEqualTo(5);
        assertThat(copy.isReadOnly()).isTrue();
        assertThat(copy.getMaxRetries()).isEqualTo(7);
        assertThat(copy.getRegion()).isEqualTo("us-east1");

        copy.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        copy.setMaxRetries(1);
        copy.setRegion("eu-west1");
        assertThat(template.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
        assertThat(template.getMaxRetries()).isEqualTo(7);
        assertThat(template.getRegion()).isEqualTo("us-east1");
    }

    @Test
    void inRegionReturnsACopyThatSetsTheSessionRegion() {
        CockroachDBTransactionTemplate east = template.inRegion("us-east1");

        east.execute(() -> "ok");

        assertThat(east).isNotSameAs(template);
        assertThat(template.getRegion()).isNull();
        verify(jdbc).execute("SET crdb_region = 'us-east1'");
    }

    @Test
    void explicitRegionWinsOverRegionContext() {
        RegionContext.set("eu-west1");

        template.inRegion("us-east1").execute(() -> "ok");

        verify(jdbc).execute("SET crdb_region = 'us-east1'");
    }

    @Test
    void regionComesFromRegionContextWhenNotSet() {
        RegionContext.set("eu-west1");

        template.execute(() -> "ok");

        verify(jdbc).execute("SET crdb_region = 'eu-west1'");
    }

    @Test
    void rejectsInvalidRegions() {
        assertThatIllegalArgumentException().isThrownBy(() -> template.inRegion("US East"));

        properties.getRegion().setValidate(true);
        properties.getRegion().setAllowed(List.of("us-east1"));
        assertThatIllegalArgumentException().isThrownBy(() -> template.setRegion("eu-west1"));
    }
}
