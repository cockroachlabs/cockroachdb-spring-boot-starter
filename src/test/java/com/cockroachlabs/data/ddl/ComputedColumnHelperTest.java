package com.cockroachlabs.data.ddl;

import com.cockroachlabs.data.annotation.ComputedColumn;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.*;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for ComputedColumnHelper using Testcontainers.
 * A CockroachDB container is automatically started and stopped.
 */
@Testcontainers
class ComputedColumnHelperTest {

    @Container
    static final CockroachContainer crdb = new CockroachContainer(
            "cockroachdb/cockroach:v26.3.3");

    private Connection conn;

    @BeforeEach
    void connect() throws SQLException {
        conn = DriverManager.getConnection(crdb.getJdbcUrl(), crdb.getUsername(), crdb.getPassword());
    }

    @AfterEach
    void disconnect() throws SQLException {
        if (conn != null) conn.close();
    }

    // --- Entity classes for annotation scanning ---

    static class OrderEntity {
        private Integer price;
        private Integer quantity;

        @ComputedColumn(expression = "price * quantity")
        private Integer total;

        @ComputedColumn(expression = "price * quantity * 2")
        private Integer totalDoubled;
    }

    static class InvalidEntity {
        @ComputedColumn(expression = "a + b", stored = false)
        private Integer computed;
    }

    static class NoComputedEntity {
        private String name;
    }

    // --- DDL generation + live execution tests ---

    @Test
    void generateDDL_producesCorrectStatements() {
        List<String> ddl = ComputedColumnHelper.generateDDL("orders", OrderEntity.class);
        assertThat(ddl).hasSize(2);
        assertThat(ddl.get(0)).contains("INT8 AS (price * quantity) STORED");
        assertThat(ddl.get(0)).contains("orders");
    }

    @Test
    void generateDDL_convertsFieldNameToSnakeCase() {
        List<String> ddl = ComputedColumnHelper.generateDDL("orders", OrderEntity.class);
        assertThat(ddl.get(1)).contains("total_doubled");
    }

    @Test
    void generateDDL_usesIfNotExists() {
        List<String> ddl = ComputedColumnHelper.generateDDL("orders", OrderEntity.class);
        assertThat(ddl.get(0)).contains("IF NOT EXISTS");
    }

    @Test
    void generateDDL_rejectsVirtualColumns() {
        assertThatThrownBy(() ->
                ComputedColumnHelper.generateDDL("test", InvalidEntity.class)
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stored");
    }

    @Test
    void generateDDL_emptyForNoAnnotations() {
        List<String> ddl = ComputedColumnHelper.generateDDL("test", NoComputedEntity.class);
        assertThat(ddl).isEmpty();
    }

    @Test
    void validate_rejectsVirtualColumns() {
        assertThatThrownBy(() ->
                ComputedColumnHelper.validate(InvalidEntity.class)
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("virtual");
    }

    @Test
    void validate_passesForStoredColumns() {
        assertThatCode(() ->
                ComputedColumnHelper.validate(OrderEntity.class)
        ).doesNotThrowAnyException();
    }

    @Test
    void camelToSnake_convertsCorrectly() {
        assertThat(ComputedColumnHelper.camelToSnake("totalDoubled")).isEqualTo("total_doubled");
        assertThat(ComputedColumnHelper.camelToSnake("simple")).isEqualTo("simple");
    }

    // --- Live CockroachDB integration tests (Testcontainers) ---

    @Test
    void liveCRDB_connectionVerifiesCockroachDB() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT version()")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).contains("CockroachDB");
        }
    }

    @Test
    void liveCRDB_computedColumnCalculatesCorrectly() throws SQLException {
        String table = "t_calc_" + UUID.randomUUID().toString().substring(0, 8);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE " + table
                    + " (id INT8 PRIMARY KEY DEFAULT unique_rowid(), "
                    + "price INT8 NOT NULL, quantity INT8 NOT NULL)");

            List<String> ddl = ComputedColumnHelper.generateDDL(table, OrderEntity.class);
            for (String sql : ddl) {
                stmt.execute(sql);
            }

            stmt.execute("INSERT INTO " + table + " (price, quantity) VALUES (10, 5)");

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT total, total_doubled FROM " + table + " LIMIT 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("total")).isEqualTo(50);
                assertThat(rs.getInt("total_doubled")).isEqualTo(100);
            }
        } finally {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }

    @Test
    void liveCRDB_computedColumnAppearsInSchema() throws SQLException {
        String table = "t_schema_" + UUID.randomUUID().toString().substring(0, 8);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE " + table
                    + " (id INT8 PRIMARY KEY DEFAULT unique_rowid(), "
                    + "price INT8, quantity INT8)");

            List<String> ddl = ComputedColumnHelper.generateDDL(table, OrderEntity.class);
            for (String sql : ddl) {
                stmt.execute(sql);
            }

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT column_name FROM information_schema.columns "
                            + "WHERE table_name = '" + table
                            + "' AND is_generated = 'ALWAYS'")) {
                int count = 0;
                while (rs.next()) count++;
                assertThat(count).isEqualTo(2);
            }
        } finally {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }

    @Test
    void liveCRDB_ifNotExistsIsIdempotent() throws SQLException {
        String table = "t_idemp_" + UUID.randomUUID().toString().substring(0, 8);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE " + table
                    + " (id INT8 PRIMARY KEY DEFAULT unique_rowid(), "
                    + "price INT8, quantity INT8)");

            List<String> ddl = ComputedColumnHelper.generateDDL(table, OrderEntity.class);

            for (String sql : ddl) stmt.execute(sql);
            // Run again — IF NOT EXISTS prevents errors
            for (String sql : ddl) {
                assertThatCode(() -> stmt.execute(sql)).doesNotThrowAnyException();
            }
        } finally {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }

    @Test
    void liveCRDB_multipleRowsComputedCorrectly() throws SQLException {
        String table = "t_multi_" + UUID.randomUUID().toString().substring(0, 8);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE " + table
                    + " (id INT8 PRIMARY KEY DEFAULT unique_rowid(), "
                    + "price INT8, quantity INT8)");

            List<String> ddl = ComputedColumnHelper.generateDDL(table, OrderEntity.class);
            for (String sql : ddl) stmt.execute(sql);

            stmt.execute("INSERT INTO " + table + " (price, quantity) VALUES (10, 5)");
            stmt.execute("INSERT INTO " + table + " (price, quantity) VALUES (20, 3)");
            stmt.execute("INSERT INTO " + table + " (price, quantity) VALUES (7, 10)");

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT price, quantity, total, total_doubled FROM " + table
                            + " ORDER BY price")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("total")).isEqualTo(70);       // 7*10
                assertThat(rs.getInt("total_doubled")).isEqualTo(140); // 70*2

                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("total")).isEqualTo(50);       // 10*5
                assertThat(rs.getInt("total_doubled")).isEqualTo(100); // 50*2

                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("total")).isEqualTo(60);       // 20*3
                assertThat(rs.getInt("total_doubled")).isEqualTo(120); // 60*2
            }
        } finally {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }
}
