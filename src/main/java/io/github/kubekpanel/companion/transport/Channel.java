package io.github.kubekpanel.companion.transport;

import io.github.kubekpanel.companion.json.Json;
import io.github.kubekpanel.companion.json.Obj;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Outgoing side, one daemon thread writes signed frames straight to fd 1, past log4j and latest.log
 * Frame: ESC ] 7768 ; kc1 ; id ; part ; parts ; sig ; chunk BEL LF, terminals hide OSC
 */
public final class Channel {

  private static final String PREFIX = "\u001b]7768;kc1;";
  private static final char END = '\u0007';
  // under PIPE_BUF (4096 on Linux) so log output can't land inside a frame
  private static final int MAX_CHUNK = 3500;
  private static final int QUEUE_LIMIT = 512;
  private static final int EVENT_BATCH = 64;
  private static final long EVENT_FLUSH_MS = 1000;

  private static final Object POISON = new Object();

  private final Signer signer;
  private final Logger logger;
  private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>(QUEUE_LIMIT);
  private final List<Map<String, Object>> events = new ArrayList<>();
  private final AtomicLong dropped = new AtomicLong();
  private final OutputStream out = new FileOutputStream(FileDescriptor.out);
  private long nextId;
  private long firstEventAt;
  private volatile boolean broken;
  private boolean warned;
  private Thread thread;

  public Channel(Signer signer, Logger logger) {
    this.signer = signer;
    this.logger = logger;
  }

  public void start() {
    thread = new Thread(this::loop, "KubekCompanion-IO");
    thread.setDaemon(true);
    thread.setPriority(Thread.MIN_PRIORITY);
    thread.start();
  }

  /** Flushes the queue, waits up to 2s so "stopping" still gets out */
  public void stop() {
    if (thread == null) return;
    queue.offer(POISON);
    try {
      thread.join(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    thread = null;
  }

  public void send(Map<String, Object> message) {
    offer(message);
  }

  /** Built on the IO thread to keep main thread work small */
  public void send(Supplier<Map<String, Object>> producer) {
    offer(producer);
  }

  /** Batched for up to 1s, no frame per chat line */
  public void event(Map<String, Object> event) {
    offer(new EventItem(event));
  }

  private void offer(Object item) {
    if (broken) return;
    if (!queue.offer(item)) dropped.incrementAndGet();
  }

  private void loop() {
    try {
      while (true) {
        Object item;
        if (events.isEmpty()) {
          item = queue.take();
        } else {
          long wait = EVENT_FLUSH_MS - (System.currentTimeMillis() - firstEventAt);
          item = wait > 0 ? queue.poll(wait, TimeUnit.MILLISECONDS) : null;
        }
        if (item == POISON) {
          drainAndFlush();
          return;
        }
        handle(item);
        if (shouldFlush()) flushEvents();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Also flushes on drops alone, otherwise they'd go unreported until the next event */
  private boolean shouldFlush() {
    if (events.isEmpty()) return dropped.get() > 0;
    return events.size() >= EVENT_BATCH || System.currentTimeMillis() - firstEventAt >= EVENT_FLUSH_MS;
  }

  private void drainAndFlush() {
    Object item;
    while ((item = queue.poll()) != null) {
      if (item != POISON) handle(item);
    }
    flushEvents();
  }

  @SuppressWarnings("unchecked")
  private void handle(Object item) {
    if (item == null) return;
    try {
      if (item instanceof EventItem) {
        if (events.isEmpty()) firstEventAt = System.currentTimeMillis();
        events.add(((EventItem) item).event);
        return;
      }
      Map<String, Object> message;
      if (item instanceof Supplier) {
        message = ((Supplier<Map<String, Object>>) item).get();
        if (message == null) return;
      } else {
        message = (Map<String, Object>) item;
      }
      write(message);
    } catch (RuntimeException e) {
      // only the 1st one goes to console, no spam
      logger.log(warned ? Level.FINE : Level.WARNING, "Failed to build a message", e);
      warned = true;
    }
  }

  private void flushEvents() {
    long lost = dropped.getAndSet(0);
    if (events.isEmpty() && lost == 0) return;
    Map<String, Object> batch = Obj.map();
    batch.put("t", "events");
    batch.put("ts", System.currentTimeMillis());
    batch.put("items", new ArrayList<>(events));
    if (lost > 0) batch.put("dropped", lost);
    events.clear();
    write(batch);
  }

  private void write(Map<String, Object> message) {
    if (broken) return;
    String payload = Json.write(message);
    String sig = signer.sign(payload);
    String id = Long.toString(nextId++, 36);
    int parts = Math.max(1, (payload.length() + MAX_CHUNK - 1) / MAX_CHUNK);
    try {
      for (int part = 0; part < parts; part++) {
        int from = part * MAX_CHUNK;
        String chunk = payload.substring(from, Math.min(payload.length(), from + MAX_CHUNK));
        String frame = PREFIX + id + ';' + part + ';' + parts + ';' + sig + ';' + chunk + END + '\n';
        // one write() per frame, atomic on a pipe
        out.write(frame.getBytes(StandardCharsets.US_ASCII));
      }
      out.flush();
    } catch (IOException e) {
      // stdout closed, nobody's listening
      broken = true;
      queue.clear();
    }
  }

  private static final class EventItem {
    final Map<String, Object> event;

    EventItem(Map<String, Object> event) {
      this.event = event;
    }
  }
}
