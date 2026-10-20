package com.cockroachlabs.data.multiregion;

import java.io.IOException;
import com.cockroachlabs.data.autoconfigure.CockroachDBProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

public class RegionContextFilter extends OncePerRequestFilter {

    private final CockroachDBProperties properties;
    private final RegionResolver resolver;

    public RegionContextFilter(CockroachDBProperties properties,
                               RegionResolver resolver) {
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

    private String resolveFromRequest(HttpServletRequest request) {
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
