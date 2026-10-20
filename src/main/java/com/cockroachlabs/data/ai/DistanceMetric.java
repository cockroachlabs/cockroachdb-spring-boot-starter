package com.cockroachlabs.data.ai;

/**
 * Distance metrics for CockroachDB vector similarity search.
 * <p>
 * Each metric maps to a CockroachDB distance operator and an opclass name
 * used in {@code CREATE VECTOR INDEX} DDL (CockroachDB v26.2+).
 */
public enum DistanceMetric {

    L2("<->", "vector_l2_ops"),

    COSINE("<=>", "vector_cosine_ops"),

    INNER_PRODUCT("<#>", "vector_ip_ops");

    private final String operator;
    private final String opclass;

    DistanceMetric(String operator, String opclass) {
        this.operator = operator;
        this.opclass = opclass;
    }

    public String operator() {
        return operator;
    }

    /**
     * Returns the CockroachDB opclass name for use in vector index DDL.
     * Example: {@code vector_cosine_ops}, {@code vector_l2_ops}, {@code vector_ip_ops}.
     */
    public String opclass() {
        return opclass;
    }
}
