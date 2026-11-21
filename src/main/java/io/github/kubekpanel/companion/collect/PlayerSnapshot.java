package io.github.kubekpanel.companion.collect;

import io.github.kubekpanel.companion.Settings;
import io.github.kubekpanel.companion.json.Obj;
import io.github.kubekpanel.companion.platform.Compat;
import io.github.kubekpanel.companion.util.Format;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** One online player for stats, read on the thread that owns the player */
final class PlayerSnapshot {

  private final Compat compat;
  private final Settings settings;
  private final Sessions sessions;

  PlayerSnapshot(Compat compat, Settings settings, Sessions sessions) {
    this.compat = compat;
    this.settings = settings;
    this.sessions = sessions;
  }

  Map<String, Object> of(Player player, Location scratch) {
    Map<String, Object> p = Obj.map();
    p.put("uuid", player.getUniqueId().toString());
    p.put("name", player.getName());
    p.put("world", player.getWorld().getName());
    p.put("gamemode", player.getGameMode().name());
    p.put("ping", compat.ping(player));
    p.put("since", sessions.since(player.getUniqueId()));
    p.put("health", Format.round(player.getHealth(), 1));
    p.put("food", player.getFoodLevel());
    p.put("level", player.getLevel());
    if (settings.coords) {
      Location loc = player.getLocation(scratch);
      p.put("x", Format.round(loc.getX(), 1));
      p.put("y", Format.round(loc.getY(), 1));
      p.put("z", Format.round(loc.getZ(), 1));
    }
    return p;
  }
}
