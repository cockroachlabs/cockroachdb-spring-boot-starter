package com.cockroachlabs.data.cdc.handler;

import com.cockroachlabs.data.cdc.CDCEnvelope;
import com.cockroachlabs.data.cdc.CDCOperationType;
import com.cockroachlabs.data.cdc.autoconfigure.CockroachDBCDCProperties;
import com.cockroachlabs.data.cdc.metrics.CDCMetrics;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CDCWebhookController {

    private static final Logger log = LoggerFactory.getLogger(CDCWebhookController.class);

    private final CDCHandlerRegistry registry;
    private final CDCMethodInvoker invoker;
    private final CockroachDBCDCProperties properties;
    private final ObjectMapper objectMapper;
    private final CDCMetrics metrics;

    public CDCWebhookController(CDCHandlerRegistry registry,
                                 CDCMethodInvoker invoker,
                                 CockroachDBCDCProperties properties,
                                 ObjectMapper objectMapper,
                                 CDCMetrics metrics) {
        this.registry = registry;
        this.invoker = invoker;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @PostMapping("${cockroachdb.cdc.base-path:/cdc}/{*endpoint}")
    public ResponseEntity<Void> handleWebhook(
            @PathVariable String endpoint,
            @RequestBody String body,
            @RequestHeader(value = "${cockroachdb.cdc.auth-header:Authorization}",
                           required = false) String authHeader) {

        String normalizedEndpoint = "/" + endpoint;
        metrics.recordWebhookReceived(normalizedEndpoint, body.length());

        if (!authenticateRequest(authHeader)) {
            log.warn("CDC webhook auth failed for endpoint: {}", endpoint);
            metrics.recordAuthFailure(normalizedEndpoint);
            return ResponseEntity.status(401).build();
        }

        CDCHandlerRegistry.HandlerDescriptor handler = registry.getHandler(normalizedEndpoint);
        if (handler == null) {
            log.warn("No CDC handler registered for endpoint: {}", normalizedEndpoint);
            return ResponseEntity.notFound().build();
        }

        try {
            CDCEnvelope envelope = objectMapper.readValue(body, CDCEnvelope.class);

            if (envelope.isResolved() && !envelope.hasPayload()) {
                log.debug("CDC resolved timestamp for {}: {}", normalizedEndpoint, envelope.resolved());
                metrics.recordResolved(normalizedEndpoint);
                return ResponseEntity.ok().build();
            }

            long startNanos = System.nanoTime();
            invoker.invoke(handler, envelope);
            long durationNanos = System.nanoTime() - startNanos;

            int eventCount = envelope.payload() != null ? envelope.payload().size() : 1;
            CDCOperationType op = envelope.hasPayload()
                    ? envelope.payload().getFirst().resolveOperation()
                    : CDCOperationType.INSERT;
            for (int i = 0; i < eventCount; i++) {
                metrics.recordEvent(normalizedEndpoint, op, durationNanos / eventCount, true);
            }

            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("CDC webhook processing failed for endpoint: {}", normalizedEndpoint, e);
            metrics.recordEvent(normalizedEndpoint, CDCOperationType.INSERT, 0, false);
            return ResponseEntity.internalServerError().build();
        }
    }

    private boolean authenticateRequest(String authHeader) {
        String expectedToken = properties.getAuthToken();
        if (expectedToken == null || expectedToken.isBlank()) {
            return true;
        }
        return expectedToken.equals(authHeader);
    }
}
