package io.github.kubekpanel.companion.collect;

import io.github.kubekpanel.companion.platform.Compat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Join time per online player, for session length */
public final class Sessions {

  private final Map<UUID, Long> joinedAt = new ConcurrentHashMap<>();
  private final Compat compat;

  public Sessions(Compat compat) {
    this.compat = compat;
  }

  /** For players already online after /reload, Paper knows the real login time */
  public void seedOnline() {
    long now = System.currentTimeMillis();
    for (Player player : Bukkit.getOnlinePlayers()) {
      Long login = compat.lastLogin(player);
      joinedAt.put(player.getUniqueId(), login != null ? login : now);
    }
  }

  public void join(UUID id) {
    joinedAt.put(id, System.currentTimeMillis());
  }

  /** ms, 0 if unknown */
  public long leave(UUID id) {
    Long since = joinedAt.remove(id);
    return since == null ? 0 : System.currentTimeMillis() - since;
  }

  public Long since(UUID id) {
    return joinedAt.get(id);
  }
}
