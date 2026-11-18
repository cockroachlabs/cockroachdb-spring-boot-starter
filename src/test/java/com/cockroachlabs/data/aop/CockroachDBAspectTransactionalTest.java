package com.cockroachlabs.data.aop;

import java.io.IOException;
import com.cockroachlabs.data.annotation.CockroachDB;
import com.cockroachlabs.data.autoconfigure.CockroachDBProperties;
import com.cockroachlabs.data.metrics.CockroachDBMetrics;
import com.cockroachlabs.data.template.CockroachDBTransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@code @CockroachDB} attributes behave like their {@code @Transactional} counterparts:
 * they reach the transaction definition, rollback rules decide commit or rollback, a
 * qualifier selects the transaction manager, and a class-level annotation applies to
 * public methods unless the method has its own.
 */
class CockroachDBAspectTransactionalTest {

    public static class Service {

        @CockroachDB
        public void defaults() {
        }

        @CockroachDB(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
                timeout = 7, readOnly = true)
        public void configured() {
        }

        @CockroachDB(timeoutString = "${tx.timeout}")
        public void timeoutFromPlaceholder() {
        }

        @CockroachDB(timeout = 5, timeoutString = "5")
        public void bothTimeouts() {
        }

        @CockroachDB
        public void throwsChecked() throws IOException {
            throw new IOException("checked");
        }

        @CockroachDB
        public void throwsUnchecked() {
            throw new IllegalStateException("unchecked");
        }

        @CockroachDB(rollbackFor = IOException.class)
        public void rollsBackOnChecked() throws IOException {
            throw new IOException("checked");
        }

        @CockroachDB(rollbackForClassName = "IOException")
        public void rollsBackOnCheckedByName() throws IOException {
            throw new IOException("checked");
        }

        @CockroachDB(noRollbackFor = IllegalStateException.class)
        public void commitsOnUnchecked() {
            throw new IllegalStateException("unchecked");
        }

        @CockroachDB("other")
        public void otherManagerByValue() {
        }

        @CockroachDB(transactionManager = "other")
        public void otherManager() {
        }
    }

    @CockroachDB(readOnly = true, timeout = 3)
    public static class ReadOnlyService {

        public void inherited() {
        }

        @CockroachDB
        public void ownAnnotation() {
        }
    }

    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final PlatformTransactionManager otherManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus status = mock(TransactionStatus.class);
    private CockroachDBAspect aspect;

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any())).thenReturn(status);
        when(otherManager.getTransaction(any())).thenReturn(status);

        CockroachDBProperties properties = new CockroachDBProperties();
        aspect = new CockroachDBAspect(new CockroachDBTransactionTemplate(
                txManager, properties, new CockroachDBMetrics.InMemory(), mock(JdbcTemplate.class), null));

        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("other", otherManager);
        beanFactory.addEmbeddedValueResolver(value -> value.replace("${tx.timeout}", "11"));
        aspect.setBeanFactory(beanFactory);
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(aspect);
        return (T) factory.getProxy();
    }

    private TransactionDefinition definition(PlatformTransactionManager manager) {
        ArgumentCaptor<TransactionDefinition> captor = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager).getTransaction(captor.capture());
        return captor.getValue();
    }

    @Test
    void defaultsMatchTransactionalExceptSerializable() {
        proxy(new Service()).defaults();

        TransactionDefinition def = definition(txManager);
        assertThat(def.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(def.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
        assertThat(def.getTimeout()).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT);
        assertThat(def.isReadOnly()).isFalse();
        verify(txManager).commit(status);
    }

    @Test
    void transactionAttributesReachTheDefinition() {
        proxy(new Service()).configured();

        TransactionDefinition def = definition(txManager);
        assertThat(def.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(def.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThat(def.getTimeout()).isEqualTo(7);
        assertThat(def.isReadOnly()).isTrue();
    }

    @Test
    void timeoutStringResolvesPlaceholders() {
        proxy(new Service()).timeoutFromPlaceholder();

        assertThat(definition(txManager).getTimeout()).isEqualTo(11);
    }

    @Test
    void timeoutAndTimeoutStringAreExclusive() {
        assertThatIllegalArgumentException().isThrownBy(() -> proxy(new Service()).bothTimeouts());
    }

    @Test
    void checkedExceptionCommitsAndReachesCallerUnchanged() {
        assertThatThrownBy(() -> proxy(new Service()).throwsChecked())
                .isExactlyInstanceOf(IOException.class).hasMessage("checked");
        verify(txManager).commit(status);
        verify(txManager, never()).rollback(any());
    }

    @Test
    void uncheckedExceptionRollsBack() {
        assertThatIllegalStateException().isThrownBy(() -> proxy(new Service()).throwsUnchecked());
        verify(txManager).rollback(status);
        verify(txManager, never()).commit(any());
    }

    @Test
    void rollbackForRollsBackOnCheckedException() {
        assertThatThrownBy(() -> proxy(new Service()).rollsBackOnChecked())
                .isExactlyInstanceOf(IOException.class);
        verify(txManager).rollback(status);
    }

    @Test
    void rollbackForClassNameRollsBackOnCheckedException() {
        assertThatThrownBy(() -> proxy(new Service()).rollsBackOnCheckedByName())
                .isExactlyInstanceOf(IOException.class);
        verify(txManager).rollback(status);
    }

    @Test
    void noRollbackForCommitsOnUncheckedException() {
        assertThatIllegalStateException().isThrownBy(() -> proxy(new Service()).commitsOnUnchecked());
        verify(txManager).commit(status);
        verify(txManager, never()).rollback(any());
    }

    @Test
    void valueSelectsTransactionManager() {
        proxy(new Service()).otherManagerByValue();

        verify(otherManager).commit(status);
        verifyNoInteractions(txManager);
    }

    @Test
    void transactionManagerSelectsTransactionManager() {
        proxy(new Service()).otherManager();

        verify(otherManager).commit(status);
        verifyNoInteractions(txManager);
    }

    @Test
    void classLevelAnnotationAppliesToPublicMethods() {
        proxy(new ReadOnlyService()).inherited();

        TransactionDefinition def = definition(txManager);
        assertThat(def.isReadOnly()).isTrue();
        assertThat(def.getTimeout()).isEqualTo(3);
    }

    @Test
    void methodAnnotationWinsOverClassAnnotation() {
        proxy(new ReadOnlyService()).ownAnnotation();

        TransactionDefinition def = definition(txManager);
        assertThat(def.isReadOnly()).isFalse();
        assertThat(def.getTimeout()).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT);
    }
}
