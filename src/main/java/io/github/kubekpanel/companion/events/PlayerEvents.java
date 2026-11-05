package io.github.kubekpanel.companion.events;

import io.github.kubekpanel.companion.collect.Sessions;
import io.github.kubekpanel.companion.json.Obj;
import io.github.kubekpanel.companion.platform.Compat;
import io.github.kubekpanel.companion.transport.Channel;
import io.github.kubekpanel.companion.util.Format;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/** All at MONITOR, handlers just copy a few fields over to the IO thread */
public final class PlayerEvents implements Listener {

  private final Channel channel;
  private final Sessions sessions;
  private final Compat compat;
  private final Consumer<Player> joinHook;
  private final Set<UUID> kicked = ConcurrentHashMap.newKeySet();

  public PlayerEvents(Channel channel, Sessions sessions, Compat compat, Consumer<Player> joinHook) {
    this.channel = channel;
    this.sessions = sessions;
    this.compat = compat;
    this.joinHook = joinHook;
  }

  public void register(Plugin plugin) {
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  public void unregister() {
    HandlerList.unregisterAll(this);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    sessions.join(player.getUniqueId());
    Map<String, Object> e = base("join", player);
    e.put("firstJoin", !player.hasPlayedBefore());
    channel.event(e);
    joinHook.accept(player);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent event) {
    Player player = event.getPlayer();
    Map<String, Object> e = base("quit", player);
    e.put("session", sessions.leave(player.getUniqueId()));
    e.put("reason", compat.quitReason(event));
    e.put("kicked", kicked.remove(player.getUniqueId()));
    channel.event(e);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  @SuppressWarnings("deprecation")
  public void onKick(PlayerKickEvent event) {
    Player player = event.getPlayer();
    kicked.add(player.getUniqueId());
    Map<String, Object> e = base("kick", player);
    e.put("reason", Format.strip(event.getReason()));
    e.put("cause", compat.kickCause(event));
    channel.event(e);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  @SuppressWarnings("deprecation")
  public void onDeath(PlayerDeathEvent event) {
    Player player = event.getEntity();
    Map<String, Object> e = base("death", player);
    e.put("message", Format.strip(event.getDeathMessage()));
    Player killer = player.getKiller();
    e.put("killer", killer == null ? null : killer.getName());
    channel.event(e);
  }

  private static Map<String, Object> base(String type, Player player) {
    Map<String, Object> e = Obj.map();
    e.put("type", type);
    e.put("ts", System.currentTimeMillis());
    e.put("uuid", player.getUniqueId().toString());
    e.put("name", player.getName());
    return e;
  }
}
