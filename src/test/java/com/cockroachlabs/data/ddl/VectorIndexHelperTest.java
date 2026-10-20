package com.cockroachlabs.data.ddl;

import com.cockroachlabs.data.ai.DistanceMetric;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit and integration tests for VectorIndexHelper DDL generation (CockroachDB v26.2+).
 */
class VectorIndexHelperTest {

    // --- CREATE VECTOR INDEX DDL generation ---

    @Test
    void generateCreateVectorIndex_cosine_noPrefixColumns() {
        String ddl = VectorIndexHelper.generateCreateVectorIndex(
                "idx_docs_embedding", "documents", "embedding", DistanceMetric.COSINE);

        assertThat(ddl).isEqualTo(
                "CREATE VECTOR INDEX IF NOT EXISTS idx_docs_embedding ON documents (embedding vector_cosine_ops)");
    }

    @Test
    void generateCreateVectorIndex_l2_noPrefixColumns() {
        String ddl = VectorIndexHelper.generateCreateVectorIndex(
                "idx_docs_embedding", "documents", "embedding", DistanceMetric.L2);

        assertThat(ddl).isEqualTo(
                "CREATE VECTOR INDEX IF NOT EXISTS idx_docs_embedding ON documents (embedding vector_l2_ops)");
    }

    @Test
    void generateCreateVectorIndex_innerProduct_noPrefixColumns() {
        String ddl = VectorIndexHelper.generateCreateVectorIndex(
                "idx_docs_embedding", "documents", "embedding", DistanceMetric.INNER_PRODUCT);

        assertThat(ddl).isEqualTo(
                "CREATE VECTOR INDEX IF NOT EXISTS idx_docs_embedding ON documents (embedding vector_ip_ops)");
    }

    @Test
    void generateCreateVectorIndex_withSinglePrefixColumn() {
        String ddl = VectorIndexHelper.generateCreateVectorIndex(
                "idx_chunks_embedding", "chunks", "embedding", DistanceMetric.COSINE,
                "tenant_id");

        assertThat(ddl).isEqualTo(
                "CREATE VECTOR INDEX IF NOT EXISTS idx_chunks_embedding ON chunks (tenant_id, embedding vector_cosine_ops)");
    }

    @Test
    void generateCreateVectorIndex_withMultiplePrefixColumns() {
        String ddl = VectorIndexHelper.generateCreateVectorIndex(
                "idx_chunks_embedding", "chunks", "embedding", DistanceMetric.COSINE,
                "tenant_id", "category");

        assertThat(ddl).isEqualTo(
                "CREATE VECTOR INDEX IF NOT EXISTS idx_chunks_embedding ON chunks (tenant_id, category, embedding vector_cosine_ops)");
    }

    @Test
    void generateCreateVectorIndex_emptyPrefixColumns_ignored() {
        String ddl = VectorIndexHelper.generateCreateVectorIndex(
                "idx_docs_embedding", "documents", "embedding", DistanceMetric.COSINE,
                new String[]{});

        assertThat(ddl).isEqualTo(
                "CREATE VECTOR INDEX IF NOT EXISTS idx_docs_embedding ON documents (embedding vector_cosine_ops)");
    }

    @Test
    void generateCreateVectorIndex_nullPrefixColumns_ignored() {
        String ddl = VectorIndexHelper.generateCreateVectorIndex(
                "idx_docs_embedding", "documents", "embedding", DistanceMetric.COSINE,
                (String[]) null);

        assertThat(ddl).isEqualTo(
                "CREATE VECTOR INDEX IF NOT EXISTS idx_docs_embedding ON documents (embedding vector_cosine_ops)");
    }

    @Test
    void generateCreateVectorIndex_blankPrefixColumns_filtered() {
        String ddl = VectorIndexHelper.generateCreateVectorIndex(
                "idx_docs_embedding", "documents", "embedding", DistanceMetric.COSINE,
                "", "  ", "tenant_id");

        assertThat(ddl).isEqualTo(
                "CREATE VECTOR INDEX IF NOT EXISTS idx_docs_embedding ON documents (tenant_id, embedding vector_cosine_ops)");
    }

