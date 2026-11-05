package io.github.kubekpanel.companion.platform;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Plain BukkitScheduler, all on the main thread */
public final class BukkitTasks implements Tasks {

  private final Plugin plugin;

  public BukkitTasks(Plugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public Handle repeat(Runnable task, long delayTicks, long periodTicks) {
    return wrap(Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks));
  }

  @Override
  public Handle later(Runnable task, long delayTicks) {
    return wrap(Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks));
  }

  @Override
  public void run(Runnable task) {
    if (Bukkit.isPrimaryThread()) {
      task.run();
    } else {
      Bukkit.getScheduler().runTask(plugin, task);
    }
  }

  @Override
  public void runFor(Entity entity, Runnable task, Runnable retired) {
    run(task);
  }

  private static Handle wrap(BukkitTask task) {
    return task::cancel;
  }
}
