package com.cockroachlabs.data.cdc;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record CDCEvent<T>(
        @Nullable T after,
        @Nullable T before,
        @Nullable List<@Nullable Object> key,
        CDCOperationType operation,
        @Nullable String table,
        @Nullable Instant timestamp
) {

    public boolean isInsert() {
        return operation == CDCOperationType.INSERT;
    }

    public boolean isUpdate() {
        return operation == CDCOperationType.UPDATE;
    }

    public boolean isDelete() {
        return operation == CDCOperationType.DELETE;
    }
}
