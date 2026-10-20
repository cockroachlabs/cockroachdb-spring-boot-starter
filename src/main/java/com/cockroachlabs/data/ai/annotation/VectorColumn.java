package com.cockroachlabs.data.ai.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface VectorColumn {

    /**
     * Dimensionality of the vector. Must match the embedding model output.
     * Example: 1536 for OpenAI text-embedding-ada-002, 768 for all-MiniLM-L6-v2.
     */
    int dimensions();
}
