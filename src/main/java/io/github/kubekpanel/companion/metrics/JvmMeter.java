package io.github.kubekpanel.companion.metrics;

import io.github.kubekpanel.companion.json.Obj;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Map;

/** MXBeans only, safe off the main thread */
public final class JvmMeter {

  private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
  private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
  private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
  private final GcPauses pauses = GcPauses.tryCreate();
  private long lastGcCount;
  private long lastGcTime;

  public JvmMeter() {
    // baseline now so the 1st window lines up with the pause counter
    long[] totals = gcTotals();
    lastGcCount = totals[0];
    lastGcTime = totals[1];
  }

  public Map<String, Object> drain() {
    Map<String, Object> out = Obj.map();
    MemoryUsage heap = memory.getHeapMemoryUsage();
    out.put("heapUsed", heap.getUsed());
    out.put("heapCommitted", heap.getCommitted());
    out.put("heapMax", heap.getMax());
    out.put("nonHeapUsed", memory.getNonHeapMemoryUsage().getUsed());
    out.put("threads", threads.getThreadCount());
    out.put("daemonThreads", threads.getDaemonThreadCount());
    out.put("uptime", ManagementFactory.getRuntimeMXBean().getUptime());
    out.put("gc", gc());
    return out;
  }

  public void stop() {
    if (pauses != null) pauses.stop();
  }

  private Map<String, Object> gc() {
    long[] totals = gcTotals();
    Map<String, Object> out = Obj.map();
    // counters are cumulative, the panel wants the window
    out.put("count", totals[0] - lastGcCount);
    out.put("timeMs", totals[1] - lastGcTime);
    lastGcCount = totals[0];
    lastGcTime = totals[1];
    if (pauses != null) pauses.drainInto(out);
    return out;
  }

  private long[] gcTotals() {
    long count = 0;
    long time = 0;
    for (GarbageCollectorMXBean bean : collectors) {
      if (bean.getCollectionCount() > 0) count += bean.getCollectionCount();
      if (bean.getCollectionTime() > 0) time += bean.getCollectionTime();
    }
    return new long[] {count, time};
  }
}
