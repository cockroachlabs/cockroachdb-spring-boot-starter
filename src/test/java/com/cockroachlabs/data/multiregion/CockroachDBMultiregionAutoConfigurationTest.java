package com.cockroachlabs.data.multiregion;

import com.cockroachlabs.data.autoconfigure.CockroachDBProperties;
import com.cockroachlabs.data.autoconfigure.CockroachDBProperties.Multiregion.Mode;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CockroachDBMultiregionAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CockroachDBMultiregionAutoConfiguration.class));

    @Test
    void onByDefault() {
        runner.run(context -> assertThat(context).hasSingleBean(MultiregionVerifier.class));
    }

    @Test
    void defaultsToWarn() {
        assertThat(new CockroachDBProperties().getMultiregion().getMode()).isEqualTo(Mode.WARN);
    }

    @Test
    void onWithWarn() {
        runner.withPropertyValues("cockroachdb.multiregion.mode=warn")
                .run(context -> assertThat(context).hasSingleBean(MultiregionVerifier.class));
    }

    @Test
    void onWithValidate() {
        runner.withPropertyValues("cockroachdb.multiregion.mode=validate")
                .run(context -> assertThat(context).hasSingleBean(MultiregionVerifier.class));
    }

    @Test
    void noneStartsWithoutEntityManagerFactory() {
        runner.withPropertyValues("cockroachdb.multiregion.mode=none")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void offWithoutHibernate() {
        runner.withPropertyValues("cockroachdb.multiregion.mode=validate")
                .withClassLoader(new FilteredClassLoader(SessionFactoryImplementor.class))
                .run(context -> assertThat(context).doesNotHaveBean(MultiregionVerifier.class));
    }
}
