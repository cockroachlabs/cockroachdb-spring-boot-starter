package com.cockroachlabs.data.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs the method in its own read-only transaction with
 * {@code SET TRANSACTION AS OF SYSTEM TIME ...}, so every query it makes reads a
 * consistent snapshot from the past that the nearest replica can serve.
 *
 * <p>With nothing set (the default), it reads at {@code follower_read_timestamp()}, the
 * most recent time any replica can serve (about 4.2s ago). Otherwise the parts add up,
 * so {@code @FollowerRead(hoursAgo = 1, minutesAgo = 30)} reads as of 1h30m ago.
 *
 * <p>A time more recent than {@code follower_read_timestamp()} still reads historical
 * data, but is served by the leaseholder rather than the nearest replica. A negative
 * part fails when the method is called.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FollowerRead {

    long hoursAgo() default 0;

    long minutesAgo() default 0;

    long secondsAgo() default 0;
}
