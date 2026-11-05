package io.github.kubekpanel.companion.transport;

import io.github.kubekpanel.companion.json.Json;
import io.github.kubekpanel.companion.json.Obj;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.event.server.ServerCommandEvent;

/** Incoming side, the panel types "kubekc <sig> <base64url json>" into the console and we cancel it before dispatch */
public final class Inbound implements Listener {

  public interface Handler {
    void handle(String id, String type, Obj request);
  }

  private static final String PREFIX = "kubekc ";
  private static final long MAX_SKEW_MS = 120_000;
  // hard cap so a flood can't grow the map forever
  private static final int SEEN_LIMIT = 16_384;

  private final Signer signer;
  private final Handler handler;
  private final Logger logger;
  // id -> when we got it. Kept for the whole skew window, otherwise a captured line could be replayed
  private final Map<String, Long> seen = new LinkedHashMap<>();

  public Inbound(Signer signer, Handler handler, Logger logger) {
    this.signer = signer;
    this.handler = handler;
    this.logger = logger;
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void onConsole(ServerCommandEvent event) {
    intercept(event);
  }

  // RCON has its own handler list
  @EventHandler(priority = EventPriority.LOWEST)
  public void onRemote(RemoteServerCommandEvent event) {
    intercept(event);
  }

  private void intercept(ServerCommandEvent event) {
    String line = event.getCommand();
    if (line == null) return;
    line = line.trim();
    if (line.startsWith("/")) line = line.substring(1);
    if (!line.startsWith(PREFIX)) return;
    event.setCancelled(true);
    accept(line.substring(PREFIX.length()).trim());
  }

  private void accept(String body) {
    int space = body.indexOf(' ');
    if (space <= 0) return;
    String sig = body.substring(0, space);
    String encoded = body.substring(space + 1).trim();
    // no reply on a bad sig, it'd only help someone guessing
    if (!signer.verify(encoded, sig)) return;
    Obj request;
    try {
      String json = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
      request = Obj.of(Json.parse(json));
    } catch (RuntimeException e) {
      logger.log(Level.FINE, "Malformed panel request", e);
      return;
    }
    String id = request.str("id", null);
    String type = request.str("t", null);
    if (id == null || type == null) return;
    long ts = request.num("ts", 0);
    if (Math.abs(System.currentTimeMillis() - ts) > MAX_SKEW_MS) return;
    if (!firstTime(id)) return;
    handler.handle(id, type, request);
  }

  private boolean firstTime(String id) {
    long now = System.currentTimeMillis();
    synchronized (seen) {
      Iterator<Long> it = seen.values().iterator();
      // insertion order = arrival order, so expired ids sit at the head
      while (it.hasNext()) {
        long at = it.next();
        if (now - at <= 2 * MAX_SKEW_MS && seen.size() < SEEN_LIMIT) break;
        it.remove();
      }
      return seen.putIfAbsent(id, now) == null;
    }
  }
}
