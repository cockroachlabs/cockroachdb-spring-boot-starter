package com.cockroachlabs.data.autoconfigure;

import javax.sql.DataSource;
import com.cockroachlabs.data.aop.CockroachDBAspect;
import com.cockroachlabs.data.aop.FollowerReadAspect;
import com.cockroachlabs.data.metrics.CockroachDBMetrics;
import com.cockroachlabs.data.multiregion.HeaderRegionResolver;
import com.cockroachlabs.data.multiregion.RegionContextFilter;
import com.cockroachlabs.data.multiregion.RegionResolver;
import com.cockroachlabs.data.multiregion.StaticRegionResolver;
import com.cockroachlabs.data.template.CockroachDBTemplate;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@AutoConfiguration(after = CockroachDBMetricsAutoConfiguration.class)
@ConditionalOnClass(DataSource.class)
@EnableConfigurationProperties(CockroachDBProperties.class)
@ImportRuntimeHints(CockroachDBRuntimeHints.class)
@RegisterReflectionForBinding({CockroachDBProperties.class, CockroachDBProperties.Retry.class, CockroachDBProperties.Region.class})
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
    public CockroachDBTemplate cockroachDBTemplate(
            PlatformTransactionManager txManager,
            CockroachDBProperties props,
            CockroachDBMetrics metrics,
            JdbcTemplate jdbcTemplate,
            RegionResolver regionResolver) {
        return new CockroachDBTemplate(txManager, props, metrics, jdbcTemplate, regionResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public CockroachDBAspect cockroachDBAspect(
            PlatformTransactionManager txManager,
            CockroachDBProperties props,
            CockroachDBMetrics metrics,
            JdbcTemplate jdbcTemplate,
            RegionResolver regionResolver) {
        return new CockroachDBAspect(txManager, props, metrics, jdbcTemplate, regionResolver);
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
