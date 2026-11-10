package io.autolog;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.api.Test;

class DocumentationApiTest extends ApiTestSupport {
  @Test
  void openApiAndSetupAssetsArePublicAndSecurityContractIsExplicit() throws Exception {
    var document = call("GET", "/api/docs", null, null, 200);
    assertThat(document.path("info").path("title").asText()).isEqualTo("AutoLog Server API");
    assertThat(document.path("paths").has("/api/device/trips/{id}")).isTrue();
    assertThat(document.path("paths").path("/api/auth/login").path("post").path("security"))
        .isEmpty();
    assertThat(
            document
                .path("components")
                .path("securitySchemes")
                .path("bearerAuth")
                .path("scheme")
                .asText())
        .isEqualTo("bearer");
    assertThat(response("GET", "/docs", null, null).getResponse().getStatus()).isEqualTo(302);
    for (String path :
        new String[] {
          "/password/setup", "/password/setup.js", "/password/setup.css", "/swagger-ui/index.html"
        })
      assertThat(response("GET", path, null, null).getResponse().getStatus())
          .as(path)
          .isEqualTo(200);
    var error = call("GET", "/api/statistics", owner, null, 400);
    assertThat(error.path("code").asText()).isEqualTo("INVALID_REQUEST");
  }

  @Test
  void corsAllowsConfiguredClientAndRejectsUnknownOrigin() throws Exception {
    var allowed =
        mvc.perform(
                options("/api/vehicles")
                    .header("Origin", "http://127.0.0.1:5173")
                    .header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "Authorization"))
            .andReturn()
            .getResponse();
    assertThat(allowed.getStatus()).isEqualTo(200);
    assertThat(allowed.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://127.0.0.1:5173");
    var denied =
        mvc.perform(
                options("/api/vehicles")
                    .header("Origin", "https://untrusted.example.test")
                    .header("Access-Control-Request-Method", "GET"))
            .andReturn()
            .getResponse();
    assertThat(denied.getStatus()).isEqualTo(403);
  }
}
