package com.cockroachlabs.data.multiregion;

import com.cockroachlabs.data.autoconfigure.CockroachDBProperties;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Checks at startup that {@code @GlobalTable} and {@code @RegionalByRow} annotations agree with
 * the multi-region configuration of their tables and database. On by default in
 * {@code warn} mode; {@code cockroachdb.multiregion.mode=none} turns it off.
 */
@AutoConfiguration(afterName = "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration")
@ConditionalOnClass({EntityManagerFactory.class, SessionFactoryImplementor.class})
@ConditionalOnProperty(prefix = "cockroachdb.multiregion", name = "mode", matchIfMissing = true)
@EnableConfigurationProperties(CockroachDBProperties.class)
public class CockroachDBMultiregionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MultiregionVerifier cockroachDBMultiregionVerifier(
            CockroachDBProperties properties, ObjectProvider<EntityManagerFactory> entityManagerFactories) {
        return new MultiregionVerifier(properties.getMultiregion().getMode(), entityManagerFactories);
    }
}
