package io.github.kubekpanel.companion;

import io.github.kubekpanel.companion.actions.Countdown;
import io.github.kubekpanel.companion.actions.Maintenance;
import io.github.kubekpanel.companion.actions.Messages;
import io.github.kubekpanel.companion.collect.LagReport;
import io.github.kubekpanel.companion.collect.PluginList;
import io.github.kubekpanel.companion.collect.Sessions;
import io.github.kubekpanel.companion.collect.StatsCollector;
import io.github.kubekpanel.companion.events.ChatEvents;
import io.github.kubekpanel.companion.events.PlayerEvents;
import io.github.kubekpanel.companion.json.Obj;
import io.github.kubekpanel.companion.metrics.JvmMeter;
import io.github.kubekpanel.companion.metrics.MsptMeter;
import io.github.kubekpanel.companion.metrics.TpsMeter;
import io.github.kubekpanel.companion.platform.BukkitTasks;
import io.github.kubekpanel.companion.platform.Compat;
import io.github.kubekpanel.companion.platform.FoliaTasks;
import io.github.kubekpanel.companion.platform.Tasks;
import io.github.kubekpanel.companion.transport.Channel;
import io.github.kubekpanel.companion.transport.Inbound;
import io.github.kubekpanel.companion.transport.Signer;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

/** No KUBEK_COMPANION_TOKEN in env = fully idle, no listeners, tasks or output. Kubek sets it on launch */
public final class KubekCompanion extends JavaPlugin implements Inbound.Handler {

  public static final int PROTOCOL = 1;
  private static final String TOKEN_ENV = "KUBEK_COMPANION_TOKEN";
  private static final int MIN_TOKEN_LENGTH = 16;
  private static final int DEFAULT_REPORT_LIMIT = 10;
  private static final int MAX_REPORT_LIMIT = 100;
  // full entity + chunk scan on the main thread, a busy panel shouldn't turn it into lag
  private static final long REPORT_COOLDOWN_MS = 10_000;

  private final Settings settings = new Settings();
  private Compat compat;
  private Tasks tasks;
  private Channel channel;
  private Sessions sessions;
  private TpsMeter tps;
  private MsptMeter mspt;
  private JvmMeter jvm;
  private StatsCollector stats;
  private LagReport lagReport;
  private PluginList pluginList;
  private PlayerEvents playerEvents;
  private ChatEvents chatEvents;
  private Maintenance maintenance;
  private Countdown countdown;
  private Messages messages;
  private Inbound inbound;
  private Tasks.Handle statsTask;
  private boolean active;
  private volatile boolean warned;
  // lag_report cache, touched only on the main thread / global region
  private Map<String, Object> lastReport;
  private int lastReportLimit;
  private long lastReportAt;

  @Override
  public void onEnable() {
    String token = System.getenv(TOKEN_ENV);
    if (token == null || token.trim().length() < MIN_TOKEN_LENGTH) return;
    try {
      start(token.trim());
    } catch (Throwable e) {
      // some odd core broke us, don't take the server down with it
      getLogger().log(Level.WARNING, "Kubek companion could not start", e);
      shutdown(false);
    }
  }

  @Override
  public void onDisable() {
    if (active) shutdown(true);
  }

  private void start(String token) {
    Signer signer = new Signer(token);
    compat = new Compat();
    tasks = compat.folia ? new FoliaTasks(this) : new BukkitTasks(this);
    channel = new Channel(signer, getLogger());
    channel.start();
    active = true;

    sessions = new Sessions(compat);
    sessions.seedOnline();
    tps = new TpsMeter(compat, tasks);
    mspt = new MsptMeter(this, compat);
    jvm = new JvmMeter();
    stats = new StatsCollector(compat, tasks, channel, settings, sessions, tps, mspt, jvm);
    lagReport = new LagReport(compat);
    pluginList = new PluginList(compat);
    countdown = new Countdown(tasks, channel);
    messages = new Messages(compat, tasks);
    maintenance = new Maintenance(tasks);
    maintenance.register(this);
    chatEvents = new ChatEvents(this, channel);
    playerEvents = new PlayerEvents(channel, sessions, compat, countdown::onJoin);
    playerEvents.register(this);
    inbound = new Inbound(signer, this, getLogger());
    Bukkit.getPluginManager().registerEvents(inbound, this);

    channel.send(hello());
    // 1st tick = server fully started
    tasks.later(() -> channel.send(message("ready")), 1);
    scheduleStats(5 * 20L);
  }

  private void shutdown(boolean notify) {
    if (statsTask != null) statsTask.cancel();
    statsTask = null;
    if (countdown != null) countdown.cancel(false);
    if (chatEvents != null) chatEvents.setEnabled(false);
    if (playerEvents != null) playerEvents.unregister();
    if (maintenance != null) maintenance.unregister();
    if (inbound != null) HandlerList.unregisterAll(inbound);
    if (mspt != null) mspt.stop();
    if (tps != null) tps.stop();
    if (jvm != null) jvm.stop();
    if (channel != null) {
      if (notify) channel.send(message("stopping"));
      channel.stop();
    }
    active = false;
  }

