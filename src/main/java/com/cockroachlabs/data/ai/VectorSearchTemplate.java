package com.cockroachlabs.data.ai;

import java.util.List;
import java.util.Map;
import com.cockroachlabs.data.ai.metrics.AIMetrics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class VectorSearchTemplate {

    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager txManager;
    private final AIMetrics metrics;

    public VectorSearchTemplate(JdbcTemplate jdbcTemplate,
                                PlatformTransactionManager txManager,
                                AIMetrics metrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.txManager = txManager;
        this.metrics = metrics;
    }

    public List<VectorSearchResult<Object>> search(String table,
                                                    String vectorColumn,
                                                    float[] queryVector,
                                                    DistanceMetric metric,
                                                    int limit) {
        return search(table, vectorColumn, queryVector, metric, limit, List.of(), "", 0);
    }

    public List<VectorSearchResult<Object>> search(String table,
                                                    String vectorColumn,
                                                    float[] queryVector,
                                                    DistanceMetric metric,
                                                    int limit,
                                                    List<String> columns,
                                                    String filter,
                                                    int beamSize) {
        validateInputs(table, vectorColumn, queryVector);

        TransactionTemplate txTemplate = new TransactionTemplate(txManager);
        txTemplate.setReadOnly(true);

        long start = System.nanoTime();
        try {
            List<VectorSearchResult<Object>> results = txTemplate.execute(status -> {
                if (beamSize > 0) {
                    jdbcTemplate.execute(
                            "SET LOCAL vector_search_beam_size = " + beamSize);
                }

                String vectorLiteral = toVectorLiteral(queryVector);
                String selectCols = buildSelectColumns(columns);
                String distanceExpr = vectorColumn + " " + metric.operator()
                        + " '" + vectorLiteral + "'::vector";

                StringBuilder sql = new StringBuilder();
                sql.append("SELECT ").append(selectCols).append(", ")
                   .append(distanceExpr).append(" AS distance FROM ")
                   .append(table);

                if (filter != null && !filter.isBlank()) {
                    sql.append(" WHERE ").append(filter);
                }

                sql.append(" ORDER BY ").append(distanceExpr)
                   .append(" LIMIT ").append(limit);

                return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
                    Object id = rs.getObject(1);
                    double distance = rs.getDouble("distance");
                    return new VectorSearchResult<>(id, distance);
                });
            });

            int count = results != null ? results.size() : 0;
            metrics.recordVectorSearch(start, count, true);
            return results != null ? results : List.of();
        } catch (Exception ex) {
            metrics.recordVectorSearch(start, 0, false);
            throw ex;
        }
    }

    public void storeEmbedding(String table,
                               String idColumn,
                               Object idValue,
                               String vectorColumn,
                               float[] embedding) {
        validateInputs(table, vectorColumn, embedding);
        String vectorLiteral = toVectorLiteral(embedding);

        jdbcTemplate.update(
                "INSERT INTO " + table + " (" + idColumn + ", " + vectorColumn
                        + ") VALUES (?, '" + vectorLiteral + "'::vector)"
                        + " ON CONFLICT (" + idColumn + ") DO UPDATE SET "
                        + vectorColumn + " = '" + vectorLiteral + "'::vector",
                idValue);
    }

    public void storeEmbeddingWithMetadata(String table,
                                           String idColumn,
                                           Object idValue,
                                           String vectorColumn,
                                           float[] embedding,
                                           Map<String, Object> metadata) {
        validateInputs(table, vectorColumn, embedding);
        String vectorLiteral = toVectorLiteral(embedding);

        StringBuilder cols = new StringBuilder(idColumn + ", " + vectorColumn);
        StringBuilder vals = new StringBuilder("?, '" + vectorLiteral + "'::vector");
        StringBuilder updates = new StringBuilder(
                vectorColumn + " = '" + vectorLiteral + "'::vector");

        Object[] params = new Object[1 + metadata.size()];
        params[0] = idValue;
        int i = 1;
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            cols.append(", ").append(entry.getKey());
            vals.append(", ?");
            updates.append(", ").append(entry.getKey()).append(" = ?");
            params[i++] = entry.getValue();
        }

        Object[] allParams = new Object[params.length + metadata.size()];
        System.arraycopy(params, 0, allParams, 0, params.length);
        i = params.length;
        for (Object value : metadata.values()) {
            allParams[i++] = value;
        }

        jdbcTemplate.update(
                "INSERT INTO " + table + " (" + cols + ") VALUES (" + vals + ")"
                        + " ON CONFLICT (" + idColumn + ") DO UPDATE SET " + updates,
                allParams);
    }

    public static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private String buildSelectColumns(List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return "*";
        }
        return String.join(", ", columns);
    }

    private void validateInputs(String table, String vectorColumn, float[] vector) {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("Table name must not be blank");
        }
        if (vectorColumn == null || vectorColumn.isBlank()) {
            throw new IllegalArgumentException("Vector column name must not be blank");
        }
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("Query vector must not be null or empty");
        }
    }
}
