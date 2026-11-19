package com.cockroachlabs.data.transaction;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import com.cockroachlabs.data.annotation.CockroachDBTransactional;
import com.cockroachlabs.data.autoconfigure.CockroachDBProperties;
import com.cockroachlabs.data.metrics.CockroachDBMetrics;
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
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;

/**
 * Retries under JPA (Hibernate + {@link JpaTransactionManager}) and real contention, where
 * CockroachDB's SQLSTATE 40001 surfaces as {@code JpaSystemException} or
 * {@code TransactionSystemException}. The bank is proxied the way an application context
 * would: the starter's retry advisor outside Spring's own transaction interceptor.
 */
@Testcontainers
class CockroachDBTransactionalJpaIT {

    @Container
    static final CockroachContainer crdb = new CockroachContainer("cockroachdb/cockroach:v26.3.3");

    static LocalContainerEntityManagerFactoryBean emfBean;
    static JdbcTemplate jdbc;
    static JpaTransactionManager txManager;
    static EntityManager em;

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

        @CockroachDBTransactional(maxRetries = 20)
        public void annotatedTransfer(int fromId, int toId) {
            move(fromId, toId);
        }

        @Transactional
        public void plainTransfer(int fromId, int toId) {
            move(fromId, toId);
        }

        private void move(int fromId, int toId) {
            JpaAccount from = em.find(JpaAccount.class, fromId);
            JpaAccount to = em.find(JpaAccount.class, toId);
            from.balance = from.balance.subtract(BigDecimal.ONE);
            to.balance = to.balance.add(BigDecimal.ONE);
        }
    }

    /** A bank proxied with the starter's retry advisor around Spring's transaction interceptor. */
    record Setup(Bank bank, CockroachDBMetrics.InMemory metrics) {
    }

    static Setup bank(boolean applyToTransactionalMethods) {
        CockroachDBProperties properties = new CockroachDBProperties();
        properties.getRetry().setMaxRetries(20);
        properties.getRetry().setDelay(10);
        properties.getRetry().setJitter(10);
        CockroachDBMetrics.InMemory metrics = new CockroachDBMetrics.InMemory();

        AnnotationTransactionAttributeSource attributes = new AnnotationTransactionAttributeSource();
        CockroachDBTransactionInterceptor retries = new CockroachDBTransactionInterceptor(
                new SerializationRetry(properties.getRetry(), metrics),
                new CockroachDBRegions(properties.getRegion(), null),
                applyToTransactionalMethods);

        Bank target = new Bank();
        target.em = em;
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvisor(new CockroachDBTransactionAdvisor(attributes, retries));
        factory.addAdvice(new TransactionInterceptor(txManager, attributes));
        return new Setup((Bank) factory.getProxy(), metrics);
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

        txManager = new JpaTransactionManager(emf);
        txManager.afterPropertiesSet();
        em = SharedEntityManagerCreator.createSharedEntityManager(emf);
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

    private static CockroachDBConcurrencyRunner.Result transfers(Runnable oneToTwo, Runnable twoToOne) {
        return CockroachDBConcurrencyRunner.run(8, 25, () -> {
            if (ThreadLocalRandom.current().nextBoolean()) {
                oneToTwo.run();
            } else {
                twoToOne.run();
            }
        });
    }

    @Test
    void cockroachDBTransactionalTransfersAreRetriedUntilTheySucceed() {
        Setup setup = bank(true);

        var result = transfers(() -> setup.bank().annotatedTransfer(1, 2), () -> setup.bank().annotatedTransfer(2, 1));

        assertThat(result.errors()).isEmpty();
        assertThat(result.successes()).isEqualTo(200);
        assertThat(totalBalance()).isEqualByComparingTo("2000.00");
        // Without retries actually happening, this test would prove nothing.
        assertThat(setup.metrics().getRetryCount()).isPositive();
    }

    @Test
    void plainTransactionalTransfersAreRetriedToo() {
        Setup setup = bank(true);

        var result = transfers(() -> setup.bank().plainTransfer(1, 2), () -> setup.bank().plainTransfer(2, 1));

        assertThat(result.errors()).isEmpty();
        assertThat(result.successes()).isEqualTo(200);
        assertThat(totalBalance()).isEqualByComparingTo("2000.00");
        assertThat(setup.metrics().getRetryCount()).isPositive();
    }

    @Test
    void plainTransactionalFailsWithoutRetries() {
        Setup setup = bank(false);

        var result = transfers(() -> setup.bank().plainTransfer(1, 2), () -> setup.bank().plainTransfer(2, 1));

        // Hibernate reports CockroachDB's 40001 as a JpaSystemException; nothing retries it.
        assertThat(result.errors()).isNotEmpty()
                .allSatisfy(e -> assertThat(SerializationRetry.isSerializationError(e)).isTrue())
                .anySatisfy(e -> assertThat(e).isInstanceOf(JpaSystemException.class));
        assertThat(totalBalance()).isEqualByComparingTo("2000.00");
    }
}
