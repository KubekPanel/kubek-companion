package io.github.kubekpanel.companion.json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed reads with defaults */
public final class Obj {

  private final Map<String, Object> map;

  public Obj(Map<String, Object> map) {
    this.map = map;
  }

  public static Obj of(Object parsed) {
    if (!(parsed instanceof Map)) throw new IllegalArgumentException("Expected an object");
    @SuppressWarnings("unchecked")
    Map<String, Object> m = (Map<String, Object>) parsed;
    return new Obj(m);
  }

  /** Ordered, so outgoing JSON keeps field order */
  public static Map<String, Object> map() {
    return new LinkedHashMap<>();
  }

  public boolean has(String key) {
    return map.get(key) != null;
  }

  public String str(String key, String def) {
    Object v = map.get(key);
    return v instanceof String ? (String) v : def;
  }

  public long num(String key, long def) {
    Object v = map.get(key);
    return v instanceof Number ? ((Number) v).longValue() : def;
  }

  public boolean bool(String key, boolean def) {
    Object v = map.get(key);
    return v instanceof Boolean ? (Boolean) v : def;
  }

  @SuppressWarnings("unchecked")
  public List<Object> list(String key) {
    Object v = map.get(key);
    return v instanceof List ? (List<Object>) v : null;
  }
}
