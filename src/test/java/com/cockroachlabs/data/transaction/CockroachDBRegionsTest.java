package com.cockroachlabs.data.transaction;

import java.util.List;
import com.cockroachlabs.data.autoconfigure.CockroachDBProperties;
import com.cockroachlabs.data.multiregion.RegionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

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
            regions.afterBegin(null, null);

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
            regions.afterBegin(null, null);

            assertThat(RegionContext.current()).isEmpty();
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        }
    }

    @Test
    void noBoundScopeLeavesRegionContextUntouched() {
        TransactionSynchronizationManager.initSynchronization();
        regions.afterBegin(null, null);

        assertThat(RegionContext.current()).isEmpty();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }
}
