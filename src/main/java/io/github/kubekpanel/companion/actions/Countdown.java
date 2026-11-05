package io.github.kubekpanel.companion.actions;

import io.github.kubekpanel.companion.json.Obj;
import io.github.kubekpanel.companion.platform.Compat;
import io.github.kubekpanel.companion.platform.Tasks;
import io.github.kubekpanel.companion.transport.Channel;
import io.github.kubekpanel.companion.util.Format;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Array;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;

/** Only counts down. The panel does the actual restart/stop after countdown_done */
public final class Countdown {

  private static final int[] MILESTONES = {600, 300, 120, 60, 30, 15, 10, 5, 4, 3, 2, 1};

  private final Tasks tasks;
  private final Channel channel;
  private final BossBars bars = BossBars.tryCreate();
  private Tasks.Handle ticker;
  private Object bar;
  private String id;
  private String template;
  private int total;
  private int remaining;

  public Countdown(Tasks tasks, Channel channel) {
    this.tasks = tasks;
    this.channel = channel;
  }

  public boolean hasBossBar() {
    return bars != null;
  }

  /** Main thread or global region */
  public synchronized void start(String countdownId, int seconds, String message, boolean bossBar) {
    // the panel may still wait on the old one, tell it that one's gone
    cancel(true);
    id = countdownId;
    template = Format.color(message);
    total = Math.max(1, seconds);
    remaining = total;
    if (bossBar && bars != null) {
      bar = bars.create(format(remaining));
      if (bar != null) {
        for (Player player : Bukkit.getOnlinePlayers()) bars.add(bar, player);
      }
    }
    announce();
    ticker = tasks.repeat(this::tick, 20, 20);
  }

  public synchronized void cancel(boolean notify) {
    if (ticker == null) return;
    ticker.cancel();
    ticker = null;
    if (bar != null) bars.removeAll(bar);
    bar = null;
    if (notify) emit("countdown_cancelled");
  }

  /** So late joiners see the bar too */
  public synchronized void onJoin(Player player) {
    if (bar != null) bars.add(bar, player);
  }

  private synchronized void tick() {
    if (ticker == null) return;
    remaining--;
    if (remaining <= 0) {
      cancel(false);
      emit("countdown_done");
      return;
    }
    if (bar != null) {
      bars.update(bar, format(remaining), (double) remaining / total);
    }
    for (int milestone : MILESTONES) {
      if (milestone == remaining) {
        announce();
        break;
      }
    }
  }

  private void announce() {
    Bukkit.broadcastMessage(format(remaining));
  }

  private String format(int seconds) {
    String time = seconds >= 60
        ? (seconds / 60) + ":" + (seconds % 60 < 10 ? "0" : "") + (seconds % 60)
        : String.valueOf(seconds);
    return template.replace("{s}", String.valueOf(seconds)).replace("{time}", time);
  }

  private void emit(String type) {
    Map<String, Object> e = Obj.map();
    e.put("type", type);
    e.put("ts", System.currentTimeMillis());
    e.put("id", id);
    channel.event(e);
  }

  /** org.bukkit.boss is 1.9+, not on the 1.8.8 classpath */
  private static final class BossBars {
    private final MethodHandle create;
    private final MethodHandle setTitle;
    private final MethodHandle setProgress;
    private final MethodHandle addPlayer;
    private final MethodHandle removeAll;
    private final Object color;
    private final Object style;
    private final Object noFlags;

    private BossBars(Class<?> barType, Class<?> colorType, Class<?> styleType, Class<?> flagType) {
      noFlags = Array.newInstance(flagType, 0);
      create = Compat.method(Server.class, "createBossBar", String.class, colorType, styleType, noFlags.getClass());
      setTitle = Compat.method(barType, "setTitle", String.class);
      setProgress = Compat.method(barType, "setProgress", double.class);
      addPlayer = Compat.method(barType, "addPlayer", Player.class);
      removeAll = Compat.method(barType, "removeAll");
      color = enumConstant(colorType, "YELLOW");
      style = enumConstant(styleType, "SOLID");
    }

    static BossBars tryCreate() {
      Class<?> barType = Compat.findClass("org.bukkit.boss.BossBar");
      Class<?> colorType = Compat.findClass("org.bukkit.boss.BarColor");
      Class<?> styleType = Compat.findClass("org.bukkit.boss.BarStyle");
      Class<?> flagType = Compat.findClass("org.bukkit.boss.BarFlag");
      if (barType == null || colorType == null || styleType == null || flagType == null) return null;
      BossBars bars = new BossBars(barType, colorType, styleType, flagType);
      boolean complete = bars.create != null && bars.setTitle != null && bars.setProgress != null
          && bars.addPlayer != null && bars.removeAll != null && bars.color != null && bars.style != null;
      return complete ? bars : null;
    }

    Object create(String title) {
      try {
        return create.invoke(Bukkit.getServer(), title, color, style, noFlags);
      } catch (Throwable e) {
        return null;
      }
    }

    // bar calls below are cosmetic, a failure (e.g. player already left) is fine to skip

    void add(Object bar, Player player) {
      try {
        addPlayer.invoke(bar, player);
      } catch (Throwable ignored) {
      }
    }

    void update(Object bar, String title, double progress) {
      try {
        setTitle.invoke(bar, title);
        setProgress.invoke(bar, Math.max(0, Math.min(1, progress)));
      } catch (Throwable ignored) {
      }
    }

    void removeAll(Object bar) {
      try {
        removeAll.invoke(bar);
      } catch (Throwable ignored) {
      }
    }

    private static Object enumConstant(Class<?> type, String name) {
      for (Object constant : type.getEnumConstants()) {
        if (((Enum<?>) constant).name().equals(name)) return constant;
      }
      return null;
    }
  }
}
