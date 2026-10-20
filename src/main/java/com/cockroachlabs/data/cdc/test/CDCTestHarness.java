package com.cockroachlabs.data.cdc.test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.cockroachlabs.data.cdc.CDCEnvelope;
import com.cockroachlabs.data.cdc.CDCEvent;
import com.cockroachlabs.data.cdc.CDCOperationType;
import com.cockroachlabs.data.cdc.CDCPayload;
import com.cockroachlabs.data.cdc.handler.CDCHandlerRegistry;
import com.cockroachlabs.data.cdc.handler.CDCMethodInvoker;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public class CDCTestHarness {

    private final CDCHandlerRegistry registry;
    private final CDCMethodInvoker invoker;
    private final ObjectMapper objectMapper;

    public CDCTestHarness(CDCHandlerRegistry registry, CDCMethodInvoker invoker) {
        this.registry = registry;
        this.invoker = invoker;
        this.objectMapper = JsonMapper.builder().findAndAddModules().build();
    }

    public <T> void simulateInsert(String endpoint, T entity, Object... keyParts) {
        CDCEnvelope envelope = CDCEventBuilder.insertEnvelope(entity, objectMapper, keyParts);
        dispatch(endpoint, envelope);
    }

    public <T> void simulateUpdate(String endpoint, T before, T after, Object... keyParts) {
        CDCEnvelope envelope = CDCEventBuilder.updateEnvelope(before, after, objectMapper, keyParts);
        dispatch(endpoint, envelope);
    }

    public void simulateDelete(String endpoint, Object... keyParts) {
        CDCEnvelope envelope = CDCEventBuilder.deleteEnvelope(objectMapper, keyParts);
        dispatch(endpoint, envelope);
    }

    public void simulateResolved(String endpoint, Instant timestamp) {
        CDCEnvelope envelope = CDCEventBuilder.resolvedEnvelope(timestamp);
        dispatch(endpoint, envelope);
    }

    public <T> void send(String endpoint, CDCEvent<T> event) {
        Map<String, Object> afterMap = event.after() != null
                ? objectMapper.convertValue(event.after(), mapType())
                : null;
        Map<String, Object> beforeMap = event.before() != null
                ? objectMapper.convertValue(event.before(), mapType())
                : null;

        String hlc = event.timestamp() != null
                ? event.timestamp().getEpochSecond() + ".0000000000"
                : null;

        CDCPayload payload = new CDCPayload(afterMap, beforeMap, event.key(), event.table(), hlc);
        CDCEnvelope envelope = new CDCEnvelope(List.of(payload), 1, null);
        dispatch(endpoint, envelope);
    }

    public <T> void sendBatch(String endpoint, List<CDCEvent<T>> events) {
        List<CDCPayload> payloads = events.stream().map(event -> {
            Map<String, Object> afterMap = event.after() != null
                    ? objectMapper.convertValue(event.after(), mapType())
                    : null;
            Map<String, Object> beforeMap = event.before() != null
                    ? objectMapper.convertValue(event.before(), mapType())
                    : null;
            String hlc = event.timestamp() != null
                    ? event.timestamp().getEpochSecond() + ".0000000000"
                    : null;
            return new CDCPayload(afterMap, beforeMap, event.key(), event.table(), hlc);
        }).toList();

        CDCEnvelope envelope = new CDCEnvelope(payloads, payloads.size(), null);
        dispatch(endpoint, envelope);
    }

    public CDCEnvelope buildRawEnvelope(CDCOperationType operation, Object entity,
                                         Object entityBefore, Object... keyParts) {
        return switch (operation) {
            case INSERT -> CDCEventBuilder.insertEnvelope(entity, objectMapper, keyParts);
            case UPDATE -> CDCEventBuilder.updateEnvelope(entityBefore, entity, objectMapper, keyParts);
            case DELETE -> CDCEventBuilder.deleteEnvelope(objectMapper, keyParts);
        };
    }

    public Map<String, CDCHandlerRegistry.HandlerDescriptor> getRegisteredHandlers() {
        return registry.getAllHandlers();
    }

    private void dispatch(String endpoint, CDCEnvelope envelope) {
        String normalized = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        CDCHandlerRegistry.HandlerDescriptor handler = registry.getHandler(normalized);
        if (handler == null) {
            throw new IllegalArgumentException(
                    "No CDC handler registered for endpoint: " + normalized
                    + ". Registered endpoints: " + registry.getAllHandlers().keySet());
        }
        invoker.invoke(handler, envelope);
    }

    @SuppressWarnings("unchecked")
    private static Class<Map<String, Object>> mapType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }
}
