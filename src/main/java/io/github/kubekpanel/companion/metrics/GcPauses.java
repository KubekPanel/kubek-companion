package io.github.kubekpanel.companion.metrics;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

/** Real STW pauses from GC notifications, read via CompositeData so we don't link com.sun.management */
final class GcPauses implements NotificationListener {

  private static final String GC_NOTIFICATION = "com.sun.management.gc.notification";

  private final List<NotificationEmitter> emitters = new ArrayList<>();
  private int pauses;
  private long pauseMs;
  private long maxPauseMs;

  private GcPauses() {
  }

  static GcPauses tryCreate() {
    try {
      GcPauses tracker = new GcPauses();
      for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
        if (bean instanceof NotificationEmitter) {
          NotificationEmitter emitter = (NotificationEmitter) bean;
          emitter.addNotificationListener(tracker, null, null);
          tracker.emitters.add(emitter);
        }
      }
      return tracker.emitters.isEmpty() ? null : tracker;
    } catch (Throwable e) {
      return null;
    }
  }

  @Override
  public void handleNotification(Notification notification, Object handback) {
    if (!GC_NOTIFICATION.equals(notification.getType())) return;
    Object data = notification.getUserData();
    if (!(data instanceof CompositeData)) return;
    try {
      CompositeData info = (CompositeData) data;
      String name = String.valueOf(info.get("gcName"));
      // concurrent G1/ZGC/Shenandoah cycles don't stop the server
      if (name.contains("Concurrent") || name.contains("Cycles")) return;
      CompositeData gcInfo = (CompositeData) info.get("gcInfo");
      long duration = ((Number) gcInfo.get("duration")).longValue();
      synchronized (this) {
        pauses++;
        pauseMs += duration;
        if (duration > maxPauseMs) maxPauseMs = duration;
      }
    } catch (Throwable ignored) {
    }
  }

  synchronized void drainInto(Map<String, Object> out) {
    out.put("pauses", pauses);
    out.put("pauseMs", pauseMs);
    out.put("maxPauseMs", maxPauseMs);
    pauses = 0;
    pauseMs = 0;
    maxPauseMs = 0;
  }

  void stop() {
    for (NotificationEmitter emitter : emitters) {
      try {
        emitter.removeNotificationListener(this);
      } catch (Throwable ignored) {
      }
    }
    emitters.clear();
  }
}
