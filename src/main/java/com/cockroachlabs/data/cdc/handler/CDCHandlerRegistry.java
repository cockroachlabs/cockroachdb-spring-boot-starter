package com.cockroachlabs.data.cdc.handler;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.cockroachlabs.data.cdc.CDCEnvelope;
import com.cockroachlabs.data.cdc.CDCEvent;
import com.cockroachlabs.data.cdc.CDCOperationType;
import com.cockroachlabs.data.cdc.annotation.CockroachDBCDC;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

public class CDCHandlerRegistry implements BeanPostProcessor {

    private final Map<String, HandlerDescriptor> handlers = new ConcurrentHashMap<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        for (Method method : targetClass.getDeclaredMethods()) {
            CockroachDBCDC annotation = method.getAnnotation(CockroachDBCDC.class);
            if (annotation == null) continue;

            String endpoint = normalizeEndpoint(annotation.endpoint());
            HandlerDescriptor descriptor = buildDescriptor(bean, method, annotation);
            handlers.put(endpoint, descriptor);
        }
        return bean;
    }

    public HandlerDescriptor getHandler(String endpoint) {
        return handlers.get(normalizeEndpoint(endpoint));
    }

    public Map<String, HandlerDescriptor> getAllHandlers() {
        return Map.copyOf(handlers);
    }

    private HandlerDescriptor buildDescriptor(Object bean, Method method, CockroachDBCDC annotation) {
        method.setAccessible(true);

        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length != 1) {
            throw new IllegalStateException(
                    "@CockroachDBCDC method must have exactly one parameter: " + method);
        }

        Class<?> paramType = paramTypes[0];
        Type genericParamType = method.getGenericParameterTypes()[0];
        SignatureType signatureType = resolveSignatureType(paramType, genericParamType);
        Class<?> entityType = resolveEntityType(paramType, genericParamType, signatureType);

        Set<CDCOperationType> allowedOps = annotation.operations().length > 0
                ? Set.copyOf(Arrays.asList(annotation.operations()))
                : Set.of(CDCOperationType.values());

        return new HandlerDescriptor(
                bean, method, annotation,
                signatureType, entityType, allowedOps
        );
    }

    private SignatureType resolveSignatureType(Class<?> paramType, Type genericType) {
        if (paramType == CDCEnvelope.class) {
            return SignatureType.ENVELOPE;
        }
        if (paramType == CDCEvent.class) {
            return SignatureType.CDC_EVENT;
        }
        if (paramType == List.class && genericType instanceof ParameterizedType pt) {
            Type listArg = pt.getActualTypeArguments()[0];
            if (listArg instanceof ParameterizedType innerPt
                    && innerPt.getRawType() == CDCEvent.class) {
                return SignatureType.CDC_EVENT_LIST;
            }
        }
        return SignatureType.ENTITY;
    }

    private Class<?> resolveEntityType(Class<?> paramType, Type genericType, SignatureType sigType) {
        return switch (sigType) {
            case CDC_EVENT -> {
                if (genericType instanceof ParameterizedType pt) {
                    Type arg = pt.getActualTypeArguments()[0];
                    yield (arg instanceof Class<?> c) ? c : Object.class;
                }
                yield Object.class;
            }
            case CDC_EVENT_LIST -> {
                if (genericType instanceof ParameterizedType pt) {
                    Type listArg = pt.getActualTypeArguments()[0];
                    if (listArg instanceof ParameterizedType innerPt) {
                        Type arg = innerPt.getActualTypeArguments()[0];
                        yield (arg instanceof Class<?> c) ? c : Object.class;
                    }
                }
                yield Object.class;
            }
            case ENTITY -> paramType;
            case ENVELOPE -> Object.class;
        };
    }

    private String normalizeEndpoint(String endpoint) {
        if (!endpoint.startsWith("/")) {
            endpoint = "/" + endpoint;
        }
        if (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        return endpoint;
    }

    public enum SignatureType {
        CDC_EVENT,
        CDC_EVENT_LIST,
        ENTITY,
        ENVELOPE
    }

    public record HandlerDescriptor(
            Object bean,
            Method method,
            CockroachDBCDC annotation,
            SignatureType signatureType,
            Class<?> entityType,
            Set<CDCOperationType> allowedOperations
    ) {}
}
