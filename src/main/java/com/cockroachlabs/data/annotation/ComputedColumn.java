package com.cockroachlabs.data.annotation;

import java.lang.annotation.*;

/**
 * Marks a field as a CockroachDB computed (generated) column.
 * CockroachDB requires computed columns to be STORED.
 *
 * <pre>{@code
 * @ComputedColumn(expression = "price * quantity")
 * private Integer total;
 * }</pre>
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ComputedColumn {

    /** SQL expression for the computed column. */
    String expression();

    /** Whether the column is stored. CockroachDB requires {@code true}. */
    boolean stored() default true;
}