    // --- Validation ---

    @Test
    void generateCreateVectorIndex_rejectsBlankIndexName() {
        assertThatThrownBy(() ->
                VectorIndexHelper.generateCreateVectorIndex("", "table", "col", DistanceMetric.COSINE)
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("indexName");
    }

    @Test
    void generateCreateVectorIndex_rejectsBlankTableName() {
        assertThatThrownBy(() ->
                VectorIndexHelper.generateCreateVectorIndex("idx", "", "col", DistanceMetric.COSINE)
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tableName");
    }

    @Test
    void generateCreateVectorIndex_rejectsBlankVectorColumn() {
        assertThatThrownBy(() ->
                VectorIndexHelper.generateCreateVectorIndex("idx", "table", "", DistanceMetric.COSINE)
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vectorColumn");
    }

    // --- Legacy CSPANN index generation ---

    @Test
    void generateCreateCspannIndex_cosine() {
        String ddl = VectorIndexHelper.generateCreateCspannIndex(
                "idx_docs_embedding", "documents", "embedding", DistanceMetric.COSINE);

        assertThat(ddl).isEqualTo(
                "CREATE INDEX IF NOT EXISTS idx_docs_embedding ON documents USING cspann (embedding vector_cosine_ops)");
    }

    @Test
    void generateCreateCspannIndex_l2() {
        String ddl = VectorIndexHelper.generateCreateCspannIndex(
                "idx_docs_embedding", "documents", "embedding", DistanceMetric.L2);

        assertThat(ddl).isEqualTo(
                "CREATE INDEX IF NOT EXISTS idx_docs_embedding ON documents USING cspann (embedding vector_l2_ops)");
    }

    @Test
    void generateCreateCspannIndex_innerProduct() {
        String ddl = VectorIndexHelper.generateCreateCspannIndex(
                "idx_docs_embedding", "documents", "embedding", DistanceMetric.INNER_PRODUCT);

        assertThat(ddl).isEqualTo(
                "CREATE INDEX IF NOT EXISTS idx_docs_embedding ON documents USING cspann (embedding vector_ip_ops)");
    }

    // --- Drop index ---

    @Test
    void generateDropIndex_producesCorrectStatement() {
        String ddl = VectorIndexHelper.generateDropIndex("idx_docs_embedding");

        assertThat(ddl).isEqualTo("DROP INDEX IF EXISTS idx_docs_embedding");
    }

    @Test
    void generateDropIndex_rejectsBlank() {
        assertThatThrownBy(() -> VectorIndexHelper.generateDropIndex(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Cluster setting ---

    @Test
    void enableVectorIndexSetting_returnsCorrectStatement() {
        String sql = VectorIndexHelper.enableVectorIndexSetting();

        assertThat(sql).isEqualTo("SET CLUSTER SETTING feature.vector_index.enabled = true");
    }

    // --- Beam size ---

    @Test
    void setBeamSize_returnsCorrectStatement() {
        String sql = VectorIndexHelper.setBeamSize(32);

        assertThat(sql).isEqualTo("SET LOCAL vector_search_beam_size = 32");
    }

    @Test
    void setBeamSize_rejectsZero() {
        assertThatThrownBy(() -> VectorIndexHelper.setBeamSize(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void setBeamSize_rejectsNegative() {
        assertThatThrownBy(() -> VectorIndexHelper.setBeamSize(-5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- DistanceMetric opclass names ---

    @Test
    void distanceMetric_opclassNames() {
        assertThat(DistanceMetric.COSINE.opclass()).isEqualTo("vector_cosine_ops");
        assertThat(DistanceMetric.L2.opclass()).isEqualTo("vector_l2_ops");
        assertThat(DistanceMetric.INNER_PRODUCT.opclass()).isEqualTo("vector_ip_ops");
    }

    @Test
    void distanceMetric_operators_unchanged() {
        assertThat(DistanceMetric.COSINE.operator()).isEqualTo("<=>");
        assertThat(DistanceMetric.L2.operator()).isEqualTo("<->");
        assertThat(DistanceMetric.INNER_PRODUCT.operator()).isEqualTo("<#>");
    }
}
