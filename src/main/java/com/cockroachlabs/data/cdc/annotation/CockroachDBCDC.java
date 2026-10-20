package com.cockroachlabs.data.cdc.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import com.cockroachlabs.data.cdc.CDCOperationType;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CockroachDBCDC {

    /**
     * Webhook endpoint path relative to the CDC base path.
     * Example: "/orders" registers at {basePath}/orders.
     */
    String endpoint();

    /**
     * Source table name. Used by ChangefeedFactory when creating changefeeds.
     * If empty, inferred from the method's entity type via JPA @Table or class name.
     */
    String table() default "";

    /**
     * Column projections. When set, only these columns are included in the changefeed.
     * The method's parameter type should be a DTO matching the projected columns.
     */
    String[] projections() default {};

    /**
     * Filter which operations this handler processes.
     * Default: all operations (INSERT, UPDATE, DELETE).
     */
    CDCOperationType[] operations() default {};
}
