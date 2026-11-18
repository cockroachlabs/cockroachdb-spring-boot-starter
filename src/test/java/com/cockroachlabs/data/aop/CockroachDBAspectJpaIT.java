package com.cockroachlabs.data.aop;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import com.cockroachlabs.data.annotation.CockroachDB;
import com.cockroachlabs.data.autoconfigure.CockroachDBProperties;
import com.cockroachlabs.data.metrics.CockroachDBMetrics;
import com.cockroachlabs.data.template.CockroachDBTransactionTemplate;
import com.cockroachlabs.data.test.CockroachDBConcurrencyRunner;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;

/**
 * Runs {@link CockroachDBAspect} with JPA (Hibernate + {@link JpaTransactionManager}) under
 * real contention. Hibernate surfaces SQLSTATE 40001 as {@code JpaSystemException} or
 * {@code TransactionSystemException} rather than a {@code TransientDataAccessException};
 * the aspect must still retry it.
 */
@Testcontainers
class CockroachDBAspectJpaIT {

    @Container
    static final CockroachContainer crdb = new CockroachContainer(
            "cockroachdb/cockroach:v26.3.3");

    static LocalContainerEntityManagerFactoryBean emfBean;
    static JdbcTemplate jdbc;
    static CockroachDBMetrics.InMemory metrics;
    static Bank bank;

    @Entity(name = "JpaAccount")
    @Table(name = "jpa_accounts")
    public static class JpaAccount {
        @Id
        Integer id;

        BigDecimal balance;

        protected JpaAccount() {
        }
    }

    public static class Bank {
        EntityManager em;

        @CockroachDB(maxRetries = 20)
        public void transfer(int fromId, int toId) {
            JpaAccount from = em.find(JpaAccount.class, fromId);
            JpaAccount to = em.find(JpaAccount.class, toId);
            from.balance = from.balance.subtract(BigDecimal.ONE);
            to.balance = to.balance.add(BigDecimal.ONE);
        }
    }

    @BeforeAll
    static void setUp() {
        var ds = new DriverManagerDataSource(crdb.getJdbcUrl(), crdb.getUsername(), crdb.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE jpa_accounts (id INT PRIMARY KEY, balance DECIMAL(12, 2) NOT NULL)");

        emfBean = new LocalContainerEntityManagerFactoryBean();
        emfBean.setDataSource(ds);
        emfBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        emfBean.setManagedTypes(PersistenceManagedTypes.of(JpaAccount.class.getName()));
        emfBean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
        emfBean.afterPropertiesSet();
        EntityManagerFactory emf = emfBean.getObject();

        JpaTransactionManager txManager = new JpaTransactionManager(emf);
        txManager.afterPropertiesSet();

        CockroachDBProperties properties = new CockroachDBProperties();
        properties.getRetry().setDelay(10);
        properties.getRetry().setJitter(10);
        metrics = new CockroachDBMetrics.InMemory();

        var target = new Bank();
        target.em = SharedEntityManagerCreator.createSharedEntityManager(emf);
        var factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new CockroachDBAspect(
                new CockroachDBTransactionTemplate(txManager, properties, metrics, jdbc, null)));
        bank = factory.getProxy();
    }

    @AfterAll
    static void tearDown() {
        emfBean.destroy();
    }

    @BeforeEach
    void resetAccounts() {
        jdbc.execute("UPSERT INTO jpa_accounts (id, balance) VALUES (1, 1000.00), (2, 1000.00)");
    }

    private static BigDecimal totalBalance() {
        return jdbc.queryForObject("SELECT sum(balance) FROM jpa_accounts", BigDecimal.class);
    }

    @Test
    void concurrentJpaTransfersAreRetriedUntilTheySucceed() {
        CockroachDBConcurrencyRunner.Result result = CockroachDBConcurrencyRunner.run(8, 25, () -> {
            if (ThreadLocalRandom.current().nextBoolean()) {
                bank.transfer(1, 2);
            } else {
                bank.transfer(2, 1);
            }
        });

        assertThat(result.errors()).isEmpty();
        assertThat(result.successes()).isEqualTo(200);
        assertThat(totalBalance()).isEqualByComparingTo("2000.00");
        // Without retries actually happening, this test would prove nothing.
        assertThat(metrics.getRetryCount()).isPositive();
        assertThat(metrics.getFailureCount()).isZero();
    }
}
