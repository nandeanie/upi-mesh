package com.demo.upimesh.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Bearer token enforcement on /api/bridge/ingest and /api/demo/reset-full. */
@Component
public class ApiKeyFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    @Value("${bridge.api-key:demo-key}")
    private String expectedApiKey;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String path   = request.getRequestURI();
        String method = request.getMethod();

        boolean requiresKey =
                ("POST".equals(method) && "/api/bridge/ingest".equals(path)) ||
                ("POST".equals(method) && "/api/demo/reset-full".equals(path));

        if (requiresKey) {
            String auth  = request.getHeader("Authorization");
            String token = (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7).trim() : null;

            if (!expectedApiKey.equals(token)) {
                log.warn("Unauthorized request to {} from {}", path, request.getRemoteAddr());
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write(
                    "{\"error\":\"Missing or invalid API key\",\"status\":401,\"hint\":\"Authorization: Bearer <BRIDGE_API_KEY>\"}"
                );
                return;
            }
        }

        chain.doFilter(req, res);
    }
}
