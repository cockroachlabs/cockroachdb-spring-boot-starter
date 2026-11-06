package com.cockroachlabs.ideas;

import com.cockroachlabs.data.ai.DistanceMetric;
import com.cockroachlabs.data.ai.VectorSearchResult;
import com.cockroachlabs.data.ai.VectorSearchTemplate;
import com.cockroachlabs.data.ddl.VectorIndexHelper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Stores idea documents with a small hand-built embedding and finds the most similar
 * ones by cosine distance, using the starter's {@link VectorSearchTemplate} directly.
 * No embedding model and no Spring AI dependency are required.
 */
public class SemanticIdeaService {

    static final String TABLE = "idea_docs";
    static final String VECTOR_COLUMN = "embedding";
    static final int DIMENSIONS = 3;

    private static final Logger log = LoggerFactory.getLogger(SemanticIdeaService.class);

    private final VectorSearchTemplate vectorSearch;
    private final JdbcTemplate jdbc;

    public SemanticIdeaService(VectorSearchTemplate vectorSearch, JdbcTemplate jdbc) {
        this.vectorSearch = vectorSearch;
        this.jdbc = jdbc;
    }

    // tag::vector-schema[]
    /**
     * Create the vector table and a C-SPANN vector index. The index is best-effort:
     * if the CockroachDB image does not support {@code CREATE VECTOR INDEX}, similarity
     * search falls back to an exact brute-force ORDER BY, so results stay correct.
     */
    public void ensureSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "id UUID PRIMARY KEY, "
                + "text STRING NOT NULL, "
                + VECTOR_COLUMN + " VECTOR(" + DIMENSIONS + "))");
        try {
            jdbc.execute(VectorIndexHelper.generateCreateVectorIndex(
                    "idea_docs_embedding_idx", TABLE, VECTOR_COLUMN, DistanceMetric.COSINE));
        } catch (RuntimeException ex) {
            log.warn("Vector index not created ({}); using an exact brute-force scan.",
                    ex.getMessage());
        }
    }
    // end::vector-schema[]

    // tag::vector-search[]
    /** Store an idea document and its embedding (upsert on id). */
    public void store(IdeaDoc doc) {
        vectorSearch.storeEmbeddingWithMetadata(
                TABLE, "id", doc.id(), VECTOR_COLUMN, doc.embedding(),
                Map.of("text", doc.text()));
    }

    /** Return the {@code k} stored ideas most similar to {@code queryVector}, nearest first. */
    public List<VectorSearchResult<Object>> findSimilar(float[] queryVector, int k) {
        return vectorSearch.search(
                TABLE, VECTOR_COLUMN, queryVector, DistanceMetric.COSINE, k,
                List.of("id", "text"), "", 0);
    }
    // end::vector-search[]
}
