package com.cockroachlabs.data.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FollowerRead {

    /**
     * Name of the boolean method parameter that controls follower reads.
     * When that parameter is {@code true}, the method runs inside a read-only
     * transaction with {@code AS OF SYSTEM TIME follower_read_timestamp()}.
     * Set to empty string to always use follower reads.
     */
    String staleParam() default "stale";
}
