package com.cockroachlabs.ideas;

import static org.assertj.core.api.Assertions.assertThat;

import com.cockroachlabs.data.cdc.handler.CDCHandlerRegistry;
import com.cockroachlabs.data.cdc.handler.CDCMethodInvoker;
import com.cockroachlabs.data.cdc.test.CDCTestHarness;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit-tests the CDC handler without a live changefeed. {@link CDCTestHarness} builds a
 * synthetic change event and dispatches it through the same registry/invoker path the
 * webhook controller uses, so no database or HTTP server is required.
 */
class IdeaCdcTest {

    @Test
    void handlerProcessesSyntheticInsert() {
        // tag::cdc-test[]
        IdeaChangefeedHandler handler = new IdeaChangefeedHandler();

        // Register the @CockroachDBCDC method the same way the starter's BeanPostProcessor does.
        CDCHandlerRegistry registry = new CDCHandlerRegistry();
        registry.postProcessAfterInitialization(handler, "ideaChangefeedHandler");

        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        CDCTestHarness harness = new CDCTestHarness(registry, new CDCMethodInvoker(mapper));

        UUID id = UUID.randomUUID();
        harness.simulateInsert("/ideas", new Idea(id, "Serve coffee at standup", 0), id);

        assertThat(handler.processedCount()).isEqualTo(1);
        assertThat(handler.lastNewIdeaText()).isEqualTo("Serve coffee at standup");
        // end::cdc-test[]
    }
}
