package io.autolog.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.autolog.common.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class BearerFilter extends OncePerRequestFilter {
  private final CredentialRepository credentials;
  private final ObjectMapper json;

  public BearerFilter(CredentialRepository credentials, ObjectMapper json) {
    this.credentials = credentials;
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String header = req.getHeader("Authorization");
    if (header != null) {
      if (!header.startsWith("Bearer ") || header.length() > 256) {
        reject(res);
        return;
      }
      var row = credentials.authenticate(TokenCodec.hash(header.substring(7)));
      if (row.isEmpty()) {
        reject(res);
        return;
      }
      var actor = row.get();
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  actor, null, List.of(new SimpleGrantedAuthority("ROLE_" + actor.role()))));
    }
    chain.doFilter(req, res);
  }

  private void reject(HttpServletResponse res) throws IOException {
    res.setStatus(401);
    res.setContentType("application/json;charset=UTF-8");
    json.writeValue(
        res.getOutputStream(), ApiErrorHandler.body("UNAUTHORIZED", "인증 정보가 없거나 유효하지 않습니다."));
  }
}
