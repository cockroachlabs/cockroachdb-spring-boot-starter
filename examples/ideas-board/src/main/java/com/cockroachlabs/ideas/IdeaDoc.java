package com.cockroachlabs.ideas;

import com.cockroachlabs.data.ai.annotation.VectorColumn;
import java.util.UUID;

/**
 * A searchable idea document. The {@code embedding} is a small, hand-built vector —
 * no embedding model is involved — so similarity search is fully deterministic.
 */
// tag::vector-entity[]
public record IdeaDoc(
        UUID id,
        String text,
        @VectorColumn(dimensions = 3) float[] embedding) {
}
// end::vector-entity[]
