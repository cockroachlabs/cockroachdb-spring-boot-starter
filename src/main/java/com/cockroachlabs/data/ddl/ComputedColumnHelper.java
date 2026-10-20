package com.cockroachlabs.data.ddl;

import com.cockroachlabs.data.annotation.ComputedColumn;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates CockroachDB DDL for computed columns annotated with
 * {@link ComputedColumn @ComputedColumn}.
 */
public final class ComputedColumnHelper {

    private ComputedColumnHelper() {}

    /**
     * Generate {@code ALTER TABLE ADD COLUMN ... AS (expr) STORED} DDL
     * for all {@link ComputedColumn @ComputedColumn}-annotated fields.
     *
     * @param tableName   the SQL table name
     * @param entityClass the JPA entity class to scan
     * @return list of DDL statements (may be empty)
     * @throws IllegalArgumentException if any field has {@code stored = false}
     */
    public static List<String> generateDDL(String tableName, Class<?> entityClass) {
        List<String> statements = new ArrayList<>();
        for (Field field : entityClass.getDeclaredFields()) {
            ComputedColumn cc = field.getAnnotation(ComputedColumn.class);
            if (cc != null) {
                if (!cc.stored()) {
                    throw new IllegalArgumentException(
                            "CockroachDB computed columns must be stored. "
                                    + "Field: " + field.getName());
                }
                String colName = camelToSnake(field.getName());
                String sqlType = javaTypeToSql(field.getType());
                statements.add(String.format(
                        "ALTER TABLE %s ADD COLUMN IF NOT EXISTS %s %s AS (%s) STORED",
                        tableName, colName, sqlType, cc.expression()));
            }
        }
        return statements;
    }

    /**
     * Validate that all {@link ComputedColumn @ComputedColumn} annotations
     * on the given class use {@code stored = true}.
     *
     * @throws IllegalArgumentException if any field has {@code stored = false}
     */
    public static void validate(Class<?> entityClass) {
        for (Field field : entityClass.getDeclaredFields()) {
            ComputedColumn cc = field.getAnnotation(ComputedColumn.class);
            if (cc != null && !cc.stored()) {
                throw new IllegalArgumentException(
                        "CockroachDB does not support virtual computed columns. "
                                + "Field '" + field.getName() + "' has stored=false.");
            }
        }
    }

    static String javaTypeToSql(Class<?> type) {
        if (type == Integer.class || type == int.class) return "INT8";
        if (type == Long.class || type == long.class) return "INT8";
        if (type == Double.class || type == double.class) return "FLOAT8";
        if (type == Float.class || type == float.class) return "FLOAT4";
        if (type == Boolean.class || type == boolean.class) return "BOOL";
        if (type == String.class) return "STRING";
        if (type == java.math.BigDecimal.class) return "DECIMAL";
        return "INT8";
    }

    static String camelToSnake(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
}
