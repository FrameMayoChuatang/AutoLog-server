package io.autolog.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.autolog.common.*;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http, CredentialRepository credentials, ObjectMapper json) throws Exception {
    return http.csrf(c -> c.disable())
        .cors(c -> {})
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        HttpMethod.POST,
                        "/api/auth/companies",
                        "/api/auth/login",
                        "/api/auth/password/setup",
                        "/api/device/pair")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/password/setup",
                        "/password/setup.js",
                        "/password/setup.css")
                    .permitAll()
                    .requestMatchers(
                        "/actuator/health",
                        "/api/docs",
                        "/api/docs/**",
                        "/api/docs.yaml",
                        "/docs",
                        "/swagger-ui/**",
                        "/error")
                    .permitAll()
                    .requestMatchers("/api/admins/**")
                    .hasRole("OWNER")
                    .requestMatchers("/api/device/**")
                    .hasRole("DEVICE")
                    .requestMatchers("/api/auth/me", "/api/auth/logout")
                    .authenticated()
                    .requestMatchers("/api/**")
                    .hasAnyRole("OWNER", "STAFF")
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, ex) -> {
                          res.setStatus(401);
                          res.setContentType("application/json;charset=UTF-8");
                          json.writeValue(
                              res.getOutputStream(),
                              ApiErrorHandler.body("UNAUTHORIZED", "로그인이 필요합니다."));
                        })
                    .accessDeniedHandler(
                        (req, res, ex) -> {
                          res.setStatus(403);
                          res.setContentType("application/json;charset=UTF-8");
                          json.writeValue(
                              res.getOutputStream(),
                              ApiErrorHandler.body("FORBIDDEN", "접근 권한이 없습니다."));
                        }))
        .addFilterBefore(
            new BearerFilter(credentials, json), UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(
      @Value("${autolog.cors-origins}") String origins) {
    var c = new CorsConfiguration();
    c.setAllowedOrigins(Arrays.asList(origins.split(",")));
    c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    c.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    c.setExposedHeaders(List.of("Content-Disposition"));
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", c);
    return source;
  }
}
