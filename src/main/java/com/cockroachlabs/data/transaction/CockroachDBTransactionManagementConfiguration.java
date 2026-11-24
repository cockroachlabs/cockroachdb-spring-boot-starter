package com.cockroachlabs.data.transaction;

import java.util.Map;
import com.cockroachlabs.data.annotation.EnableCockroachDBTransactionManagement;
import com.cockroachlabs.data.autoconfigure.CockroachDBProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportAware;
import org.springframework.context.annotation.Role;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttributeSource;

/**
 * Registers the retry advisor and interceptor for
 * {@link EnableCockroachDBTransactionManagement @EnableCockroachDBTransactionManagement}, the way
 * Spring's {@code ProxyTransactionManagementConfiguration} registers its transaction advisor for
 * {@code @EnableTransactionManagement}.
 * <p>
 * The interceptor looks up its collaborators ({@link SerializationRetry}, {@link CockroachDBRegions}
 * and {@link CockroachDBProperties}, provided by the starter's auto-configuration) on first use, so
 * creating the advisor early, while bean post-processors are being registered, doesn't create them
 * too early to be post-processed.
 */
@Configuration(proxyBeanMethods = false)
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class CockroachDBTransactionManagementConfiguration implements ImportAware {

    private int order = CockroachDBTransactionAdvisor.ORDER;

    @Override
    public void setImportMetadata(AnnotationMetadata importMetadata) {
        Map<String, @Nullable Object> attributes =
                importMetadata.getAnnotationAttributes(EnableCockroachDBTransactionManagement.class.getName());
        if (attributes != null && attributes.get("order") instanceof Integer order) {
            this.order = order;
        }
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    CockroachDBTransactionInterceptor cockroachDBTransactionInterceptor(
            ObjectProvider<SerializationRetry> retry,
            ObjectProvider<CockroachDBRegions> regions,
            ObjectProvider<CockroachDBProperties> properties) {
        return new CockroachDBTransactionInterceptor(retry::getObject, regions::getObject,
                () -> properties.getObject().getRetry().isApplyToTransactionalMethods());
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    CockroachDBTransactionAdvisor cockroachDBTransactionAdvisor(
            ObjectProvider<TransactionAttributeSource> transactionAttributeSource,
            CockroachDBTransactionInterceptor interceptor) {
        CockroachDBTransactionAdvisor advisor = new CockroachDBTransactionAdvisor(
                transactionAttributeSource.getIfAvailable(AnnotationTransactionAttributeSource::new),
                interceptor);
        advisor.setOrder(order);
        return advisor;
    }
}
