package io.github.kubekpanel.companion.actions;

import io.github.kubekpanel.companion.platform.Compat;
import io.github.kubekpanel.companion.platform.Tasks;
import io.github.kubekpanel.companion.util.Format;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Broadcast and title, & colour codes ok */
public final class Messages {

  private final Compat compat;
  private final Tasks tasks;

  public Messages(Compat compat, Tasks tasks) {
    this.compat = compat;
    this.tasks = tasks;
  }

  /** Main thread or global region */
  public void broadcast(String message) {
    Bukkit.broadcastMessage(Format.color(message));
  }

  public void title(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
    String main = Format.color(title == null ? "" : title);
    String sub = Format.color(subtitle == null ? "" : subtitle);
    for (Player player : Bukkit.getOnlinePlayers()) {
      // Folia wants it on the player's region
      tasks.runFor(player, () -> compat.sendTitle(player, main, sub, fadeIn, stay, fadeOut), null);
    }
  }
}
