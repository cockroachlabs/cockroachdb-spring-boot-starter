package com.cockroachlabs.data.transaction;

import java.util.ArrayList;
import java.util.List;
import com.cockroachlabs.data.autoconfigure.CockroachDBProperties;
import com.cockroachlabs.data.multiregion.RegionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit regression guard for multi-region row homing. CockroachDB has no {@code SET crdb_region}
 * session variable, so the listener must home REGIONAL BY ROW rows by publishing the region to
 * {@link RegionContext} (where {@code RegionalByRowListener} reads it on {@code @PrePersist} to
 * stamp the {@code @CrdbRegion} column), scoped to the transaction.
 * <p>
 * This runs without a database. The bug it guards against — issuing {@code SET crdb_region} — only
 * failed against a real multi-region cluster, and the earlier unit tests mocked {@code JdbcTemplate}
 * so the invalid statement passed silently. These assertions fail if the listener stops publishing
 * to {@link RegionContext} or fails to restore it on completion.
 */
class CockroachDBRegionsTest {

    private final CockroachDBProperties properties = new CockroachDBProperties();
    private final CockroachDBRegions regions =
            new CockroachDBRegions(properties.getRegion(), null);
    private final TransactionExecution transaction = new SimpleTransactionStatus();

    @AfterEach
    void tearDown() {
        RegionContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishesBoundRegionToRegionContextThenRestoresOnCompletion() {
        TransactionSynchronizationManager.initSynchronization();
        try (CockroachDBRegions.Scope scope = CockroachDBRegions.bind("us-west1")) {
            regions.beforeBegin(transaction);
            regions.afterBegin(transaction, null);

            // Homes the row via the @CrdbRegion column path, not a (non-existent) session SET.
            assertThat(RegionContext.current()).contains("us-west1");

            // Cleanup is tied to THIS transaction, so a pooled thread never leaks a region.
            List<TransactionSynchronization> syncs =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).hasSize(1);
            syncs.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            assertThat(RegionContext.current()).isEmpty();
        }
    }

    @Test
    void suppressedScopeLeavesRegionContextUntouched() {
        TransactionSynchronizationManager.initSynchronization();
        try (CockroachDBRegions.Scope scope = CockroachDBRegions.suppress()) {
            regions.beforeBegin(transaction);
            regions.afterBegin(transaction, null);

            assertThat(RegionContext.current()).isEmpty();
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        }
    }

    @Test
    void noBoundScopeLeavesRegionContextUntouched() {
        TransactionSynchronizationManager.initSynchronization();
        regions.beforeBegin(transaction);
        regions.afterBegin(transaction, null);

        assertThat(RegionContext.current()).isEmpty();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void bindsRegionForRealTransactionAndRestoresAfterCommit() {
        RecordingTransactionManager txManager = new RecordingTransactionManager(regions);
        try (CockroachDBRegions.Scope scope = CockroachDBRegions.bind("us-east1")) {
            TransactionStatus status = txManager.getTransaction(TransactionDefinition.withDefaults());
            assertThat(RegionContext.current()).contains("us-east1");
            txManager.commit(status);
        }
        assertThat(RegionContext.current()).isEmpty();
        assertThat(txManager.events).containsExactly("begin", "commit", "cleanup");
    }

    @Test
    void invalidRegionFailsBeforeTheTransactionBegins() {
        RecordingTransactionManager txManager = new RecordingTransactionManager(regions);
        RegionContext.set("Bad Region'; DROP TABLE x");
        try (CockroachDBRegions.Scope scope = CockroachDBRegions.bind(null)) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> txManager.getTransaction(TransactionDefinition.withDefaults()))
                    .withMessageContaining("Invalid region format");
        }
        // Nothing was begun, so nothing is left bound to the thread.
        assertThat(txManager.events).isEmpty();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(RegionContext.current()).contains("Bad Region'; DROP TABLE x");
    }

    @Test
    void regionOutsideAllowedListFailsBeforeTheTransactionBegins() {
        CockroachDBProperties validating = new CockroachDBProperties();
        validating.getRegion().setValidate(true);
        validating.getRegion().setAllowed(List.of("us-east1"));
        RecordingTransactionManager txManager =
                new RecordingTransactionManager(new CockroachDBRegions(validating.getRegion(), () -> "eu-west1"));
        try (CockroachDBRegions.Scope scope = CockroachDBRegions.bind(null)) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> txManager.getTransaction(TransactionDefinition.withDefaults()))
                    .withMessageContaining("not in allowed list");
        }
        assertThat(txManager.events).isEmpty();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }

    /** A transaction manager with no resources that records what it was asked to do. */
    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private final List<String> events = new ArrayList<>();

        RecordingTransactionManager(CockroachDBRegions regions) {
            addListener(regions);
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            events.add("begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("rollback");
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            events.add("cleanup");
        }
    }
}
