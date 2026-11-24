package com.cockroachlabs.data.multiregion;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.cockroachlabs.data.multiregion.MultiregionState.TableInfo;
import com.cockroachlabs.data.multiregion.MultiregionState.TableName;
import com.cockroachlabs.data.autoconfigure.CockroachDBProperties.Multiregion.Mode;
import com.cockroachlabs.data.multiregion.MultiregionTable.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultiregionCheckTest {

    private static final String REGION_TYPE = "public.crdb_internal_region";

    private static final MultiregionTable CURRENCY =
            new MultiregionTable("com.example.Currency", null, "currency", Kind.GLOBAL, null);

    private static final MultiregionTable ORDER =
            new MultiregionTable("com.example.Order", null, "orders", Kind.REGIONAL_BY_ROW, null);

    private final Map<TableName, TableInfo> tables = new HashMap<>();

    private void table(String name, String locality, Map<String, String> columnTypes) {
        tables.put(new TableName("public", name), new TableInfo(locality, columnTypes));
    }

    private MultiregionState multiregion() {
        return new MultiregionState("app", "public", "us-east1", List.of("us-east1", "us-west1"),
                tables, Set.of("user"));
    }

    private MultiregionReport evaluate(MultiregionState state, MultiregionTable... entities) {
        return MultiregionCheck.evaluate(state, List.of(entities));
    }

    @Test
    void matchingTablesReportNothing() {
        table("currency", "GLOBAL", Map.of());
        table("orders", "REGIONAL BY ROW", Map.of("crdb_region", REGION_TYPE));

        assertThat(evaluate(multiregion(), CURRENCY, ORDER).isEmpty()).isTrue();
    }

    @Test
    void globalTableWithOtherLocality() {
        table("currency", "REGIONAL BY TABLE IN PRIMARY REGION", Map.of());

        MultiregionReport report = evaluate(multiregion(), CURRENCY);

        assertThat(report.problems()).containsExactly(
                "Table public.currency (@GlobalTable com.example.Currency) is REGIONAL BY TABLE IN PRIMARY REGION, not GLOBAL.");
        assertThat(report.statements()).containsExactly("ALTER TABLE public.currency SET LOCALITY GLOBAL;");
    }

    @Test
    void regionalByRowWithoutRegionField() {
        table("orders", "REGIONAL BY TABLE IN PRIMARY REGION", Map.of());

        MultiregionReport report = evaluate(multiregion(), ORDER);

        assertThat(report.problems()).containsExactly(
                "Table public.orders (@RegionalByRow com.example.Order) is REGIONAL BY TABLE IN PRIMARY REGION, not REGIONAL BY ROW.");
        assertThat(report.statements()).containsExactly(
                "ALTER TABLE public.orders SET LOCALITY REGIONAL BY ROW;  -- rewrites the table");
    }

    @Test
    void anyRegionColumnSatisfiesEntityWithoutRegionField() {
        table("orders", "REGIONAL BY ROW AS home", Map.of("home", REGION_TYPE));

        assertThat(evaluate(multiregion(), ORDER).isEmpty()).isTrue();
    }

    @Test
    void regionalByRowAsRegionFieldColumn() {
        var order = new MultiregionTable("com.example.Order", null, "orders", Kind.REGIONAL_BY_ROW, "home");
        table("orders", "REGIONAL BY TABLE IN PRIMARY REGION", Map.of("home", REGION_TYPE));

        MultiregionReport report = evaluate(multiregion(), order);

        assertThat(report.statements()).containsExactly(
                "ALTER TABLE public.orders SET LOCALITY REGIONAL BY ROW AS home;  -- rewrites the table");
    }

    @Test
    void regionalByRowOnAnotherColumnThanRegionField() {
        var order = new MultiregionTable("com.example.Order", null, "orders", Kind.REGIONAL_BY_ROW, "home");
        table("orders", "REGIONAL BY ROW", Map.of("home", REGION_TYPE, "crdb_region", REGION_TYPE));

        MultiregionReport report = evaluate(multiregion(), order);

        assertThat(report.problems()).containsExactly(
                "Table public.orders (@RegionalByRow com.example.Order) is REGIONAL BY ROW, not REGIONAL BY ROW AS home.");
    }

    @Test
    void regionFieldNamedCrdbRegionMatchesImplicitColumn() {
        var order = new MultiregionTable("com.example.Order", null, "orders", Kind.REGIONAL_BY_ROW, "crdb_region");
        table("orders", "REGIONAL BY ROW", Map.of("crdb_region", REGION_TYPE));

        assertThat(evaluate(multiregion(), order).isEmpty()).isTrue();
    }

    @Test
    void regionColumnOfWrongTypeGetsNoStatement() {
        var order = new MultiregionTable("com.example.Order", null, "orders", Kind.REGIONAL_BY_ROW, "region");
        table("orders", "REGIONAL BY TABLE IN PRIMARY REGION", Map.of("region", "STRING"));

        MultiregionReport report = evaluate(multiregion(), order);

        assertThat(report.problems()).containsExactly(
                "Table public.orders (@RegionalByRow com.example.Order): column region is STRING, but REGIONAL BY ROW"
                        + " needs a crdb_internal_region column. Change it with your migrations.");
        assertThat(report.statements()).isEmpty();
    }

    @Test
    void missingRegionColumnGetsNoStatement() {
        var order = new MultiregionTable("com.example.Order", null, "orders", Kind.REGIONAL_BY_ROW, "home");
        table("orders", "REGIONAL BY TABLE IN PRIMARY REGION", Map.of());

        MultiregionReport report = evaluate(multiregion(), order);

        assertThat(report.problems()).singleElement().asString()
                .contains("column home of the @CrdbRegion field does not exist");
        assertThat(report.statements()).isEmpty();
    }

    @Test
    void missingTable() {
        MultiregionReport report = evaluate(multiregion(), CURRENCY);

        assertThat(report.problems()).containsExactly(
                "Table public.currency (@GlobalTable com.example.Currency) does not exist. Create it with your migrations.");
        assertThat(report.statements()).isEmpty();
    }

    @Test
    void namesAreQuotedInStatements() {
        var user = new MultiregionTable("com.example.User", "Sales", "user", Kind.GLOBAL, null);
        tables.put(new TableName("Sales", "user"), new TableInfo("REGIONAL BY TABLE IN PRIMARY REGION", Map.of()));

        assertThat(evaluate(multiregion(), user).statements())
                .containsExactly("ALTER TABLE \"Sales\".\"user\" SET LOCALITY GLOBAL;");
    }

    @Test
    void databaseNotMultiregionIsReportedOnceWithEveryStatement() {
        table("currency", null, Map.of());
        table("orders", null, Map.of());
        var state = new MultiregionState("app", "public", null, List.of("eu-west1", "us-east1", "us-west1"),
                tables, Set.of());

        MultiregionReport report = evaluate(state, CURRENCY, ORDER);

        assertThat(report.problems()).containsExactly(
                "Database app is not multi-region; @GlobalTable/@RegionalByRow on 2 entities (com.example.Currency, com.example.Order) have no effect."
                        + "\nThe cluster has regions: eu-west1, us-east1, us-west1."
                        + " Choose the primary region deliberately; the SQL below uses the first one.");
        assertThat(report.statements()).containsExactly(
                "ALTER DATABASE app SET PRIMARY REGION \"eu-west1\";",
                "ALTER DATABASE app ADD REGION \"us-east1\";",
                "ALTER DATABASE app ADD REGION \"us-west1\";",
                "ALTER TABLE public.currency SET LOCALITY GLOBAL;",
                "ALTER TABLE public.orders SET LOCALITY REGIONAL BY ROW;  -- rewrites the table");
    }

    @Test
    void clusterWithoutRegions() {
        table("currency", null, Map.of());
        var state = new MultiregionState("app", "public", null, List.of(), tables, Set.of());

        MultiregionReport report = evaluate(state, CURRENCY);

        assertThat(report.problems()).singleElement().asString()
                .contains("Database app is not multi-region")
                .contains("The cluster has no regions")
                .contains("--locality=region=<region>");
        assertThat(report.statements()).containsExactly("ALTER TABLE public.currency SET LOCALITY GLOBAL;");
    }

    @Test
    void entityWithBothAnnotations() {
        var both = new MultiregionTable("com.example.Odd", null, "odd", Kind.BOTH, null);

        assertThat(evaluate(multiregion(), both).problems()).containsExactly(
                "Contradiction: Entity com.example.Odd is annotated with both @GlobalTable and @RegionalByRow; keep one.");
        assertThat(evaluate(multiregion(), both).hasContradictions()).isTrue();
    }

    @Test
    void globalEntityOnRegionalByRowTableIsAContradiction() {
        table("currency", "REGIONAL BY ROW", Map.of());

        MultiregionReport report = evaluate(multiregion(), CURRENCY);

        assertThat(report.problems()).containsExactly(
                "Contradiction: Table public.currency (@GlobalTable com.example.Currency) is REGIONAL BY ROW, not GLOBAL.");
        assertThat(report.statements()).containsExactly("ALTER TABLE public.currency SET LOCALITY GLOBAL;");
        assertThat(report.hasContradictions()).isTrue();
    }

    @Test
    void regionalByRowEntityOnGlobalTableIsAContradiction() {
        var order = new MultiregionTable("com.example.Order", null, "orders", Kind.REGIONAL_BY_ROW, null);
        table("orders", "GLOBAL", Map.of());

        MultiregionReport report = evaluate(multiregion(), order);

        assertThat(report.problems()).containsExactly(
                "Contradiction: Table public.orders (@RegionalByRow com.example.Order) is GLOBAL, not REGIONAL BY ROW.");
        assertThat(report.statements()).containsExactly(
                "ALTER TABLE public.orders SET LOCALITY REGIONAL BY ROW;  -- rewrites the table");
        assertThat(report.hasContradictions()).isTrue();
    }

    @Test
    void regionalByRowEntityOnGlobalTableWithBadRegionColumnReportsBoth() {
        var order = new MultiregionTable("com.example.Order", null, "orders", Kind.REGIONAL_BY_ROW, "home");
        table("orders", "GLOBAL", Map.of("home", "STRING"));

        MultiregionReport report = evaluate(multiregion(), order);

        assertThat(report.problems()).hasSize(2);
        assertThat(report.problems().get(0)).startsWith("Contradiction: ").endsWith("is GLOBAL, not REGIONAL BY ROW AS home.");
        assertThat(report.problems().get(1)).contains("column home is STRING");
        assertThat(report.statements()).isEmpty();
    }

    @Test
    void localityNotYetAppliedIsNotAContradiction() {
        table("currency", "REGIONAL BY TABLE IN PRIMARY REGION", Map.of());

        assertThat(evaluate(multiregion(), CURRENCY).hasContradictions()).isFalse();
    }

    @Test
    void tableSharedByEntitiesIsCheckedOnce() {
        var sub = new MultiregionTable("com.example.SpecialCurrency", null, "currency", Kind.GLOBAL, null);
        table("currency", "REGIONAL BY TABLE IN PRIMARY REGION", Map.of());

        assertThat(evaluate(multiregion(), CURRENCY, sub).statements()).hasSize(1);
    }

    @Test
    void unannotatedEntityOnGlobalTable() {
        var rate = new MultiregionTable("com.example.Rate", null, "rates", Kind.NONE, null);
        table("rates", "GLOBAL", Map.of());

        MultiregionReport report = evaluate(multiregion(), rate);

        assertThat(report.problems()).containsExactly(
                "Table public.rates is GLOBAL, but entity com.example.Rate has no @GlobalTable. Add @GlobalTable to it.");
        assertThat(report.statements()).isEmpty();
    }

    @Test
    void unannotatedEntityOnRegionalByRowTable() {
        var order = new MultiregionTable("com.example.Order", null, "orders", Kind.NONE, null);
        table("orders", "REGIONAL BY ROW AS home", Map.of());

        assertThat(evaluate(multiregion(), order).problems()).containsExactly(
                "Table public.orders is REGIONAL BY ROW AS home, but entity com.example.Order has no @RegionalByRow."
                        + " Add @RegionalByRow to it, and @CrdbRegion to the field mapped to home"
                        + " so rows are homed in the transaction's region.");
    }

    @Test
    void unannotatedEntityOnRegionalByTableIsFine() {
        var note = new MultiregionTable("com.example.Note", null, "notes", Kind.NONE, null);
        table("notes", "REGIONAL BY TABLE IN PRIMARY REGION", Map.of());

        assertThat(evaluate(multiregion(), note).isEmpty()).isTrue();
    }

    @Test
    void unannotatedEntityWithoutTableIsLeftToHibernate() {
        var note = new MultiregionTable("com.example.Note", null, "notes", Kind.NONE, null);

        assertThat(evaluate(multiregion(), note).isEmpty()).isTrue();
    }

    @Test
    void databaseNotMultiregionWithoutAnnotationsIsFine() {
        var note = new MultiregionTable("com.example.Note", null, "notes", Kind.NONE, null);
        table("notes", null, Map.of());
        var state = new MultiregionState("app", "public", null, List.of("us-east1"), tables, Set.of());

        assertThat(evaluate(state, note).isEmpty()).isTrue();
    }

    @Test
    void annotatedEntityGovernsTableSharedWithUnannotatedOne() {
        var view = new MultiregionTable("com.example.CurrencyView", null, "currency", Kind.NONE, null);
        table("currency", "GLOBAL", Map.of());

        assertThat(evaluate(multiregion(), CURRENCY, view).isEmpty()).isTrue();
    }

    @Test
    void entitiesWithConflictingAnnotationsOnOneTable() {
        var regional = new MultiregionTable("com.example.RegionalCurrency", null, "currency", Kind.REGIONAL_BY_ROW, null);
        table("currency", "GLOBAL", Map.of());

        MultiregionReport report = evaluate(multiregion(), CURRENCY, regional);

        assertThat(report.problems()).containsExactly(
                "Contradiction: Table public.currency is mapped by entities with conflicting annotations:"
                        + " com.example.Currency (@GlobalTable), com.example.RegionalCurrency (@RegionalByRow). Annotate them alike.");
        assertThat(report.statements()).isEmpty();
    }

    @Test
    void otherDatabasesAreNotCockroachDB() throws SQLException {
        assertThat(MultiregionCheck.isCockroachDB(connectionReportingVersion("PostgreSQL 17.2 on aarch64"))).isFalse();

        Connection failing = mock(Connection.class);
        when(failing.createStatement()).thenThrow(new SQLException("Function \"VERSION\" not found"));
        assertThat(MultiregionCheck.isCockroachDB(failing)).isFalse();
    }

    @Test
    void cockroachDBIsRecognized() throws SQLException {
        assertThat(MultiregionCheck.isCockroachDB(connectionReportingVersion(
                "CockroachDB CCL v26.3.3 (aarch64-unknown-linux-gnu)"))).isTrue();
    }

    private static Connection connectionReportingVersion(String version) throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT version()")).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString(1)).thenReturn(version);
        return connection;
    }

    @Test
    void parsesRegionalByRowColumn() {
        assertThat(MultiregionCheck.regionalByRowColumn("REGIONAL BY ROW")).isEqualTo("crdb_region");
        assertThat(MultiregionCheck.regionalByRowColumn("REGIONAL BY ROW AS home")).isEqualTo("home");
        assertThat(MultiregionCheck.regionalByRowColumn("REGIONAL BY ROW AS \"Home\"")).isEqualTo("Home");
        assertThat(MultiregionCheck.regionalByRowColumn("GLOBAL")).isNull();
        assertThat(MultiregionCheck.regionalByRowColumn(null)).isNull();
    }

    @Test
    void formatsReport() {
        table("currency", "REGIONAL BY TABLE IN PRIMARY REGION", Map.of());

        assertThat(evaluate(multiregion(), CURRENCY).format(Mode.WARN)).isEqualTo("""
                CockroachDB multiregion check (com.cockroachlabs.data.multiregion.MultiregionCheck, cockroachdb.multiregion.mode=warn) found 1 problem:
                  - Table public.currency (@GlobalTable com.example.Currency) is REGIONAL BY TABLE IN PRIMARY REGION, not GLOBAL.

                Suggested SQL (review, then add to a migration; or alter the annotations):
                  ALTER TABLE public.currency SET LOCALITY GLOBAL;

                See also: https://docs.cockroachlabs.com/docs/stable/multiregion-overview""");
    }

    @Test
    void formatsReportWithContradiction() {
        table("currency", "REGIONAL BY ROW", Map.of());

        assertThat(evaluate(multiregion(), CURRENCY).format(Mode.WARN)).isEqualTo("""
                CockroachDB multiregion check (com.cockroachlabs.data.multiregion.MultiregionCheck, cockroachdb.multiregion.mode=warn) found 1 problem:
                  - Contradiction: Table public.currency (@GlobalTable com.example.Currency) is REGIONAL BY ROW, not GLOBAL.

                Contradictions stop startup in every mode but none: the annotations and the schema disagree about where rows live, so one of them is wrong.

                Suggested SQL (review, then add to a migration; or alter the annotations):
                  ALTER TABLE public.currency SET LOCALITY GLOBAL;

                See also: https://docs.cockroachlabs.com/docs/stable/multiregion-overview""");
    }
}
