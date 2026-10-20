package com.cockroachlabs.data.cdc;

import java.time.Instant;
import java.util.List;

public record CDCEvent<T>(
        T after,
        T before,
        List<Object> key,
        CDCOperationType operation,
        String table,
        Instant timestamp
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
