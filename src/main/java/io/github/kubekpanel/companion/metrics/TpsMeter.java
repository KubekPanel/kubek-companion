package io.github.kubekpanel.companion.metrics;

import io.github.kubekpanel.companion.platform.Compat;
import io.github.kubekpanel.companion.platform.Tasks;
import io.github.kubekpanel.companion.util.Format;

/** 1m/5m/15m TPS from the cheapest source. Folia has no global TPS, StatsCollector asks regions */
public final class TpsMeter {

  public enum Source {
    PAPER, SPIGOT, FOLIA, COUNTER
  }

  private static final double[] WINDOWS_SEC = {60, 300, 900};
  private static final int COUNTER_PERIOD = 20;

  private final Compat compat;
  private final Source source;
  private Tasks.Handle counterTask;

  // counter mode, main thread only
  private long lastNanos;
  private final double[] averages = {20, 20, 20};

  public TpsMeter(Compat compat, Tasks tasks) {
    this.compat = compat;
    if (compat.folia) {
      source = Source.FOLIA;
    } else if (compat.hasServerTps()) {
      source = Source.PAPER;
    } else if (compat.hasRecentTps()) {
      source = Source.SPIGOT;
    } else {
      source = Source.COUNTER;
      // once a sec, not per tick
      counterTask = tasks.repeat(this::sample, COUNTER_PERIOD, COUNTER_PERIOD);
    }
  }

  public Source source() {
    return source;
  }

  /** 1m/5m/15m, null if unknown */
  public double[] read() {
    double[] raw;
    switch (source) {
      case PAPER:
        raw = compat.serverTps();
        break;
      case SPIGOT:
        raw = compat.recentTps();
        break;
      case COUNTER:
        synchronized (averages) {
          raw = averages.clone();
        }
        break;
      default:
        return null;
    }
    if (raw == null || raw.length < 3) return null;
    return new double[] {Format.round(raw[0], 2), Format.round(raw[1], 2), Format.round(raw[2], 2)};
  }

  public void stop() {
    if (counterTask != null) counterTask.cancel();
  }

  private void sample() {
    long now = System.nanoTime();
    if (lastNanos != 0) {
      double elapsedSec = (now - lastNanos) / 1e9;
      if (elapsedSec > 0) {
        double tps = Math.min(20, COUNTER_PERIOD / elapsedSec);
        synchronized (averages) {
          for (int i = 0; i < WINDOWS_SEC.length; i++) {
            double alpha = 1 - Math.exp(-elapsedSec / WINDOWS_SEC[i]);
            averages[i] += alpha * (tps - averages[i]);
          }
        }
      }
    }
    lastNanos = now;
  }
}
