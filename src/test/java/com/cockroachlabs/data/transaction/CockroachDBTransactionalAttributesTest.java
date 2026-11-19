package com.cockroachlabs.data.transaction;

import java.io.IOException;
import com.cockroachlabs.data.annotation.CockroachDBTransactional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.interceptor.TransactionAttribute;

import static org.assertj.core.api.Assertions.*;

/**
 * {@code @CockroachDBTransactional} is a composed {@code @Transactional}: Spring's own
 * transaction attribute source sees every attribute, with SERIALIZABLE as the one different
 * default.
 */
class CockroachDBTransactionalAttributesTest {

    static class Service {

        @CockroachDBTransactional
        public void defaults() {
        }

        @CockroachDBTransactional(transactionManager = "other", label = "batch",
                propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
                timeout = 7, readOnly = true)
        public void configured() {
        }

        @CockroachDBTransactional("byValue")
        public void qualifierByValue() {
        }

        @CockroachDBTransactional(timeoutString = "9")
        public void timeoutString() {
        }

        @CockroachDBTransactional(rollbackFor = IOException.class, noRollbackFor = IllegalStateException.class)
        public void rollbackRules() {
        }
    }

    @CockroachDBTransactional(readOnly = true)
    static class ClassLevel {

        public void inherited() {
        }
    }

    private final AnnotationTransactionAttributeSource source = new AnnotationTransactionAttributeSource();

    private TransactionAttribute attribute(Class<?> type, String method) throws NoSuchMethodException {
        return source.getTransactionAttribute(type.getMethod(method), type);
    }

    @Test
    void defaultsMatchTransactionalExceptSerializable() throws Exception {
        TransactionAttribute attribute = attribute(Service.class, "defaults");

        assertThat(attribute.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(attribute.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
        assertThat(attribute.getTimeout()).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT);
        assertThat(attribute.isReadOnly()).isFalse();
        assertThat(attribute.getQualifier()).isEmpty();
    }

    @Test
    void transactionalAttributesCarryOver() throws Exception {
        TransactionAttribute attribute = attribute(Service.class, "configured");

        assertThat(attribute.getQualifier()).isEqualTo("other");
        assertThat(attribute.getLabels()).containsExactly("batch");
        assertThat(attribute.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(attribute.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThat(attribute.getTimeout()).isEqualTo(7);
        assertThat(attribute.isReadOnly()).isTrue();
    }

    @Test
    void valueIsTheTransactionManagerQualifier() throws Exception {
        assertThat(attribute(Service.class, "qualifierByValue").getQualifier()).isEqualTo("byValue");
    }

    @Test
    void timeoutStringIsSupported() throws Exception {
        assertThat(attribute(Service.class, "timeoutString").getTimeout()).isEqualTo(9);
    }

    @Test
    void rollbackRulesFollowTransactional() throws Exception {
        TransactionAttribute attribute = attribute(Service.class, "rollbackRules");

        assertThat(attribute.rollbackOn(new IOException())).isTrue();
        assertThat(attribute.rollbackOn(new IllegalStateException())).isFalse();
        assertThat(attribute.rollbackOn(new IllegalArgumentException())).isTrue();
    }

    @Test
    void classLevelAnnotationApplies() throws Exception {
        assertThat(attribute(ClassLevel.class, "inherited").isReadOnly()).isTrue();
    }
}
