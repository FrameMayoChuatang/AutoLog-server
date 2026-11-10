package io.autolog.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class JsonCodec {
  private final ObjectMapper mapper;

  public JsonCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("JSON serialization failed", ex);
    }
  }

  public JsonNode readTree(String value) {
    if (value == null) return null;
    try {
      return mapper.readTree(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Stored JSON is invalid", ex);
    }
  }

  public <T> List<T> readList(String value, Class<T> type) {
    if (value == null) return null;
    try {
      return mapper.readValue(
          value, mapper.getTypeFactory().constructCollectionType(List.class, type));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Stored JSON list is invalid", ex);
    }
  }
}
