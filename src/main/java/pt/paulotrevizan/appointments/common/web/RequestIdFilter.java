package pt.paulotrevizan.appointments.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Correlates a request across services and log lines: reuses the caller's {@code X-Request-Id} or
 * generates one, puts it in the MDC and echoes it in the response. Runs before Spring Security, so
 * 401 and 403 responses carry it too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Request-Id";
  static final String MDC_KEY = "requestId";

  // A caller's id goes into every log line: anything else could forge or break log entries.
  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String requestId = requestIdOf(request);
    response.setHeader(HEADER, requestId);
    MDC.put(MDC_KEY, requestId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  private static String requestIdOf(HttpServletRequest request) {
    String received = request.getHeader(HEADER);
    return received != null && SAFE_ID.matcher(received).matches()
        ? received
        : UUID.randomUUID().toString();
  }
}
