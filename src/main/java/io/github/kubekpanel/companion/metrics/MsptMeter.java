package io.github.kubekpanel.companion.metrics;

import io.github.kubekpanel.companion.json.Obj;
import io.github.kubekpanel.companion.platform.Compat;
import io.github.kubekpanel.companion.util.Format;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * MSPT over the last stats window
 * EVENTS: exact, from Paper's ServerTickEndEvent. AVERAGE: Paper's 5s avg. SAMPLED: Spigot, guessed from main thread state
 */
public final class MsptMeter implements Listener {

  public enum Source {
    EVENTS, AVERAGE, SAMPLED, NONE
  }

  private static final String TICK_END_EVENT = "com.destroystokyo.paper.event.server.ServerTickEndEvent";
  // 5 min at 20 TPS = longest interval. Extra ticks still count in avg and max, just not in percentiles
  private static final int CAPACITY = 6000;

  private final Compat compat;
  private final Source source;

  // EVENTS, written on main, read on IO
  private final double[] samples = new double[CAPACITY];
  private int count;
  private double max;
  private double sum;
  private long total;

  // SAMPLED
  private Thread sampler;
  private volatile boolean sampling;
  private long busy;
  private long seen;

  public MsptMeter(Plugin plugin, Compat compat) {
    this.compat = compat;
    if (compat.folia) {
      // regions tick on their own, StatsCollector reads them
      source = Source.NONE;
    } else if (registerTickEvents(plugin)) {
      source = Source.EVENTS;
    } else if (compat.hasAverageTickTime()) {
      source = Source.AVERAGE;
    } else {
      source = Source.SAMPLED;
      startSampler(Thread.currentThread());
    }
  }

  public Source source() {
    return source;
  }

  /** Resets the window. tps is only used by SAMPLED */
  public Map<String, Object> drain(double[] tps) {
    switch (source) {
      case EVENTS:
        return drainEvents();
      case AVERAGE:
        return average();
      case SAMPLED:
        return drainSampled(tps);
      default:
        return null;
    }
  }

  public void stop() {
    HandlerList.unregisterAll(this);
    sampling = false;
    if (sampler != null) sampler.interrupt();
  }

  private boolean registerTickEvents(Plugin plugin) {
    Class<?> raw = Compat.findClass(TICK_END_EVENT);
    if (raw == null || !Event.class.isAssignableFrom(raw)) return false;
    MethodHandle duration = Compat.virtual(raw, "getTickDuration", double.class);
    if (duration == null) return false;
    @SuppressWarnings("unchecked")
    Class<? extends Event> type = (Class<? extends Event>) raw;
    try {
      Bukkit.getPluginManager().registerEvent(type, this, EventPriority.MONITOR, (listener, event) -> {
        if (!raw.isInstance(event)) return;
        try {
          record((double) duration.invoke(event));
        } catch (Throwable ignored) {
          // runs inside the tick loop, must never throw
        }
      }, plugin, false);
      return true;
    } catch (Throwable e) {
      return false;
    }
  }

  private void record(double ms) {
    synchronized (samples) {
      if (count < CAPACITY) samples[count++] = ms;
      if (ms > max) max = ms;
      sum += ms;
      total++;
    }
  }

  private Map<String, Object> drainEvents() {
    double[] copy;
    double peak;
    double totalMs;
    long ticks;
    synchronized (samples) {
      copy = Arrays.copyOf(samples, count);
      peak = max;
      totalMs = sum;
      ticks = total;
      count = 0;
      max = 0;
      sum = 0;
      total = 0;
    }
    if (ticks == 0) return compat.hasAverageTickTime() ? average() : null;
    Arrays.sort(copy);
    Map<String, Object> out = Obj.map();
    out.put("source", "events");
    out.put("avg", Format.round(totalMs / ticks, 2));
    out.put("p50", Format.round(percentile(copy, 0.50), 2));
    out.put("p95", Format.round(percentile(copy, 0.95), 2));
    out.put("p99", Format.round(percentile(copy, 0.99), 2));
    out.put("max", Format.round(peak, 2));
    out.put("ticks", ticks);
    return out;
  }

  private Map<String, Object> average() {
    Double avg = compat.averageTickTime();
    if (avg == null) return null;
    Map<String, Object> out = Obj.map();
    out.put("source", "average");
    out.put("avg", Format.round(avg, 2));
    return out;
  }

  private Map<String, Object> drainSampled(double[] tps) {
    long busyNow;
    long seenNow;
    synchronized (this) {
      busyNow = busy;
      seenNow = seen;
      busy = 0;
      seen = 0;
    }
    if (seenNow == 0) return null;
    double load = (double) busyNow / seenNow;
    double rate = tps != null && tps[0] > 0.1 ? Math.min(20, tps[0]) : 20;
    Map<String, Object> out = Obj.map();
    out.put("source", "sampled");
    out.put("avg", Format.round(load * 1000 / rate, 2));
    out.put("load", Format.round(load, 2));
    return out;
  }

  /**
   * Main thread sleeps between ticks and is RUNNABLE during one. ~100 samples/s,
   * jittered so we don't lock onto the 50ms tick phase
   */
  private void startSampler(Thread main) {
    sampling = true;
    sampler = new Thread(() -> {
      ThreadLocalRandom random = ThreadLocalRandom.current();
      while (sampling && main.isAlive()) {
        LockSupport.parkNanos(random.nextLong(7_000_000L, 13_000_000L));
        Thread.State state = main.getState();
        boolean working = state == Thread.State.RUNNABLE || state == Thread.State.BLOCKED;
        synchronized (this) {
          seen++;
          if (working) busy++;
        }
      }
    }, "KubekCompanion-Sampler");
    sampler.setDaemon(true);
    sampler.setPriority(Thread.MIN_PRIORITY);
    sampler.start();
  }

  private static double percentile(double[] sorted, double p) {
    if (sorted.length == 0) return 0;
    int index = (int) Math.ceil(p * sorted.length) - 1;
    return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
  }
}
