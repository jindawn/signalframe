package com.signalframe.http;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CorrelationFilter extends OncePerRequestFilter {

  protected void doFilterInternal(
    HttpServletRequest req,
    HttpServletResponse res,
    FilterChain chain
  ) throws ServletException, IOException {
    String id = req.getHeader("X-Request-ID");
    if (id == null || !id.matches("[a-zA-Z0-9._-]{1,64}")) id =
      UUID.randomUUID().toString();
    req.setAttribute("requestId", id);
    res.setHeader("X-Request-ID", id);
    MDC.put("requestId", id);
    try {
      chain.doFilter(req, res);
    } finally {
      MDC.remove("requestId");
    }
  }
}
