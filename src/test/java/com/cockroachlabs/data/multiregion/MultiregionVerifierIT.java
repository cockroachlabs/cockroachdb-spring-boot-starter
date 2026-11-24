package com.cockroachlabs.data.multiregion;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.sql.DataSource;
import com.cockroachlabs.data.annotation.CrdbRegion;
import com.cockroachlabs.data.annotation.GlobalTable;
import com.cockroachlabs.data.annotation.RegionalByRow;
import com.cockroachlabs.data.autoconfigure.CockroachDBProperties.Multiregion.Mode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Table;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs the multi-region check against real CockroachDB: one node started with a region
 * locality (so databases can be made multi-region) and one without.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class MultiregionVerifierIT {

    private static final String IMAGE = "cockroachdb/cockroach:v26.3.3";

    @Container
    static final CockroachContainer withRegion = new CockroachContainer(IMAGE)
            .withCommand("start-single-node --insecure --locality=region=us-east1");

    @Container
    static final CockroachContainer withoutRegion = new CockroachContainer(IMAGE);

    private static final AtomicInteger databases = new AtomicInteger();

    private final List<LocalContainerEntityManagerFactoryBean> factories = new ArrayList<>();

    @Entity
    @Table(name = "currencies")
    @GlobalTable
    public static class Currency {
        @Id
        String code;
    }

    @Entity
    @Table(name = "orders")
    @RegionalByRow(defaultRegion = "us-east1")
    public static class Order {
        @Id
        Long id;

        @CrdbRegion
        @Column(name = "crdb_region")
        String region;
    }

    @Entity
    @Table(name = "shipments")
    @RegionalByRow(defaultRegion = "us-east1")
    public static class Shipment {
        @Id
        Long id;

        @CrdbRegion
        @Column(name = "home_region")
        String home;
    }

    @Entity(name = "ExchangeRate")
    @GlobalTable
    public static class ExchangeRate {
        @Id
        String pair;
    }

    @Entity
    @Table(name = "rates")
    public static class Rate {
        @Id
        String pair;
    }

    @Entity
    @Table(name = "fees")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @GlobalTable
    public static class Fee {
        @Id
        String code;
    }

    @Entity
    public static class WireFee extends Fee {
    }

    /** Prefixes every table with {@code app_}, as an application's own naming strategy might. */
    public static class PrefixNamingStrategy extends PhysicalNamingStrategyStandardImpl {
        @Override
        public Identifier toPhysicalTableName(Identifier name, JdbcEnvironment context) {
            return Identifier.toIdentifier("app_" + name.getText().toLowerCase());
        }
    }

    @AfterEach
    void closeFactories() {
        factories.forEach(LocalContainerEntityManagerFactoryBean::destroy);
    }

    @Test
    void matchingSchemaPasses() {
        DataSource ds = multiregionDatabase();
        execute(ds,
                "CREATE TABLE currencies (code STRING PRIMARY KEY) LOCALITY GLOBAL",
                "CREATE TABLE orders (id INT PRIMARY KEY) LOCALITY REGIONAL BY ROW",
                "CREATE TABLE shipments (id INT PRIMARY KEY, home_region crdb_internal_region NOT NULL"
                        + " DEFAULT gateway_region()::crdb_internal_region) LOCALITY REGIONAL BY ROW AS home_region");

        EntityManagerFactory emf = entityManagerFactory(ds, Map.of(), Currency.class, Order.class, Shipment.class);

        assertThatNoException().isThrownBy(() -> verifier(Mode.VALIDATE, emf).verify(emf));
    }

    @Test
    void validateFailsStartupWithEveryProblem() {
        DataSource ds = multiregionDatabase();
        execute(ds,
                "CREATE TABLE currencies (code STRING PRIMARY KEY)",
                "CREATE TABLE shipments (id INT PRIMARY KEY, home_region crdb_internal_region NOT NULL"
                        + " DEFAULT gateway_region()::crdb_internal_region)");

        EntityManagerFactory emf = entityManagerFactory(ds, Map.of(), Currency.class, Order.class, Shipment.class);

        assertThatIllegalStateException()
                .isThrownBy(() -> verifier(Mode.VALIDATE, emf).verify(emf))
                .satisfies(ex -> System.out.println(ex.getMessage()))
                .withMessageContaining("found 3 problems")
                .withMessageContaining("public.currencies (@GlobalTable com.cockroachlabs.data.multiregion.MultiregionVerifierIT.Currency) is REGIONAL BY TABLE IN PRIMARY REGION, not GLOBAL")
                .withMessageContaining("public.orders (@RegionalByRow com.cockroachlabs.data.multiregion.MultiregionVerifierIT.Order) does not exist")
                .withMessageContaining("ALTER TABLE public.currencies SET LOCALITY GLOBAL;")
                .withMessageContaining("ALTER TABLE public.shipments SET LOCALITY REGIONAL BY ROW AS home_region;")
                .withMessageContaining(MultiregionReport.OVERVIEW);
    }

    @Test
    void warnLogsAndContinues(CapturedOutput output) {
        DataSource ds = multiregionDatabase();
        execute(ds, "CREATE TABLE currencies (code STRING PRIMARY KEY)");

        EntityManagerFactory emf = entityManagerFactory(ds, Map.of(), Currency.class);

        assertThatNoException().isThrownBy(() -> verifier(Mode.WARN, emf).afterSingletonsInstantiated());
        assertThat(output).contains("ALTER TABLE public.currencies SET LOCALITY GLOBAL;");
    }

    @Test
    void suggestedSqlFixesTheTables() throws SQLException {
        DataSource ds = multiregionDatabase();
        execute(ds,
                "CREATE TABLE currencies (code STRING PRIMARY KEY)",
                "CREATE TABLE orders (id INT PRIMARY KEY)",
                "CREATE TABLE shipments (id INT PRIMARY KEY, home_region crdb_internal_region NOT NULL"
                        + " DEFAULT gateway_region()::crdb_internal_region)");
        EntityManagerFactory emf = entityManagerFactory(ds, Map.of(), Currency.class, Order.class, Shipment.class);

        assertSuggestedSqlFixes(ds, emf, 3);
    }

    @Test
    void databaseNotMultiregionGetsDatabaseAndTableSql() throws SQLException {
        DataSource ds = database(withRegion, false);
        execute(ds,
                "CREATE TABLE currencies (code STRING PRIMARY KEY)",
                "CREATE TABLE orders (id INT PRIMARY KEY)");
        EntityManagerFactory emf = entityManagerFactory(ds, Map.of(), Currency.class, Order.class);

        MultiregionReport report = report(ds, emf);
        assertThat(report.problems()).singleElement().asString()
                .contains("is not multi-region")
                .contains("The cluster has regions: us-east1");
        assertThat(report.statements()).first().asString().endsWith("SET PRIMARY REGION \"us-east1\";");

        assertSuggestedSqlFixes(ds, emf, 3);
    }

    @Test
    void clusterWithoutRegions(CapturedOutput output) {
        DataSource ds = database(withoutRegion, false);
        execute(ds, "CREATE TABLE currencies (code STRING PRIMARY KEY)");
        EntityManagerFactory emf = entityManagerFactory(ds, Map.of(), Currency.class);

        verifier(Mode.WARN, emf).afterSingletonsInstantiated();

        assertThat(output)
                .contains("is not multi-region")
                .contains("The cluster has no regions")
                .contains("--locality=region=<region>")
                .contains(MultiregionReport.OVERVIEW)
                .doesNotContain("ALTER DATABASE");
    }

    @Test
    void tableNamesComeFromTheNamingStrategy() {
        DataSource ds = multiregionDatabase();
        execute(ds, "CREATE TABLE app_exchangerate (pair STRING PRIMARY KEY)");
        EntityManagerFactory emf = entityManagerFactory(ds,
                Map.of("hibernate.physical_naming_strategy", PrefixNamingStrategy.class.getName()),
                ExchangeRate.class);

        assertThatIllegalStateException()
                .isThrownBy(() -> verifier(Mode.VALIDATE, emf).verify(emf))
                .satisfies(ex -> System.out.println(ex.getMessage()))
                .withMessageContaining("found 1 problem:")
                .withMessageContaining("ALTER TABLE public.app_exchangerate SET LOCALITY GLOBAL;");

        execute(ds, "ALTER TABLE app_exchangerate SET LOCALITY GLOBAL");
        assertThatNoException().isThrownBy(() -> verifier(Mode.VALIDATE, emf).verify(emf));
    }

    @Test
    void unannotatedEntityOnGlobalTableFailsValidate() {
        DataSource ds = multiregionDatabase();
        execute(ds,
                "CREATE TABLE rates (pair STRING PRIMARY KEY) LOCALITY GLOBAL",
                "CREATE TABLE currencies (code STRING PRIMARY KEY) LOCALITY GLOBAL");
        EntityManagerFactory emf = entityManagerFactory(ds, Map.of(), Rate.class, Currency.class);

        assertThatIllegalStateException()
                .isThrownBy(() -> verifier(Mode.VALIDATE, emf).verify(emf))
                .satisfies(ex -> System.out.println(ex.getMessage()))
                .withMessageContaining("found 1 problem:")
                .withMessageContaining("Table public.rates is GLOBAL, but entity com.cockroachlabs.data.multiregion.MultiregionVerifierIT.Rate has no @GlobalTable.");
    }

    @Test
    void unannotatedEntityOnRegionalByRowTableIsLogged(CapturedOutput output) {
        DataSource ds = multiregionDatabase();
        execute(ds, "CREATE TABLE rates (pair STRING PRIMARY KEY) LOCALITY REGIONAL BY ROW");
        EntityManagerFactory emf = entityManagerFactory(ds, Map.of(), Rate.class);

        verifier(Mode.WARN, emf).afterSingletonsInstantiated();

        assertThat(output).contains("Table public.rates is REGIONAL BY ROW, but entity com.cockroachlabs.data.multiregion.MultiregionVerifierIT.Rate has no @RegionalByRow.");
    }

    @Test
    void subclassSharesItsParentsAnnotation() {
        DataSource ds = multiregionDatabase();
        execute(ds, "CREATE TABLE fees (code STRING PRIMARY KEY, dtype STRING NOT NULL) LOCALITY GLOBAL");
        EntityManagerFactory emf = entityManagerFactory(ds, Map.of(), Fee.class, WireFee.class);

        assertThatNoException().isThrownBy(() -> verifier(Mode.VALIDATE, emf).verify(emf));
    }

    // Database not multi-region, entities annotated

    @Test
    void databaseNotMultiregionWarns(CapturedOutput output) {
        DataSource ds = database(withRegion, false);
        execute(ds, "CREATE TABLE currencies (code STRING PRIMARY KEY)");
        EntityManagerFactory emf = entityManagerFactory(ds, Map.of(), Currency.class);

        assertWarns(emf, output, "is not multi-region", "SET PRIMARY REGION \"us-east1\";");
    }

    @Test
    void databaseNotMultiregionFailsValidate() {
        DataSource ds = database(withRegion, false);
        execute(ds, "CREATE TABLE currencies (code STRING PRIMARY KEY)");
        EntityManagerFactory emf = entityManagerFactory(ds, Map.of(), Currency.class);

        assertFailsStartup(emf, "is not multi-region", "SET PRIMARY REGION \"us-east1\";");
    }

    @Test
    void clusterWithoutRegionsFailsValidate() {
        DataSource ds = database(withoutRegion, false);
        execute(ds, "CREATE TABLE currencies (code STRING PRIMARY KEY)");
        EntityManagerFactory emf = entityManagerFactory(ds, Map.of(), Currency.class);

        assertFailsStartup(emf, "is not multi-region", "The cluster has no regions");
    }

    // Multi-region database, entity not annotated

    @Test
    void unannotatedEntityOnGlobalTableWarns(CapturedOutput output) {
        DataSource ds = multiregionDatabase();
        execute(ds, "CREATE TABLE rates (pair STRING PRIMARY KEY) LOCALITY GLOBAL");
        EntityManagerFactory emf = entityManagerFactory(ds, Map.of(), Rate.class);

        assertWarns(emf, output, "Table public.rates is GLOBAL, but entity com.cockroachlabs.data.multiregion.MultiregionVerifierIT.Rate has no @GlobalTable.");
    }

    @Test
    void unannotatedEntityOnRegionalByRowTableFailsValidate() {
        DataSource ds = multiregionDatabase();
        execute(ds, "CREATE TABLE rates (pair STRING PRIMARY KEY) LOCALITY REGIONAL BY ROW");
        EntityManagerFactory emf = entityManagerFactory(ds, Map.of(), Rate.class);

        assertFailsStartup(emf, "Table public.rates is REGIONAL BY ROW, but entity com.cockroachlabs.data.multiregion.MultiregionVerifierIT.Rate has no @RegionalByRow.");
    }

    // Multi-region database, entity annotated with the other locality: a contradiction, which
    // fails startup in warn mode too

    @Test
    void regionalByRowEntityOnGlobalTableFailsWarn() {
        DataSource ds = multiregionDatabase();
        execute(ds, "CREATE TABLE orders (id INT PRIMARY KEY) LOCALITY GLOBAL");
        EntityManagerFactory emf = entityManagerFactory(ds, Map.of(), Order.class);

        assertFailsStartup(Mode.WARN, emf,
                "Contradiction: Table public.orders (@RegionalByRow com.cockroachlabs.data.multiregion.MultiregionVerifierIT.Order) is GLOBAL, not REGIONAL BY ROW.",
                "ALTER TABLE public.orders SET LOCALITY REGIONAL BY ROW;");
    }

    @Test
    void regionalByRowEntityOnGlobalTableFailsValidate() {
        DataSource ds = multiregionDatabase();
        execute(ds, "CREATE TABLE orders (id INT PRIMARY KEY) LOCALITY GLOBAL");
        EntityManagerFactory emf = entityManagerFactory(ds, Map.of(), Order.class);

        assertFailsStartup(emf, "Table public.orders (@RegionalByRow com.cockroachlabs.data.multiregion.MultiregionVerifierIT.Order) is GLOBAL, not REGIONAL BY ROW.",
                "ALTER TABLE public.orders SET LOCALITY REGIONAL BY ROW;");
    }

    @Test
    void globalEntityOnRegionalByRowTableFailsWarn() {
        DataSource ds = multiregionDatabase();
        execute(ds, "CREATE TABLE currencies (code STRING PRIMARY KEY) LOCALITY REGIONAL BY ROW");
        EntityManagerFactory emf = entityManagerFactory(ds, Map.of(), Currency.class);

        assertFailsStartup(Mode.WARN, emf,
                "Contradiction: Table public.currencies (@GlobalTable com.cockroachlabs.data.multiregion.MultiregionVerifierIT.Currency) is REGIONAL BY ROW, not GLOBAL.",
                "ALTER TABLE public.currencies SET LOCALITY GLOBAL;");
    }

    @Test
    void contradictionIsSkippedWithNone() {
        DataSource ds = multiregionDatabase();
        execute(ds, "CREATE TABLE currencies (code STRING PRIMARY KEY) LOCALITY REGIONAL BY ROW");
        EntityManagerFactory emf = entityManagerFactory(ds, Map.of(), Currency.class);

        assertThatNoException().isThrownBy(() -> verifier(Mode.NONE, emf).afterSingletonsInstantiated());
    }

    @Test
    void globalEntityOnRegionalByRowTableFailsValidate() {
        DataSource ds = multiregionDatabase();
        execute(ds, "CREATE TABLE currencies (code STRING PRIMARY KEY) LOCALITY REGIONAL BY ROW");
        EntityManagerFactory emf = entityManagerFactory(ds, Map.of(), Currency.class);

        assertFailsStartup(emf, "Table public.currencies (@GlobalTable com.cockroachlabs.data.multiregion.MultiregionVerifierIT.Currency) is REGIONAL BY ROW, not GLOBAL.",
                "ALTER TABLE public.currencies SET LOCALITY GLOBAL;");
    }

    @Test
    void recognizesCockroachDB() throws SQLException {
        try (Connection connection = multiregionDatabase().getConnection()) {
            assertThat(MultiregionCheck.isCockroachDB(connection)).isTrue();
        }
    }

    /** Startup in {@link Mode#WARN} carries on and logs every expected line. */
    private static void assertWarns(EntityManagerFactory emf, CapturedOutput output, String... expected) {
        assertThatNoException().isThrownBy(() -> verifier(Mode.WARN, emf).afterSingletonsInstantiated());
        assertThat(output).contains("CockroachDB multiregion check (" + MultiregionReport.SOURCE + ", cockroachdb.multiregion.mode=warn) found").contains(expected);
    }

    /** Startup in {@link Mode#VALIDATE} fails with every expected line in the message. */
    private static void assertFailsStartup(EntityManagerFactory emf, String... expected) {
        assertFailsStartup(Mode.VALIDATE, emf, expected);
    }

    /** Startup in {@code mode} fails with every expected line in the message. */
    private static void assertFailsStartup(Mode mode, EntityManagerFactory emf, String... expected) {
        assertThatIllegalStateException()
                .isThrownBy(() -> verifier(mode, emf).afterSingletonsInstantiated())
                .satisfies(ex -> System.out.println(ex.getMessage()))
                .satisfies(ex -> assertThat(ex.getMessage()).contains(expected));
    }

    /** Runs the report's SQL, then waits for the asynchronous locality changes to settle. */
    private void assertSuggestedSqlFixes(DataSource ds, EntityManagerFactory emf, int statements) throws SQLException {
        MultiregionReport report = report(ds, emf);
        assertThat(report.statements()).hasSize(statements);
        execute(ds, report.statements().stream()
                .map(statement -> statement.replaceAll(";.*$", ""))
                .toArray(String[]::new));

        long deadline = System.currentTimeMillis() + 60_000;
        while (!report(ds, emf).isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(report(ds, emf).format(Mode.WARN)).isEqualTo(new MultiregionReport().format(Mode.WARN));
    }

    private static MultiregionReport report(DataSource ds, EntityManagerFactory emf) throws SQLException {
        List<MultiregionTable> tables = MultiregionEntities.read(
                emf.unwrap(SessionFactoryImplementor.class));
        try (Connection connection = ds.getConnection()) {
            return MultiregionCheck.evaluate(MultiregionCheck.load(connection, tables), tables);
        }
    }

    private static MultiregionVerifier verifier(Mode mode, EntityManagerFactory emf) {
        @SuppressWarnings("unchecked")
        ObjectProvider<EntityManagerFactory> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation -> Stream.of(emf));
        return new MultiregionVerifier(mode, provider);
    }

    private DataSource multiregionDatabase() {
        return database(withRegion, true);
    }

    private static DataSource database(CockroachContainer container, boolean multiregion) {
        String name = "mr_" + databases.incrementAndGet();
        DataSource admin = dataSource(container, "defaultdb");
        execute(admin, "CREATE DATABASE " + name);
        if (multiregion) {
            execute(admin, "ALTER DATABASE " + name + " SET PRIMARY REGION \"us-east1\"");
        }
        return dataSource(container, name);
    }

    private static DataSource dataSource(CockroachContainer container, String database) {
        return new DriverManagerDataSource("jdbc:postgresql://" + container.getHost() + ":"
                + container.getMappedPort(26257) + "/" + database + "?sslmode=disable", "root", "");
    }

    private static void execute(DataSource ds, String... statements) {
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        for (String statement : statements) {
            jdbc.execute(statement);
        }
    }

    private EntityManagerFactory entityManagerFactory(DataSource ds, Map<String, Object> properties,
                                                      Class<?>... types) {
        var bean = new LocalContainerEntityManagerFactoryBean();
        bean.setDataSource(ds);
        bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        bean.setManagedTypes(PersistenceManagedTypes.of(
                Arrays.stream(types).map(Class::getName).toArray(String[]::new)));
        Map<String, Object> jpa = new HashMap<>(properties);
        jpa.put("hibernate.hbm2ddl.auto", "none");
        bean.setJpaPropertyMap(jpa);
        bean.afterPropertiesSet();
        factories.add(bean);
        return bean.getObject();
    }
}
