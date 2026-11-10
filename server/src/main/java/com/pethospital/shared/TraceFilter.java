package com.pethospital.shared;
import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {
 public static String current() { return MDC.get("traceId"); }
 @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
  String id=UUID.randomUUID().toString();
  MDC.put("traceId",id); res.setHeader("X-Trace-Id",id);
  try { chain.doFilter(req,res); } finally { MDC.remove("traceId"); }
 }
}

