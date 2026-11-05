package io.github.kubekpanel.companion.actions;

import io.github.kubekpanel.companion.json.Obj;
import io.github.kubekpanel.companion.platform.Compat;
import io.github.kubekpanel.companion.platform.Tasks;
import io.github.kubekpanel.companion.util.Format;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.Plugin;

/**
 * Refuses logins in AsyncPlayerPreLoginEvent, before the player exists. PlayerLoginEvent is deprecated on Paper 1.21.6+
 * State is memory only, the panel re-sends it after every hello
 */
public final class Maintenance implements Listener {

  private static final String PAPER_PING = "com.destroystokyo.paper.event.server.PaperServerListPingEvent";

  private final Tasks tasks;
  private volatile State state = State.OFF;
  // written on main, read by async login threads
  private volatile Set<UUID> operators = Collections.emptySet();

  public Maintenance(Tasks tasks) {
    this.tasks = tasks;
  }

  public void register(Plugin plugin) {
    Bukkit.getPluginManager().registerEvents(this, plugin);
    registerPaperPing(plugin);
  }

  public void unregister() {
    HandlerList.unregisterAll(this);
  }

  /** Main thread, keeps the op list fresh for allowOps */
  public void refresh() {
    if (!state.enabled || !state.allowOps) return;
    Set<UUID> ops = new HashSet<>();
    for (OfflinePlayer op : Bukkit.getOperators()) ops.add(op.getUniqueId());
    operators = ops;
  }

  public void apply(Obj request) {
    State next = State.from(request);
    state = next;
    refresh();
    if (next.enabled && request.bool("kickOnline", false)) kickOnline(next);
  }

  public Map<String, Object> describe() {
    Map<String, Object> out = Obj.map();
    out.put("enabled", state.enabled);
    out.put("allowOps", state.allowOps);
    return out;
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onPreLogin(AsyncPlayerPreLoginEvent event) {
    State current = state;
    if (!current.enabled) return;
    if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
    if (current.allows(event.getUniqueId(), event.getName(), operators)) return;
    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, current.message);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onPing(ServerListPingEvent event) {
    applyMotd(event);
  }

  /** Paper's ping event may have its own handler list, setMotd twice is harmless */
  private void registerPaperPing(Plugin plugin) {
    Class<?> raw = Compat.findClass(PAPER_PING);
    if (raw == null || !ServerListPingEvent.class.isAssignableFrom(raw)) return;
    @SuppressWarnings("unchecked")
    Class<? extends Event> type = (Class<? extends Event>) raw;
    try {
      Bukkit.getPluginManager().registerEvent(type, this, EventPriority.HIGHEST, (listener, event) -> {
        if (event instanceof ServerListPingEvent) applyMotd((ServerListPingEvent) event);
      }, plugin, true);
    } catch (Throwable ignored) {
      // onPing still covers it
    }
  }

  @SuppressWarnings("deprecation")
  private void applyMotd(ServerListPingEvent event) {
    State current = state;
    if (current.enabled && current.motd != null) event.setMotd(current.motd);
  }

  @SuppressWarnings("deprecation")
  private void kickOnline(State current) {
    Set<UUID> ops = operators;
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (current.allows(player.getUniqueId(), player.getName(), ops)) continue;
      tasks.runFor(player, () -> player.kickPlayer(current.message), null);
    }
  }

  private static final class State {
    static final State OFF = new State(false, "", null, false, Collections.<String>emptySet());

    final boolean enabled;
    final String message;
    final String motd;
    final boolean allowOps;
    // lowercased names and uuid strings
    final Set<String> allow;

    State(boolean enabled, String message, String motd, boolean allowOps, Set<String> allow) {
      this.enabled = enabled;
      this.message = message;
      this.motd = motd;
      this.allowOps = allowOps;
      this.allow = allow;
    }

    static State from(Obj request) {
      if (!request.bool("enabled", false)) return OFF;
      Set<String> allow = new HashSet<>();
      List<Object> list = request.list("allow");
      if (list != null) {
        for (Object o : list) {
          if (o instanceof String) allow.add(((String) o).toLowerCase(Locale.ROOT));
        }
      }
      String motd = request.str("motd", null);
      return new State(true,
          Format.color(request.str("message", "Server is under maintenance")),
          motd == null ? null : Format.color(motd),
          request.bool("allowOps", true),
          allow);
    }

    boolean allows(UUID id, String name, Set<UUID> ops) {
      if (allowOps && ops.contains(id)) return true;
      if (allow.contains(id.toString())) return true;
      return name != null && allow.contains(name.toLowerCase(Locale.ROOT));
    }
  }
}
