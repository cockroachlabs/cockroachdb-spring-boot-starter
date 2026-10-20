package com.cockroachlabs.data.ai.springai;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.cockroachlabs.data.ai.DistanceMetric;
import com.cockroachlabs.data.ai.VectorSearchTemplate;
import com.cockroachlabs.data.ai.metrics.AIMetrics;
import com.cockroachlabs.data.ddl.VectorIndexHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class CockroachDBVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(CockroachDBVectorStore.class);

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final PlatformTransactionManager txManager;
    private final AIMetrics metrics;
    private final CockroachDBFilterExpressionConverter filterConverter;

    private final String tableName;
    private final String contentColumn;
    private final String embeddingColumn;
    private final String metadataColumn;
    private final DistanceMetric distanceMetric;
    private final int beamSize;
    private final boolean initializeSchema;
    private final boolean createVectorIndex;
    private final int dimensions;

    private CockroachDBVectorStore(Builder builder) {
        this.jdbcTemplate = builder.jdbcTemplate;
        this.embeddingModel = builder.embeddingModel;
        this.txManager = builder.txManager;
        this.metrics = builder.metrics;
        this.filterConverter = builder.filterConverter;
        this.tableName = builder.tableName;
        this.contentColumn = builder.contentColumn;
        this.embeddingColumn = builder.embeddingColumn;
        this.metadataColumn = builder.metadataColumn;
        this.distanceMetric = builder.distanceMetric;
        this.beamSize = builder.beamSize;
        this.initializeSchema = builder.initializeSchema;
        this.createVectorIndex = builder.createVectorIndex;
        this.dimensions = builder.dimensions;
    }

    public void afterPropertiesSet() {
        if (initializeSchema) {
            int dims = dimensions > 0 ? dimensions : embeddingModel.dimensions();
            log.info("Initializing CockroachDB vector store table '{}' with {} dimensions",
                    tableName, dims);
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                            + "id TEXT PRIMARY KEY, "
                            + contentColumn + " TEXT, "
                            + metadataColumn + " JSONB DEFAULT '{}'::jsonb, "
                            + embeddingColumn + " VECTOR(" + dims + ")"
                            + ")");

            if (createVectorIndex) {
                String indexDdl = VectorIndexHelper.generateCreateVectorIndex(
                        "idx_" + tableName + "_" + embeddingColumn,
                        tableName, embeddingColumn, distanceMetric);
                log.info("Creating vector index: {}", indexDdl);
                try {
                    jdbcTemplate.execute(indexDdl);
                } catch (Exception ex) {
                    log.warn("Could not create vector index (may require "
                            + "feature.vector_index.enabled=true): {}", ex.getMessage());
                }
            }
        }
    }

    @Override
    public void add(List<Document> documents) {
        long start = System.nanoTime();
        try {
            TransactionTemplate txTemplate = new TransactionTemplate(txManager);
            txTemplate.executeWithoutResult(status -> {
                for (Document doc : documents) {
                    float[] embedding = embeddingModel.embed(doc);
                    String vectorLiteral = VectorSearchTemplate.toVectorLiteral(embedding);

                    String metadataJson = toJsonb(doc.getMetadata());

                    jdbcTemplate.update(
                            "INSERT INTO " + tableName
                                    + " (id, " + contentColumn + ", " + metadataColumn + ", " + embeddingColumn + ")"
                                    + " VALUES (?, ?, ?::jsonb, '" + vectorLiteral + "'::vector)"
                                    + " ON CONFLICT (id) DO UPDATE SET "
                                    + contentColumn + " = EXCLUDED." + contentColumn + ", "
                                    + metadataColumn + " = EXCLUDED." + metadataColumn + ", "
                                    + embeddingColumn + " = EXCLUDED." + embeddingColumn,
                            doc.getId(), doc.getText(), metadataJson);
                }
            });
            metrics.recordVectorSearch(start, documents.size(), true);
            log.debug("Added {} documents to '{}'", documents.size(), tableName);
        } catch (Exception ex) {
            metrics.recordVectorSearch(start, 0, false);
            throw ex;
        }
    }

    @Override
    public void delete(List<String> idList) {
        if (idList == null || idList.isEmpty()) {
            return;
        }
        String placeholders = idList.stream()
                .map(id -> "?")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        jdbcTemplate.update(
                "DELETE FROM " + tableName + " WHERE id IN (" + placeholders + ")",
                idList.toArray());
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        if (filterExpression == null) {
            return;
        }
        String whereClause = filterConverter.convertExpression(filterExpression);
        if (!whereClause.isBlank()) {
            jdbcTemplate.update("DELETE FROM " + tableName + " WHERE " + whereClause);
        }
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        long start = System.nanoTime();
        try {
            String query = request.getQuery();
            int topK = request.getTopK();
            double threshold = request.getSimilarityThreshold();

            float[] queryVector = embeddingModel.embed(query);
            String vectorLiteral = VectorSearchTemplate.toVectorLiteral(queryVector);

            TransactionTemplate txTemplate = new TransactionTemplate(txManager);
            txTemplate.setReadOnly(true);

            List<Document> results = txTemplate.execute(status -> {
                if (beamSize > 0) {
                    jdbcTemplate.execute(
                            "SET LOCAL vector_search_beam_size = " + beamSize);
                }

                String distanceExpr = embeddingColumn + " " + distanceMetric.operator()
                        + " '" + vectorLiteral + "'::vector";

                StringBuilder sql = new StringBuilder();
                sql.append("SELECT id, ").append(contentColumn).append(", ")
                   .append(metadataColumn).append(", ")
                   .append(distanceExpr).append(" AS distance FROM ").append(tableName);

                String filterWhere = buildFilterWhere(request);
                if (!filterWhere.isBlank()) {
                    sql.append(" WHERE ").append(filterWhere);
                }

                sql.append(" ORDER BY ").append(distanceExpr)
                   .append(" LIMIT ").append(topK);

                return jdbcTemplate.query(sql.toString(), (rs, rowNum) ->
                        mapRowToDocument(rs, threshold));
            });

            List<Document> filtered = results != null
                    ? results.stream().filter(d -> d != null).toList()
                    : List.of();

            metrics.recordVectorSearch(start, filtered.size(), true);
            return filtered;
        } catch (Exception ex) {
            metrics.recordVectorSearch(start, 0, false);
            throw ex;
        }
    }

    @Override
    public String getName() {
        return "CockroachDBVectorStore";
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getNativeClient() {
        return (Optional<T>) Optional.of(jdbcTemplate);
    }

    private String buildFilterWhere(SearchRequest request) {
        Filter.Expression filterExpression = request.getFilterExpression();
        if (filterExpression == null) {
            return "";
        }
        return filterConverter.convertExpression(filterExpression);
    }

    private Document mapRowToDocument(ResultSet rs, double threshold) throws SQLException {
        double distance = rs.getDouble("distance");
        double similarity = distanceToSimilarity(distance);

        if (threshold > 0 && similarity < threshold) {
            return null;
        }

        String id = rs.getString("id");
        String content = rs.getString(contentColumn);
        String metadataJson = rs.getString(metadataColumn);

        Map<String, Object> metadata = parseJsonb(metadataJson);
        metadata.put("distance", distance);
        metadata.put("similarity", similarity);

        return Document.builder()
                .id(id)
                .text(content)
                .metadata(metadata)
                .score(similarity)
                .build();
    }

    private double distanceToSimilarity(double distance) {
        return switch (distanceMetric) {
            case COSINE -> 1.0 - distance;
            case INNER_PRODUCT -> -distance;
            case L2 -> 1.0 / (1.0 + distance);
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonb(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return new HashMap<>();
        }
        try {
            var mapper = new tools.jackson.databind.ObjectMapper();
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse metadata JSON: {}", json, e);
            return new HashMap<>();
        }
    }

    private String toJsonb(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        try {
            var mapper = new tools.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(metadata);
        } catch (Exception e) {
            log.warn("Failed to serialize metadata to JSON", e);
            return "{}";
        }
    }

    public static Builder builder(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return new Builder(jdbcTemplate, embeddingModel);
    }

    public static class Builder {

        private final JdbcTemplate jdbcTemplate;
        private final EmbeddingModel embeddingModel;
        private PlatformTransactionManager txManager;
        private AIMetrics metrics = new AIMetrics.Noop();
        private CockroachDBFilterExpressionConverter filterConverter =
                new CockroachDBFilterExpressionConverter();

        private String tableName = "vector_store";
        private String contentColumn = "content";
        private String embeddingColumn = "embedding";
        private String metadataColumn = "metadata";
        private DistanceMetric distanceMetric = DistanceMetric.COSINE;
        private int beamSize = 0;
        private boolean initializeSchema = false;
        private boolean createVectorIndex = false;
        private int dimensions = 0;

        private Builder(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
            this.jdbcTemplate = jdbcTemplate;
            this.embeddingModel = embeddingModel;
        }

        public Builder transactionManager(PlatformTransactionManager txManager) {
            this.txManager = txManager;
            return this;
        }

        public Builder metrics(AIMetrics metrics) {
            this.metrics = metrics;
            return this;
        }

        public Builder filterConverter(CockroachDBFilterExpressionConverter filterConverter) {
            this.filterConverter = filterConverter;
            return this;
        }

        public Builder tableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        public Builder contentColumn(String contentColumn) {
            this.contentColumn = contentColumn;
            return this;
        }

        public Builder embeddingColumn(String embeddingColumn) {
            this.embeddingColumn = embeddingColumn;
            return this;
        }

        public Builder metadataColumn(String metadataColumn) {
            this.metadataColumn = metadataColumn;
            return this;
        }

        public Builder distanceMetric(DistanceMetric distanceMetric) {
            this.distanceMetric = distanceMetric;
            return this;
        }

        public Builder beamSize(int beamSize) {
            this.beamSize = beamSize;
            return this;
        }

        public Builder initializeSchema(boolean initializeSchema) {
            this.initializeSchema = initializeSchema;
            return this;
        }

        /**
         * When true and {@code initializeSchema} is also true, a
         * {@code CREATE VECTOR INDEX} is executed after table creation (v26.2+).
         */
        public Builder createVectorIndex(boolean createVectorIndex) {
            this.createVectorIndex = createVectorIndex;
            return this;
        }

        public Builder dimensions(int dimensions) {
            this.dimensions = dimensions;
            return this;
        }

        public CockroachDBVectorStore build() {
            return new CockroachDBVectorStore(this);
        }
    }
}
