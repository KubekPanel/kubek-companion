package io.github.kubekpanel.companion;

import io.github.kubekpanel.companion.json.Obj;
import java.util.Map;

/** Pushed by the panel, never stored on disk */
public final class Settings {

  private static final int MIN_INTERVAL = 5;
  private static final int MAX_INTERVAL = 300;

  public volatile int intervalSec = 15;
  public volatile boolean coords;
  public volatile boolean chat;

  /** true if the interval changed */
  public boolean apply(Obj request) {
    int before = intervalSec;
    if (request.has("intervalSec")) {
      long value = request.num("intervalSec", intervalSec);
      intervalSec = (int) Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, value));
    }
    if (request.has("coords")) coords = request.bool("coords", coords);
    if (request.has("chat")) chat = request.bool("chat", chat);
    return before != intervalSec;
  }

  public Map<String, Object> toMap() {
    Map<String, Object> out = Obj.map();
    out.put("intervalSec", intervalSec);
    out.put("coords", coords);
    out.put("chat", chat);
    return out;
  }
}
