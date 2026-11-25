package com.cockroachlabs.data.multiregion;

import java.io.IOException;
import com.cockroachlabs.data.autoconfigure.CockroachDBProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;

public class RegionContextFilter extends OncePerRequestFilter {

    private final CockroachDBProperties properties;
    private final @Nullable RegionResolver resolver;

    public RegionContextFilter(CockroachDBProperties properties,
                               @Nullable RegionResolver resolver) {
        this.properties = properties;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            String region = resolveFromRequest(request);
            if (region != null && !region.isBlank()) {
                RegionContext.set(region);
            }
            chain.doFilter(request, response);
        } finally {
            RegionContext.clear();
        }
    }

    private @Nullable String resolveFromRequest(HttpServletRequest request) {
        String headerName = properties.getRegion().getHeaderName();
        String fromHeader = request.getHeader(headerName);
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader;
        }
        if (resolver != null) {
            return resolver.resolve();
        }
        return null;
    }
}
