package com.cockroachlabs.data.cdc.handler;

import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.cockroachlabs.data.cdc.CDCEnvelope;
import com.cockroachlabs.data.cdc.CDCEvent;
import com.cockroachlabs.data.cdc.CDCOperationType;
import com.cockroachlabs.data.cdc.CDCPayload;
import tools.jackson.databind.ObjectMapper;

public class CDCMethodInvoker {

    private final ObjectMapper objectMapper;

    public CDCMethodInvoker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void invoke(CDCHandlerRegistry.HandlerDescriptor descriptor, CDCEnvelope envelope) {
        if (envelope.isResolved() && !envelope.hasPayload()) {
            return;
        }

        if (!envelope.hasPayload()) return;

        switch (descriptor.signatureType()) {
            case ENVELOPE -> invokeWithEnvelope(descriptor, envelope);
            case CDC_EVENT -> invokePerEvent(descriptor, envelope);
            case CDC_EVENT_LIST -> invokeWithBatch(descriptor, envelope);
            case ENTITY -> invokeWithEntity(descriptor, envelope);
        }
    }

    private void invokeWithEnvelope(CDCHandlerRegistry.HandlerDescriptor descriptor,
                                     CDCEnvelope envelope) {
        invokeMethod(descriptor, envelope);
    }

    private void invokePerEvent(CDCHandlerRegistry.HandlerDescriptor descriptor,
                                 CDCEnvelope envelope) {
        for (CDCPayload payload : envelope.payload()) {
            CDCOperationType op = payload.resolveOperation();
            if (!descriptor.allowedOperations().contains(op)) continue;

            CDCEvent<?> event = toTypedEvent(payload, descriptor.entityType());
            invokeMethod(descriptor, event);
        }
    }

    private void invokeWithBatch(CDCHandlerRegistry.HandlerDescriptor descriptor,
                                  CDCEnvelope envelope) {
        List<CDCEvent<?>> events = new ArrayList<>();
        for (CDCPayload payload : envelope.payload()) {
            CDCOperationType op = payload.resolveOperation();
            if (!descriptor.allowedOperations().contains(op)) continue;
            events.add(toTypedEvent(payload, descriptor.entityType()));
        }
        if (!events.isEmpty()) {
            invokeMethod(descriptor, events);
        }
    }

    private void invokeWithEntity(CDCHandlerRegistry.HandlerDescriptor descriptor,
                                   CDCEnvelope envelope) {
        for (CDCPayload payload : envelope.payload()) {
            CDCOperationType op = payload.resolveOperation();
            if (!descriptor.allowedOperations().contains(op)) continue;

            Map<String, Object> data = (op == CDCOperationType.DELETE)
                    ? Map.of()
                    : payload.after();

            Object entity = objectMapper.convertValue(data, descriptor.entityType());
            invokeMethod(descriptor, entity);
        }
    }

    private <T> CDCEvent<T> toTypedEvent(CDCPayload payload, Class<T> entityType) {
        T after = convertOrNull(payload.after(), entityType);
        T before = convertOrNull(payload.before(), entityType);
        CDCOperationType op = payload.resolveOperation();
        Instant timestamp = parseTimestamp(payload.updated());

        return new CDCEvent<>(after, before, payload.key(), op, payload.topic(), timestamp);
    }

    private <T> T convertOrNull(Map<String, Object> map, Class<T> type) {
        if (map == null || map.isEmpty()) return null;
        return objectMapper.convertValue(map, type);
    }

    private Instant parseTimestamp(String hlcTimestamp) {
        if (hlcTimestamp == null || hlcTimestamp.isBlank()) return null;
        try {
            String secondsPart = hlcTimestamp.contains(".")
                    ? hlcTimestamp.substring(0, hlcTimestamp.indexOf('.'))
                    : hlcTimestamp;
            long epochSeconds = Long.parseLong(secondsPart);
            return Instant.ofEpochSecond(epochSeconds);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void invokeMethod(CDCHandlerRegistry.HandlerDescriptor descriptor, Object arg) {
        try {
            descriptor.method().invoke(descriptor.bean(), arg);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new CDCHandlerException(
                    "CDC handler failed: " + descriptor.method().getName(), cause);
        } catch (IllegalAccessException e) {
            throw new CDCHandlerException(
                    "Cannot access CDC handler: " + descriptor.method().getName(), e);
        }
    }

    public static class CDCHandlerException extends RuntimeException {
        public CDCHandlerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
