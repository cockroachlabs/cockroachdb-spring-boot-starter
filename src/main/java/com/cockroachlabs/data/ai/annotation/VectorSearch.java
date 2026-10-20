package com.cockroachlabs.data.ai.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import com.cockroachlabs.data.ai.DistanceMetric;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface VectorSearch {

    /**
     * Table containing the vector column.
     */
    String table();

    /**
     * Column name storing the vector embeddings.
     */
    String vectorColumn() default "embedding";

    /**
     * Distance metric for similarity comparison.
     */
    DistanceMetric metric() default DistanceMetric.COSINE;

    /**
     * Maximum number of results to return.
     */
    int limit() default 10;

    /**
     * Name of the method parameter that holds the query vector (float[]).
     */
    String queryParam() default "queryVector";

    /**
     * Columns to SELECT alongside the distance. Empty = all columns.
     */
    String[] columns() default {};

    /**
     * Run inside a read-only transaction for consistency.
     */
    boolean readOnly() default true;

    /**
     * Beam size hint for vector search accuracy (maps to
     * {@code SET LOCAL vector_search_beam_size}). 0 = use database default.
     */
    int beamSize() default 0;

    /**
     * Prefix columns for the vector index (CockroachDB v26.2+).
     * <p>
     * Prefix columns enable pre-filtering in the vector index itself,
     * avoiding a full scan. They must appear before the vector column
     * in the {@code CREATE VECTOR INDEX} statement.
     * <p>
     * Example: {@code prefixColumns = {"tenant_id"}} generates:
     * <pre>CREATE VECTOR INDEX ON table (tenant_id, embedding vector_cosine_ops)</pre>
     */
    String[] prefixColumns() default {};
}
