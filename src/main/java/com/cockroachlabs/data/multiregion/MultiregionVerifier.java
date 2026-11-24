package com.cockroachlabs.data.multiregion;

import java.util.List;
import com.cockroachlabs.data.autoconfigure.CockroachDBProperties.Multiregion.Mode;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Runs the multi-region check for every {@link EntityManagerFactory} once all singletons,
 * including Flyway and Liquibase migrations, are ready, skipping any whose database isn't
 * CockroachDB. In {@link Mode#WARN} it logs what it finds, unless the report holds a
 * contradiction; in {@link Mode#VALIDATE}, or on a contradiction, it fails startup.
 */
final class MultiregionVerifier implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(MultiregionVerifier.class);

    private final Mode mode;

    private final ObjectProvider<EntityManagerFactory> entityManagerFactories;

    MultiregionVerifier(Mode mode, ObjectProvider<EntityManagerFactory> entityManagerFactories) {
        this.mode = mode;
        this.entityManagerFactories = entityManagerFactories;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (mode == Mode.NONE) {
            return;
        }
        entityManagerFactories.orderedStream().forEach(this::verify);
    }

    void verify(EntityManagerFactory entityManagerFactory) {
        SessionFactoryImplementor sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        List<MultiregionTable> tables = MultiregionEntities.read(sessionFactory);
        if (tables.isEmpty()) {
            return;
        }

        MultiregionReport report;
        try (Session session = sessionFactory.openSession()) {
            MultiregionState state = session.doReturningWork(connection ->
                    MultiregionCheck.isCockroachDB(connection) ? MultiregionCheck.load(connection, tables) : null);
            if (state == null) {
                log.debug("CockroachDB multiregion check ({}) skipped: the database is not CockroachDB", MultiregionReport.SOURCE);
                return;
            }
            report = MultiregionCheck.evaluate(state, tables);
        } catch (RuntimeException ex) {
            if (mode == Mode.VALIDATE) {
                throw new IllegalStateException("CockroachDB multiregion check (" + MultiregionReport.SOURCE + ") could not read the database", ex);
            }
            log.warn("CockroachDB multiregion check ({}) could not read the database: {}", MultiregionReport.SOURCE, ex.getMessage());
            return;
        }

        if (report.isEmpty()) {
            log.debug("CockroachDB multiregion check ({}) passed for {} entities", MultiregionReport.SOURCE, tables.size());
        } else if (mode == Mode.VALIDATE || report.hasContradictions()) {
            throw new IllegalStateException(report.format(mode));
        } else {
            log.warn(report.format(mode));
        }
    }
}
