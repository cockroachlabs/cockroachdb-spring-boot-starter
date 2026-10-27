package com.cockroachlabs.data.aop;

import java.util.Map;
import com.cockroachlabs.data.annotation.FollowerRead;
import com.cockroachlabs.data.metrics.CockroachDBMetrics;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;

/**
 * Runs {@link FollowerReadAspect} with JPA (Hibernate + {@link JpaTransactionManager}),
 * where the method under test queries through an {@link EntityManager}. A follower read
 * is at least ~4.8s stale, so a row inserted just before the call must not be visible.
 */
@Testcontainers
class FollowerReadAspectJpaIT {

    @Container
    static final CockroachContainer crdb = new CockroachContainer(
            "cockroachdb/cockroach:v26.3.3");

    static LocalContainerEntityManagerFactoryBean emfBean;
    static EntityManager em;
    static JdbcTemplate jdbc;
    static JpaTransactionManager txManager;
    static Catalog catalog;

    @Entity(name = "JpaItem")
    @Table(name = "jpa_items")
    public static class JpaItem {
        @Id
        Integer id;

        protected JpaItem() {
        }

        JpaItem(Integer id) {
            this.id = id;
        }
    }

    public static class Catalog {
        EntityManager em;

        @FollowerRead
        public Long count() {
            return em.createQuery("select count(i) from JpaItem i", Long.class).getSingleResult();
        }

        @FollowerRead(secondsAgo = 5)
        public Long countFiveSecondsAgo() {
            return em.createQuery("select count(i) from JpaItem i", Long.class).getSingleResult();
        }
    }

    @BeforeAll
    static void setUp() throws InterruptedException {
        var ds = new DriverManagerDataSource(crdb.getJdbcUrl(), crdb.getUsername(), crdb.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE jpa_items (id INT PRIMARY KEY)");

        emfBean = new LocalContainerEntityManagerFactoryBean();
        emfBean.setDataSource(ds);
        emfBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        emfBean.setManagedTypes(PersistenceManagedTypes.of(JpaItem.class.getName()));
        emfBean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
        emfBean.afterPropertiesSet();
        EntityManagerFactory emf = emfBean.getObject();

        txManager = new JpaTransactionManager(emf);
        txManager.afterPropertiesSet();
        em = SharedEntityManagerCreator.createSharedEntityManager(emf);

        // Let the follower-read timestamp move past the table's creation.
        Thread.sleep(8000);

        var target = new Catalog();
        target.em = em;
        var factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new FollowerReadAspect(txManager, jdbc, new CockroachDBMetrics.InMemory()));
        catalog = factory.getProxy();
    }

    @AfterAll
    static void tearDown() {
        emfBean.destroy();
    }

    private static int nextId() {
        return (int) (System.nanoTime() & 0x7fffffff);
    }

    private static void insertRow() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> em.persist(new JpaItem(nextId())));
    }

    private static long currentCount() {
        return jdbc.queryForObject("SELECT count(*) FROM jpa_items", Long.class);
    }

    @Test
    void defaultReadsHistoricalSnapshot() {
        insertRow();
        assertThat(catalog.count()).isLessThan(currentCount());
    }

    @Test
    void asOfIntervalReadsHistoricalSnapshot() {
        insertRow();
        assertThat(catalog.countFiveSecondsAgo()).isLessThan(currentCount());
    }

    @Test
    void nestedInReadWriteTransactionDoesNotJoinIt() {
        long before = currentCount();
        Long staleCount = new TransactionTemplate(txManager).execute(status -> {
            em.persist(new JpaItem(nextId()));
            em.flush();
            Long n = catalog.count();
            em.persist(new JpaItem(nextId()));
            return n;
        });

        // The follower read ran in its own transaction and didn't see the outer writes...
        assertThat(staleCount).isLessThanOrEqualTo(before);
        // ...and the outer transaction stayed read-write and committed both rows.
        assertThat(currentCount()).isEqualTo(before + 2);
    }
}
