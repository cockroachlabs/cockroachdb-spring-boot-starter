package com.cockroachlabs.data.continuum.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Assigns a Cockroach vCluster workload priority to the annotated method, mapped
 * to CockroachDB admission-control quality-of-service and applied as
 * {@code SET LOCAL default_transaction_quality_of_service} for the transaction.
 *
 * <p>Most useful alongside {@link VirtualCluster}. The aspect fires on
 * {@code @VirtualCluster}; a method carrying {@code @Workload} without
 * {@code @VirtualCluster} should also carry {@code @VirtualCluster} (or use the
 * programmatic {@code VirtualClusterContext}) for the QoS to be applied.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Workload {

    /** The workload priority class. */
    Priority value();

    /** Workload priority classes and their CockroachDB QoS mapping. */
    enum Priority {
        HIGH("critical"),
        NORMAL("regular"),
        LOW("background");

        private final String qos;

        Priority(String qos) {
            this.qos = qos;
        }

        /** The CockroachDB {@code default_transaction_quality_of_service} value. */
        public String qos() {
            return qos;
        }
    }
}
