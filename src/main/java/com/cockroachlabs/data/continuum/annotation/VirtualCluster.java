package com.cockroachlabs.data.continuum.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Tags the annotated method's work for a Cockroach vCluster so it is isolated on
 * shared capacity. The vCluster name is applied as {@code SET LOCAL
 * application_name} for the transaction, so the workload is attributable in the
 * DB Console and logs.
 *
 * <p>Combine with {@link Workload} to also assign a priority/QoS class.
 *
 * <p><b>Scope (v1):</b> workload <em>tagging</em>. Routing to a distinct vCluster
 * endpoint is platform-dependent and is not performed by the starter.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface VirtualCluster {

    /** The Cockroach vCluster / workload name to tag this work with. */
    String value();
}
