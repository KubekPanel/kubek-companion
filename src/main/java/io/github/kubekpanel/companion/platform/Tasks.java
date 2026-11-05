package io.github.kubekpanel.companion.platform;

import org.bukkit.entity.Entity;

/** Same scheduling API for Bukkit and Folia */
public interface Tasks {

  interface Handle {
    void cancel();
  }

  /** Main thread, global region on Folia */
  Handle repeat(Runnable task, long delayTicks, long periodTicks);

  Handle later(Runnable task, long delayTicks);

  void run(Runnable task);

  /** On the entity's thread, retired runs instead if the entity is gone */
  void runFor(Entity entity, Runnable task, Runnable retired);
}
