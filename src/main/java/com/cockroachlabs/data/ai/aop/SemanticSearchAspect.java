package com.cockroachlabs.data.ai.aop;

import java.util.List;
import com.cockroachlabs.data.ai.VectorSearchResult;
import com.cockroachlabs.data.ai.VectorSearchTemplate;
import com.cockroachlabs.data.ai.annotation.SemanticSearch;
import com.cockroachlabs.data.ai.metrics.AIMetrics;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class SemanticSearchAspect {

    private final VectorSearchTemplate vectorSearchTemplate;
    private final AIMetrics metrics;

    public SemanticSearchAspect(VectorSearchTemplate vectorSearchTemplate,
                                AIMetrics metrics) {
        this.vectorSearchTemplate = vectorSearchTemplate;
        this.metrics = metrics;
    }

    @Around("@annotation(semanticSearch)")
    public Object around(ProceedingJoinPoint pjp,
                         SemanticSearch semanticSearch) throws Throwable {

        float[] queryVector = extractQueryVector(pjp, semanticSearch.queryParam());
        if (queryVector == null) {
            return pjp.proceed();
        }

        String filter = resolveFilter(semanticSearch.filter(), pjp);
        if (semanticSearch.maxDistance() > 0) {
            String distanceFilter = semanticSearch.vectorColumn() + " "
                    + semanticSearch.metric().operator() + " '"
                    + VectorSearchTemplate.toVectorLiteral(queryVector)
                    + "'::vector < " + semanticSearch.maxDistance();
            filter = filter.isBlank() ? distanceFilter
                    : filter + " AND " + distanceFilter;
        }

        long start = System.nanoTime();
        try {
            List<VectorSearchResult<Object>> results = vectorSearchTemplate.search(
                    semanticSearch.table(),
                    semanticSearch.vectorColumn(),
                    queryVector,
                    semanticSearch.metric(),
                    semanticSearch.limit(),
                    List.of(semanticSearch.columns()),
                    filter,
                    semanticSearch.beamSize()
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

    private String resolveFilter(String filterTemplate, ProceedingJoinPoint pjp) {
        if (filterTemplate == null || filterTemplate.isBlank()) {
            return "";
        }

        MethodSignature sig = (MethodSignature) pjp.getSignature();
        String[] paramNames = sig.getParameterNames();
        Object[] args = pjp.getArgs();

        String resolved = filterTemplate;
        for (int i = 0; i < paramNames.length; i++) {
            if (args[i] instanceof float[]) continue;
            String placeholder = ":" + paramNames[i];
            if (resolved.contains(placeholder)) {
                String value = formatValue(args[i]);
                resolved = resolved.replace(placeholder, value);
            }
        }
        return resolved;
    }

    private String formatValue(Object value) {
        if (value == null) return "NULL";
        if (value instanceof Number) return value.toString();
        if (value instanceof Boolean) return value.toString();
        return "'" + value.toString().replace("'", "''") + "'";
    }
}
