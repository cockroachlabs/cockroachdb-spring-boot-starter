package com.cockroachlabs.data.ddl;

import com.cockroachlabs.data.ai.DistanceMetric;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates CockroachDB v26.2+ {@code CREATE VECTOR INDEX} DDL.
 * <p>
 * In CockroachDB v26.2, the legacy {@code CREATE INDEX ... USING cspann} syntax
 * is replaced by the dedicated {@code CREATE VECTOR INDEX} statement, which
 * supports prefix columns for pre-filtered vector searches.
 *
 * <p><strong>Migration from v24.x / v25.x:</strong></p>
 * <pre>
 * -- Old (v24.x/v25.x):
 * CREATE INDEX idx ON docs USING cspann (embedding vector_cosine_ops);
 *
 * -- New (v26.2+):
 * CREATE VECTOR INDEX idx ON docs (embedding vector_cosine_ops);
 *
 * -- With prefix columns (v26.2+):
 * CREATE VECTOR INDEX idx ON docs (tenant_id, embedding vector_cosine_ops);
 * </pre>
 */
public final class VectorIndexHelper {

    private VectorIndexHelper() {}

    /**
     * Generate a v26.2-compatible {@code CREATE VECTOR INDEX} statement.
     *
     * @param indexName      the index name
     * @param tableName      the table containing the vector column
     * @param vectorColumn   the column holding VECTOR data
     * @param metric         the distance metric (determines the opclass)
     * @param prefixColumns  optional prefix columns for pre-filtering (may be empty)
     * @return the DDL statement
     */
    public static String generateCreateVectorIndex(String indexName,
                                                    String tableName,
                                                    String vectorColumn,
                                                    DistanceMetric metric,
                                                    String... prefixColumns) {
        validateNotBlank(indexName, "indexName");
        validateNotBlank(tableName, "tableName");
        validateNotBlank(vectorColumn, "vectorColumn");

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE VECTOR INDEX IF NOT EXISTS ").append(indexName)
          .append(" ON ").append(tableName).append(" (");

        if (prefixColumns != null && prefixColumns.length > 0) {
            String prefix = Arrays.stream(prefixColumns)
                    .filter(c -> c != null && !c.isBlank())
                    .collect(Collectors.joining(", "));
            if (!prefix.isEmpty()) {
                sb.append(prefix).append(", ");
            }
        }

        sb.append(vectorColumn).append(' ').append(metric.opclass());
        sb.append(')');

        return sb.toString();
    }

    /**
     * Generate the legacy v24.x/v25.x {@code CREATE INDEX ... USING cspann} statement.
     * Provided for backward compatibility.
     *
     * @param indexName    the index name
     * @param tableName    the table containing the vector column
     * @param vectorColumn the column holding VECTOR data
     * @param metric       the distance metric
     * @return the DDL statement
     */
    public static String generateCreateCspannIndex(String indexName,
                                                    String tableName,
                                                    String vectorColumn,
                                                    DistanceMetric metric) {
        validateNotBlank(indexName, "indexName");
        validateNotBlank(tableName, "tableName");
        validateNotBlank(vectorColumn, "vectorColumn");

        return "CREATE INDEX IF NOT EXISTS " + indexName
                + " ON " + tableName + " USING cspann ("
                + vectorColumn + " " + metric.opclass() + ")";
    }

    /**
     * Generate a {@code DROP INDEX IF EXISTS} statement for a vector index.
     *
     * @param indexName the index to drop
     * @return the DDL statement
     */
    public static String generateDropIndex(String indexName) {
        validateNotBlank(indexName, "indexName");
        return "DROP INDEX IF EXISTS " + indexName;
    }

    /**
     * Generate the cluster setting statement to enable vector indexes (v26.2+).
     *
     * @return the {@code SET CLUSTER SETTING} statement
     */
    public static String enableVectorIndexSetting() {
        return "SET CLUSTER SETTING feature.vector_index.enabled = true";
    }

    /**
     * Generate the session-level beam size setting.
     *
     * @param beamSize the desired beam size (must be &gt; 0)
     * @return the {@code SET} statement
     * @throws IllegalArgumentException if beamSize is not positive
     */
    public static String setBeamSize(int beamSize) {
        if (beamSize <= 0) {
            throw new IllegalArgumentException("beamSize must be positive, got: " + beamSize);
        }
        return "SET LOCAL vector_search_beam_size = " + beamSize;
    }

    private static void validateNotBlank(String value, String paramName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(paramName + " must not be blank");
        }
    }
}
