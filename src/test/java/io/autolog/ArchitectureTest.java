package io.autolog;

import static org.assertj.core.api.Assertions.*;

import io.autolog.common.JdbcExecutor;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

class ArchitectureTest extends ApiTestSupport {
  @Autowired ApplicationContext context;

  @Test
  void controllersOnlyDelegateToServicesAndServicesDoNotExecuteSql() {
    for (var bean : context.getBeansWithAnnotation(RestController.class).values()) {
      var type = AopUtils.getTargetClass(bean);
      if (!type.getPackageName().startsWith("io.autolog")) continue;
      for (var field : type.getDeclaredFields()) {
        if (field.getType().getPackageName().startsWith("io.autolog"))
          assertThat(field.getType().isAnnotationPresent(Service.class))
              .as(type.getSimpleName() + "." + field.getName())
              .isTrue();
      }
      for (var method : type.getDeclaredMethods())
        assertThat(method.getGenericReturnType().getTypeName())
            .as(type.getSimpleName() + "." + method.getName())
            .doesNotContain("java.util.Map");
    }
    for (var bean : context.getBeansWithAnnotation(Service.class).values()) {
      var type = AopUtils.getTargetClass(bean);
      if (!type.getPackageName().startsWith("io.autolog")) continue;
      assertThat(
              Arrays.stream(type.getDeclaredFields())
                  .noneMatch(field -> field.getType() == JdbcExecutor.class))
          .as(type.getSimpleName())
          .isTrue();
    }
  }
}
