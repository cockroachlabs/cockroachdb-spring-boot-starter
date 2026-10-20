package com.cockroachlabs.data.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.transaction.annotation.Isolation;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CockroachDB {

    int maxRetries() default -1;

    long delay() default -1;

    double multiplier() default -1;

    long maxDelay() default -1;

    /**
     * Target region for REGIONAL BY ROW operations.
     * Executes SET crdb_region at session start.
     * Empty = use RegionContext, RegionResolver, or gateway default.
     */
    String region() default "";

    Isolation isolation() default Isolation.SERIALIZABLE;

    boolean readOnly() default false;
}
