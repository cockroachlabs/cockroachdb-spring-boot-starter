package com.cockroachlabs.data.ai.springai;

import com.cockroachlabs.data.ai.DistanceMetric;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cockroachdb.ai.spring-ai")
public class CockroachDBVectorStoreProperties {

    private String tableName = "vector_store";

    private int dimensions = 0;

    private DistanceMetric distanceType = DistanceMetric.COSINE;

    private int beamSize = 0;

    private boolean initializeSchema = false;

    /**
     * When true and {@code initializeSchema} is also true, a
     * {@code CREATE VECTOR INDEX} is executed after table creation (v26.2+).
     */
    private boolean createVectorIndex = false;

    private String contentColumn = "content";

    private String embeddingColumn = "embedding";

    private String metadataColumn = "metadata";

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }

    public DistanceMetric getDistanceType() {
        return distanceType;
    }

    public void setDistanceType(DistanceMetric distanceType) {
        this.distanceType = distanceType;
    }

    public int getBeamSize() {
        return beamSize;
    }

    public void setBeamSize(int beamSize) {
        this.beamSize = beamSize;
    }

    public boolean isInitializeSchema() {
        return initializeSchema;
    }

    public void setInitializeSchema(boolean initializeSchema) {
        this.initializeSchema = initializeSchema;
    }

    public boolean isCreateVectorIndex() {
        return createVectorIndex;
    }

    public void setCreateVectorIndex(boolean createVectorIndex) {
        this.createVectorIndex = createVectorIndex;
    }

    public String getContentColumn() {
        return contentColumn;
    }

    public void setContentColumn(String contentColumn) {
        this.contentColumn = contentColumn;
    }

    public String getEmbeddingColumn() {
        return embeddingColumn;
    }

    public void setEmbeddingColumn(String embeddingColumn) {
        this.embeddingColumn = embeddingColumn;
    }

    public String getMetadataColumn() {
        return metadataColumn;
    }

    public void setMetadataColumn(String metadataColumn) {
        this.metadataColumn = metadataColumn;
    }
}
