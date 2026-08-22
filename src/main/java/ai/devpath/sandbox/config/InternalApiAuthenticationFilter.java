package ai.devpath.sandbox.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Fail-closed workload credential for endpoints that bypass the gateway. */
public final class InternalApiAuthenticationFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-DevPath-Internal-Token";

  private final byte[] expectedToken;

  public InternalApiAuthenticationFilter(String expectedToken) {
    this.expectedToken = expectedToken == null
        ? new byte[0]
        : expectedToken.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(request.getContextPath() + "/internal/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    byte[] supplied = request.getHeader(HEADER) == null
        ? new byte[0]
        : request.getHeader(HEADER).getBytes(StandardCharsets.UTF_8);
    if (expectedToken.length == 0 || !MessageDigest.isEqual(expectedToken, supplied)) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }

    var authentication = UsernamePasswordAuthenticationToken.authenticated(
        "internal-workload",
        "N/A",
        List.of(new SimpleGrantedAuthority("ROLE_INTERNAL")));
    SecurityContextHolder.getContext().setAuthentication(authentication);
    try {
      filterChain.doFilter(request, response);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
