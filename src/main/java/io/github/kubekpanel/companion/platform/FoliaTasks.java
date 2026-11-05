package io.github.kubekpanel.companion.platform;

import java.lang.invoke.MethodHandle;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/** Folia schedulers via MethodHandles, BukkitScheduler just throws there */
public final class FoliaTasks implements Tasks {

  private static final String PKG = "io.papermc.paper.threadedregions.scheduler.";

  private final Plugin plugin;
  private final Object global;
  private final MethodHandle globalRun;
  private final MethodHandle globalDelayed;
  private final MethodHandle globalRate;
  private final MethodHandle entityScheduler;
  private final MethodHandle entityRun;
  private final MethodHandle taskCancel;

  public FoliaTasks(Plugin plugin) {
    this.plugin = plugin;
    Class<?> globalType = Compat.findClass(PKG + "GlobalRegionScheduler");
    Class<?> entityType = Compat.findClass(PKG + "EntityScheduler");
    Class<?> taskType = Compat.findClass(PKG + "ScheduledTask");
    if (globalType == null || entityType == null || taskType == null) {
      throw new IllegalStateException("Folia scheduler API not found");
    }
    MethodHandle getGlobal = Compat.virtual(Server.class, "getGlobalRegionScheduler", globalType);
    globalRun = Compat.virtual(globalType, "run", taskType, Plugin.class, Consumer.class);
    globalDelayed = Compat.virtual(globalType, "runDelayed", taskType, Plugin.class, Consumer.class, long.class);
    globalRate = Compat.virtual(globalType, "runAtFixedRate", taskType,
        Plugin.class, Consumer.class, long.class, long.class);
    entityScheduler = Compat.virtual(Entity.class, "getScheduler", entityType);
    entityRun = Compat.virtual(entityType, "run", taskType, Plugin.class, Consumer.class, Runnable.class);
    taskCancel = Compat.virtual(taskType, "cancel", Compat.findClass(PKG + "ScheduledTask$CancelledState"));
    if (getGlobal == null || globalRun == null || globalDelayed == null || globalRate == null
        || entityScheduler == null || entityRun == null || taskCancel == null) {
      throw new IllegalStateException("Folia scheduler API has an unexpected shape");
    }
    try {
      global = getGlobal.invoke(Bukkit.getServer());
    } catch (Throwable e) {
      throw new IllegalStateException("Folia global scheduler unavailable", e);
    }
  }

  @Override
  public Handle repeat(Runnable task, long delayTicks, long periodTicks) {
    try {
      // Folia rejects a 0 delay
      return wrap(globalRate.invoke(global, plugin, consumer(task), Math.max(1, delayTicks), periodTicks));
    } catch (Throwable e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public Handle later(Runnable task, long delayTicks) {
    try {
      return wrap(globalDelayed.invoke(global, plugin, consumer(task), Math.max(1, delayTicks)));
    } catch (Throwable e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void run(Runnable task) {
    try {
      globalRun.invoke(global, plugin, consumer(task));
    } catch (Throwable e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void runFor(Entity entity, Runnable task, Runnable retired) {
    try {
      Object scheduler = entityScheduler.invoke(entity);
      // null = entity already gone, Folia won't call retired itself
      Object handle = entityRun.invoke(scheduler, plugin, consumer(task), retired);
      if (handle == null && retired != null) retired.run();
    } catch (Throwable e) {
      if (retired != null) retired.run();
    }
  }

  private static Consumer<Object> consumer(Runnable task) {
    return scheduledTask -> task.run();
  }

  private Handle wrap(Object scheduledTask) {
    return () -> {
      try {
        taskCancel.invoke(scheduledTask);
      } catch (Throwable ignored) {
      }
    };
  }
}