  private void scheduleStats(long delayTicks) {
    if (statsTask != null) statsTask.cancel();
    statsTask = tasks.repeat(this::collect, delayTicks, settings.intervalSec * 20L);
  }

  private void collect() {
    try {
      maintenance.refresh();
      stats.collect();
    } catch (Throwable e) {
      logFailure("Stats snapshot failed", e);
    }
  }

  /** Called on the console thread, work hops to main / global region */
  @Override
  public void handle(String id, String type, Obj request) {
    tasks.run(() -> {
      try {
        route(id, type, request);
      } catch (Throwable e) {
        logFailure("Panel request failed: " + type, e);
        fail(id, "internal_error");
      }
    });
  }

  private void route(String id, String type, Obj request) {
    switch (type) {
      case "hello":
        channel.send(hello());
        ok(id, null);
        break;
      case "config":
        boolean intervalChanged = settings.apply(request);
        chatEvents.setEnabled(settings.chat);
        if (intervalChanged) scheduleStats(settings.intervalSec * 20L);
        ok(id, settings.toMap());
        break;
      case "stats":
        collect();
        ok(id, null);
        break;
      case "lag_report":
        lagReport(id, (int) Math.max(1, Math.min(MAX_REPORT_LIMIT, request.num("limit", DEFAULT_REPORT_LIMIT))));
        break;
      case "plugins":
        ok(id, pluginList.build());
        break;
      case "broadcast":
        String text = request.str("message", null);
        if (text == null) {
          fail(id, "missing_message");
          return;
        }
        messages.broadcast(text);
        ok(id, null);
        break;
      case "title":
        messages.title(request.str("title", ""), request.str("subtitle", ""),
            (int) request.num("fadeIn", 10), (int) request.num("stay", 70), (int) request.num("fadeOut", 20));
        ok(id, null);
        break;
      case "countdown":
        int seconds = (int) request.num("seconds", 0);
        if (seconds <= 0) {
          fail(id, "bad_seconds");
          return;
        }
        countdown.start(id, seconds, request.str("message", "&eServer restarts in {time}"),
            request.bool("bossBar", true));
        ok(id, null);
        break;
      case "countdown_cancel":
        countdown.cancel(true);
        ok(id, null);
        break;
      case "maintenance":
        maintenance.apply(request);
        ok(id, maintenance.describe());
        break;
      default:
        fail(id, "unknown_type");
    }
  }

  /** Within the cooldown the same limit gets the cached report, a different one gets rate_limited */
  private void lagReport(String id, int limit) {
    long now = System.currentTimeMillis();
    if (lastReport != null && now - lastReportAt < REPORT_COOLDOWN_MS) {
      if (limit == lastReportLimit) {
        ok(id, lastReport);
      } else {
        fail(id, "rate_limited");
      }
      return;
    }
    lastReport = lagReport.build(limit);
    lastReportLimit = limit;
    lastReportAt = now;
    ok(id, lastReport);
  }

  private void ok(String id, Object data) {
    Map<String, Object> out = result(id, true);
    if (data != null) out.put("data", data);
    channel.send(out);
  }

  private void fail(String id, String error) {
    Map<String, Object> out = result(id, false);
    out.put("error", error);
    channel.send(out);
  }

  private static Map<String, Object> result(String id, boolean ok) {
    Map<String, Object> out = message("result");
    out.put("id", id);
    out.put("ok", ok);
    return out;
  }

  private static Map<String, Object> message(String type) {
    Map<String, Object> out = Obj.map();
    out.put("t", type);
    out.put("ts", System.currentTimeMillis());
    return out;
  }

  /** 1st failure goes to console so it gets reported, the rest at FINE */
  private void logFailure(String what, Throwable e) {
    Level level = warned ? Level.FINE : Level.WARNING;
    warned = true;
    getLogger().log(level, what, e);
  }

  @SuppressWarnings("deprecation")
  private Map<String, Object> hello() {
    Map<String, Object> server = Obj.map();
    server.put("name", Bukkit.getName());
    server.put("version", Bukkit.getVersion());
    server.put("bukkitVersion", Bukkit.getBukkitVersion());
    server.put("onlineMode", Bukkit.getOnlineMode());

    Map<String, Object> java = Obj.map();
    java.put("version", System.getProperty("java.version"));
    java.put("vendor", System.getProperty("java.vendor"));
    java.put("vm", System.getProperty("java.vm.name"));

    Map<String, Object> caps = Obj.map();
    caps.put("tps", tps.source());
    caps.put("mspt", mspt.source());
    caps.put("folia", compat.folia);
    caps.put("ping", compat.hasPing());
    caps.put("tileCounters", compat.hasWorldTileCounters());
    caps.put("bossBar", countdown.hasBossBar());

    Map<String, Object> out = message("hello");
    out.put("protocol", PROTOCOL);
    out.put("plugin", getDescription().getVersion());
    out.put("server", server);
    out.put("java", java);
    out.put("caps", caps);
    out.put("settings", settings.toMap());
    out.put("maintenance", maintenance.describe());
    return out;
  }
}
