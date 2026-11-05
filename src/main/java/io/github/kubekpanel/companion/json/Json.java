package io.github.kubekpanel.companion.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny JSON codec, bundled Gson differs too much across versions. Output is pure ASCII, 1 char = 1 byte */
public final class Json {

  private Json() {
  }

  public static String write(Object value) {
    StringBuilder sb = new StringBuilder(256);
    writeValue(sb, value);
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  private static void writeValue(StringBuilder sb, Object value) {
    if (value == null) {
      sb.append("null");
    } else if (value instanceof String) {
      writeString(sb, (String) value);
    } else if (value instanceof Boolean) {
      sb.append(((Boolean) value).booleanValue() ? "true" : "false");
    } else if (value instanceof Double || value instanceof Float) {
      double d = ((Number) value).doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        sb.append("null");
      } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
        sb.append((long) d);
      } else {
        sb.append(d);
      }
    } else if (value instanceof Number) {
      sb.append(((Number) value).longValue());
    } else if (value instanceof Map) {
      sb.append('{');
      boolean first = true;
      for (Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
        if (!first) sb.append(',');
        first = false;
        writeString(sb, e.getKey());
        sb.append(':');
        writeValue(sb, e.getValue());
      }
      sb.append('}');
    } else if (value instanceof Collection) {
      sb.append('[');
      boolean first = true;
      for (Object o : (Collection<Object>) value) {
        if (!first) sb.append(',');
        first = false;
        writeValue(sb, o);
      }
      sb.append(']');
    } else if (value instanceof double[]) {
      double[] arr = (double[]) value;
      sb.append('[');
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) sb.append(',');
        writeValue(sb, arr[i]);
      }
      sb.append(']');
    } else if (value instanceof Enum) {
      writeString(sb, ((Enum<?>) value).name());
    } else {
      writeString(sb, value.toString());
    }
  }

  private static void writeString(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        default:
          if (c < 0x20 || c > 0x7e) {
            sb.append("\\u");
            String hex = Integer.toHexString(c);
            for (int pad = hex.length(); pad < 4; pad++) sb.append('0');
            sb.append(hex);
          } else {
            sb.append(c);
          }
      }
    }
    sb.append('"');
  }

  /** Objects -> LinkedHashMap, arrays -> ArrayList, numbers -> Double */
  public static Object parse(String text) {
    Parser p = new Parser(text);
    p.skipWs();
    Object value = p.readValue(0);
    p.skipWs();
    if (p.pos != text.length()) throw new IllegalArgumentException("Trailing data at " + p.pos);
    return value;
  }

  private static final class Parser {
    private static final int MAX_DEPTH = 32;
    private final String s;
    private int pos;

    Parser(String s) {
      this.s = s;
    }

    void skipWs() {
      while (pos < s.length()) {
        char c = s.charAt(pos);
        if (c != ' ' && c != '\n' && c != '\r' && c != '\t') break;
        pos++;
      }
    }

    Object readValue(int depth) {
      if (depth > MAX_DEPTH) throw new IllegalArgumentException("Too deep");
      if (pos >= s.length()) throw new IllegalArgumentException("Unexpected end");
      char c = s.charAt(pos);
      switch (c) {
        case '{':
          return readObject(depth);
        case '[':
          return readArray(depth);
        case '"':
          return readString();
        case 't':
          expect("true");
          return Boolean.TRUE;
        case 'f':
          expect("false");
          return Boolean.FALSE;
        case 'n':
          expect("null");
          return null;
        default:
          return readNumber();
      }
    }

    private Map<String, Object> readObject(int depth) {
      Map<String, Object> map = new LinkedHashMap<>();
      pos++;
      skipWs();
      if (peek() == '}') {
        pos++;
        return map;
      }
      while (true) {
        skipWs();
        if (peek() != '"') throw new IllegalArgumentException("Expected key at " + pos);
        String key = readString();
        skipWs();
        if (next() != ':') throw new IllegalArgumentException("Expected ':' at " + pos);
        skipWs();
        map.put(key, readValue(depth + 1));
        skipWs();
        char c = next();
        if (c == '}') return map;
        if (c != ',') throw new IllegalArgumentException("Expected ',' at " + pos);
      }
    }

    private List<Object> readArray(int depth) {
      List<Object> list = new ArrayList<>();
      pos++;
      skipWs();
      if (peek() == ']') {
        pos++;
        return list;
      }
      while (true) {
        skipWs();
        list.add(readValue(depth + 1));
        skipWs();
        char c = next();
        if (c == ']') return list;
        if (c != ',') throw new IllegalArgumentException("Expected ',' at " + pos);
      }
    }

    private String readString() {
      pos++;
      StringBuilder sb = new StringBuilder();
      while (true) {
        char c = next();
        if (c == '"') return sb.toString();
        if (c != '\\') {
          sb.append(c);
          continue;
        }
        char e = next();
        switch (e) {
          case 'n':
            sb.append('\n');
            break;
          case 'r':
            sb.append('\r');
            break;
          case 't':
            sb.append('\t');
            break;
          case 'b':
            sb.append('\b');
            break;
          case 'f':
            sb.append('\f');
            break;
          case 'u':
            if (pos + 4 > s.length()) throw new IllegalArgumentException("Bad escape");
            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
            pos += 4;
            break;
          default:
            sb.append(e);
        }
      }
    }

    private Double readNumber() {
      int start = pos;
      while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
      if (start == pos) throw new IllegalArgumentException("Unexpected char at " + pos);
      return Double.valueOf(s.substring(start, pos));
    }

    private void expect(String word) {
      if (!s.startsWith(word, pos)) throw new IllegalArgumentException("Expected " + word);
      pos += word.length();
    }

    private char peek() {
      if (pos >= s.length()) throw new IllegalArgumentException("Unexpected end");
      return s.charAt(pos);
    }

    private char next() {
      if (pos >= s.length()) throw new IllegalArgumentException("Unexpected end");
      return s.charAt(pos++);
    }
  }
}
