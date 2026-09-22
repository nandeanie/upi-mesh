package com.demo.upimesh.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Rate limiter: 60 req/min per IP on POST /api/bridge/ingest. */
@Component
public class RateLimitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final int  REQUESTS_PER_WINDOW = 60;
    private static final long WINDOW_MS           = 60_000L;

    private final Map<String, WindowEntry> ipWindows = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        if ("POST".equals(request.getMethod()) && "/api/bridge/ingest".equals(request.getRequestURI())) {
            String ip     = getClientIp(request);
            WindowEntry w = ipWindows.computeIfAbsent(ip, k -> new WindowEntry());
            long now      = Instant.now().toEpochMilli();

            synchronized (w) {
                if (now - w.windowStart > WINDOW_MS) {
                    w.windowStart = now;
                    w.count.set(0);
                }
                if (w.count.incrementAndGet() > REQUESTS_PER_WINDOW) {
                    log.warn("Rate limit exceeded for IP {}", ip);
                    response.setStatus(429);
                    response.setContentType("application/json");
                    response.setHeader("Retry-After", "60");
                    response.getWriter().write(
                        "{\"error\":\"Rate limit exceeded — max 60 req/min\",\"status\":429,\"retryAfterSeconds\":60}"
                    );
                    return;
                }
            }
        }

        chain.doFilter(req, res);
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
    }

    private static class WindowEntry {
        volatile long windowStart = Instant.now().toEpochMilli();
        AtomicInteger count       = new AtomicInteger(0);
    }
}
