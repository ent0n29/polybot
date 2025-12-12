package com.polymarket.hft.polymarket.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.hft.config.HftProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class LiveTradingGuardFilter extends OncePerRequestFilter {

  public static final String HEADER_LIVE_ACK = "X-HFT-LIVE-ACK";

  private final HftProperties properties;
  private final ObjectMapper objectMapper;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String uri = request.getRequestURI();
    if (uri == null || !uri.startsWith("/api/polymarket/orders")) {
      return true;
    }
    String method = request.getMethod();
    return HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain
  ) throws ServletException, IOException {
    if (properties.mode() == HftProperties.TradingMode.LIVE) {
      String ack = request.getHeader(HEADER_LIVE_ACK);
      if (!"true".equalsIgnoreCase(ack)) {
        response.setStatus(428); // Precondition Required
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", 428);
        body.put("error", "Precondition Required");
        body.put("message", "Refusing LIVE trading request without " + HEADER_LIVE_ACK + ": true");
        body.put("path", request.getRequestURI());
        response.getWriter().write(objectMapper.writeValueAsString(body));
        return;
      }
    }

    filterChain.doFilter(request, response);
  }
}

