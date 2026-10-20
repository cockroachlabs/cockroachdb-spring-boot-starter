package com.cockroachlabs.data.ai.aop;

import java.util.List;
import com.cockroachlabs.data.ai.VectorSearchResult;
import com.cockroachlabs.data.ai.VectorSearchTemplate;
import com.cockroachlabs.data.ai.annotation.VectorSearch;
import com.cockroachlabs.data.ai.metrics.AIMetrics;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class VectorSearchAspect {

    private final VectorSearchTemplate vectorSearchTemplate;
    private final AIMetrics metrics;

    public VectorSearchAspect(VectorSearchTemplate vectorSearchTemplate,
                              AIMetrics metrics) {
        this.vectorSearchTemplate = vectorSearchTemplate;
        this.metrics = metrics;
    }

    @Around("@annotation(vectorSearch)")
    public Object around(ProceedingJoinPoint pjp,
                         VectorSearch vectorSearch) throws Throwable {

        float[] queryVector = extractQueryVector(pjp, vectorSearch.queryParam());
        if (queryVector == null) {
            return pjp.proceed();
        }

        long start = System.nanoTime();
        try {
            List<VectorSearchResult<Object>> results = vectorSearchTemplate.search(
                    vectorSearch.table(),
                    vectorSearch.vectorColumn(),
                    queryVector,
                    vectorSearch.metric(),
                    vectorSearch.limit(),
                    List.of(vectorSearch.columns()),
                    "",
                    vectorSearch.beamSize()
            );
            metrics.recordVectorSearch(start, results.size(), true);
            return results;
        } catch (Exception ex) {
            metrics.recordVectorSearch(start, 0, false);
            throw ex;
        }
    }

    private float[] extractQueryVector(ProceedingJoinPoint pjp, String paramName) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        String[] paramNames = sig.getParameterNames();
        Object[] args = pjp.getArgs();

        for (int i = 0; i < paramNames.length; i++) {
            if (paramName.equals(paramNames[i]) && args[i] instanceof float[] vec) {
                return vec;
            }
        }
        return null;
    }
}
