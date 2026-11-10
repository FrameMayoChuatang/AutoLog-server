package io.autolog.common;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfig {
  @Bean
  OpenAPI openAPI() {
    return new OpenAPI()
        .info(
            new Info()
                .title("AutoLog Server API")
                .version("v1")
                .description(
                    "법인차량 운행기록 관리 API. 상세 상태 전이·동기화 계약은 저장소 docs/API.md를 참고하세요. 거리는 km, 시각은 UTC ISO-8601, 월 집계는 Asia/Seoul 기준입니다."))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("Opaque token")))
        .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
  }

  @Bean
  OpenApiCustomizer publicOperations() {
    return api ->
        List.of(
                "/api/auth/companies",
                "/api/auth/login",
                "/api/auth/password/setup",
                "/api/device/pair")
            .forEach(
                path -> {
                  var item = api.getPaths().get(path);
                  if (item != null && item.getPost() != null) item.getPost().setSecurity(List.of());
                });
  }
}
