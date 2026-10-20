package com.cockroachlabs.data.multiregion;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class HeaderRegionResolver implements RegionResolver {

    private final String headerName;

    public HeaderRegionResolver(String headerName) {
        this.headerName = headerName;
    }

    @Override
    public String resolve() {
        ServletRequestAttributes attrs = (ServletRequestAttributes)
                RequestContextHolder.getRequestAttributes();
        if (attrs == null) return null;
        HttpServletRequest request = attrs.getRequest();
        return request.getHeader(headerName);
    }
}
