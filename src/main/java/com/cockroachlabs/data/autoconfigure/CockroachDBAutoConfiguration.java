package com.cockroachlabs.data.autoconfigure;

import javax.sql.DataSource;
import com.cockroachlabs.data.annotation.EnableCockroachDBTransactionManagement;
import com.cockroachlabs.data.aop.FollowerReadAspect;
import com.cockroachlabs.data.metrics.CockroachDBMetrics;
import com.cockroachlabs.data.multiregion.HeaderRegionResolver;
import com.cockroachlabs.data.multiregion.RegionContextFilter;
import com.cockroachlabs.data.multiregion.RegionResolver;
import com.cockroachlabs.data.multiregion.StaticRegionResolver;
import com.cockroachlabs.data.template.CockroachDBTransactionTemplate;
import com.cockroachlabs.data.transaction.CockroachDBRegions;
import com.cockroachlabs.data.transaction.CockroachDBTransactionManagementConfiguration;
import com.cockroachlabs.data.transaction.SerializationRetry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Role;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.config.TransactionManagementConfigUtils;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

// After Boot's TransactionAutoConfiguration, which enables Spring's transaction advisor that the
// retry advisor wraps. Named rather than referenced: spring-boot-transaction is optional here.
@AutoConfiguration(after = CockroachDBMetricsAutoConfiguration.class,
        afterName = "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration")
@ConditionalOnClass(DataSource.class)
@EnableConfigurationProperties(CockroachDBProperties.class)
@ImportRuntimeHints(CockroachDBRuntimeHints.class)
@RegisterReflectionForBinding({CockroachDBProperties.class, CockroachDBProperties.Retry.class, CockroachDBProperties.Region.class,
        CockroachDBProperties.Multiregion.class})
public class CockroachDBAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CockroachDBMetrics cockroachDBMetrics() {
        return new CockroachDBMetrics.InMemory();
    }

    @Bean
    @ConditionalOnMissingBean
    public JdbcTemplate cockroachDBJdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(RegionResolver.class)
    public RegionResolver regionResolver(CockroachDBProperties props) {
        CockroachDBProperties.Region regionProps = props.getRegion();
        return switch (regionProps.getResolver()) {
            case STATIC -> new StaticRegionResolver(regionProps.getDefaultRegion());
            case HEADER -> new HeaderRegionResolver(regionProps.getHeaderName());
            case CONTEXT, BEAN -> () -> null;
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public SerializationRetry cockroachDBSerializationRetry(CockroachDBProperties props,
                                                            CockroachDBMetrics metrics) {
        return new SerializationRetry(props.getRetry(), metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public CockroachDBRegions cockroachDBRegions(CockroachDBProperties props,
                                                 RegionResolver regionResolver) {
        return new CockroachDBRegions(props.getRegion(), regionResolver);
    }

    /**
     * Registers {@link CockroachDBRegions} with every transaction manager that accepts
     * execution listeners, so transactions begun by retried methods get their session region.
     * The listener looks up {@code CockroachDBRegions} lazily, on first use.
     */
    @Bean
    static BeanPostProcessor cockroachDBRegionsListenerRegistrar(ObjectProvider<CockroachDBRegions> regions) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof ConfigurableTransactionManager transactionManager) {
                    transactionManager.addListener(new TransactionExecutionListener() {
                        @Override
                        public void afterBegin(TransactionExecution transaction, Throwable beginFailure) {
                            CockroachDBRegions listener = regions.getIfAvailable();
                            if (listener != null) {
                                listener.afterBegin(transaction, beginFailure);
                            }
                        }
                    });
                }
                return bean;
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public CockroachDBTransactionTemplate cockroachDBTransactionTemplate(
            PlatformTransactionManager txManager,
            SerializationRetry retry,
            CockroachDBRegions regions) {
        return new CockroachDBTransactionTemplate(txManager, retry, regions);
    }

    /**
     * Applies {@link EnableCockroachDBTransactionManagement @EnableCockroachDBTransactionManagement}
     * with its defaults, the way Spring Boot applies {@code @EnableTransactionManagement}: only when
     * Spring's transaction advisor is there to be wrapped, and not when the application declares
     * the annotation itself.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(name = TransactionManagementConfigUtils.TRANSACTION_ADVISOR_BEAN_NAME)
    @ConditionalOnMissingBean(CockroachDBTransactionManagementConfiguration.class)
    @EnableCockroachDBTransactionManagement
    static class EnableCockroachDBTransactionManagementConfiguration {
    }

    @Bean
    @ConditionalOnMissingBean
    public FollowerReadAspect followerReadAspect(
            PlatformTransactionManager txManager,
            JdbcTemplate jdbcTemplate,
            CockroachDBMetrics metrics) {
        return new FollowerReadAspect(txManager, jdbcTemplate, metrics);
    }

    /**
     * The region context filter extends OncePerRequestFilter (a spring-web type).
     * Declaring it in a nested, class-gated configuration means non-web consumers
     * never introspect its bean-method signature, so the starter loads cleanly even
     * when spring-web is absent from the classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication
    @ConditionalOnClass(name = "org.springframework.web.filter.OncePerRequestFilter")
    static class RegionWebConfiguration {

        @Bean
        @ConditionalOnMissingBean(RegionContextFilter.class)
        public RegionContextFilter regionContextFilter(
                CockroachDBProperties props,
                RegionResolver regionResolver) {
            return new RegionContextFilter(props, regionResolver);
        }
    }
}
