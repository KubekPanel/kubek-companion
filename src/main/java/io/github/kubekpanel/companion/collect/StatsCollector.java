package io.github.kubekpanel.companion.collect;

import io.github.kubekpanel.companion.Settings;
import io.github.kubekpanel.companion.json.Obj;
import io.github.kubekpanel.companion.metrics.JvmMeter;
import io.github.kubekpanel.companion.metrics.MsptMeter;
import io.github.kubekpanel.companion.metrics.TpsMeter;
import io.github.kubekpanel.companion.platform.Compat;
import io.github.kubekpanel.companion.platform.Tasks;
import io.github.kubekpanel.companion.transport.Channel;
import io.github.kubekpanel.companion.util.Format;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Periodic snapshot. Main thread only copies plain values, MSPT, JVM and JSON happen on the IO thread
 * On Folia players are read on their own region, TPS comes from the regions they stand in
 */
public final class StatsCollector {

  private static final long FOLIA_GATHER_TIMEOUT_TICKS = 40;

  private final Compat compat;
  private final Tasks tasks;
  private final Channel channel;
  private final Settings settings;
  private final Sessions sessions;
  private final TpsMeter tps;
  private final MsptMeter mspt;
  private final JvmMeter jvm;

  public StatsCollector(Compat compat, Tasks tasks, Channel channel, Settings settings, Sessions sessions,
      TpsMeter tps, MsptMeter mspt, JvmMeter jvm) {
    this.compat = compat;
    this.tasks = tasks;
    this.channel = channel;
    this.settings = settings;
    this.sessions = sessions;
    this.tps = tps;
    this.mspt = mspt;
    this.jvm = jvm;
  }

  /** Main thread, global region on Folia */
  public void collect() {
    long ts = System.currentTimeMillis();
    List<Map<String, Object>> worlds = worlds();
    if (compat.folia) {
      collectFolia(ts, worlds);
      return;
    }
    Collection<? extends Player> online = Bukkit.getOnlinePlayers();
    List<Map<String, Object>> players = new ArrayList<>(online.size());
    Location scratch = null;
    for (Player player : online) {
      if (scratch == null) scratch = new Location(player.getWorld(), 0, 0, 0);
      players.add(player(player, scratch));
    }
    publish(ts, worlds, players, tps.read(), null);
  }

  private void publish(long ts, List<Map<String, Object>> worlds, List<Map<String, Object>> players,
      double[] tpsValues, Map<String, Object> regionMspt) {
    int maxPlayers = Bukkit.getMaxPlayers();
    int interval = settings.intervalSec;
    channel.send(() -> {
      Map<String, Object> out = Obj.map();
      out.put("t", "stats");
      out.put("ts", ts);
      out.put("intervalSec", interval);
      out.put("tps", tpsValues);
      out.put("tpsSource", tps.source());
      out.put("mspt", regionMspt != null ? regionMspt : mspt.drain(tpsValues));
      out.put("jvm", jvm.drain());
      out.put("online", players.size());
      out.put("maxPlayers", maxPlayers);
      out.put("worlds", worlds);
      out.put("players", players);
      return out;
    });
  }

  private List<Map<String, Object>> worlds() {
    List<World> all = Bukkit.getWorlds();
    List<Map<String, Object>> out = new ArrayList<>(all.size());
    for (World world : all) {
      Map<String, Object> w = Obj.map();
      w.put("name", world.getName());
      w.put("env", world.getEnvironment().name());
      try {
        w.put("players", world.getPlayers().size());
      } catch (Throwable ignored) {
        w.put("players", null);
      }
      Integer chunks = compat.chunkCount(world);
      Integer entities = compat.entityCount(world);
      // Paper counters are O(1). The Spigot fallbacks aren't allowed off-region on Folia
      if (!compat.folia) {
        if (chunks == null) chunks = world.getLoadedChunks().length;
        if (entities == null) entities = world.getEntities().size();
      }
      w.put("chunks", chunks);
      w.put("entities", entities);
      // no tile counter on Spigot, scanning would snapshot every tile
      w.put("tiles", compat.tileCount(world));
      w.put("tickingTiles", compat.folia ? null : compat.tickingTileCount(world));
      out.add(w);
    }
    return out;
  }

  private Map<String, Object> player(Player player, Location scratch) {
    Map<String, Object> p = Obj.map();
    p.put("uuid", player.getUniqueId().toString());
    p.put("name", player.getName());
    p.put("world", player.getWorld().getName());
    p.put("gamemode", player.getGameMode().name());
    p.put("ping", compat.ping(player));
    p.put("since", sessions.since(player.getUniqueId()));
    if (settings.coords) {
      Location loc = player.getLocation(scratch);
      p.put("x", Format.round(loc.getX(), 1));
      p.put("y", Format.round(loc.getY(), 1));
      p.put("z", Format.round(loc.getZ(), 1));
    }
    return p;
  }

  private void collectFolia(long ts, List<Map<String, Object>> worlds) {
    Collection<? extends Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
    FoliaGather gather = new FoliaGather(online.size());
    Runnable arrived = () -> {
      if (gather.arrive()) finishFolia(ts, worlds, gather);
    };

    // spawn regions cover an empty server
    for (World world : Bukkit.getWorlds()) {
      Location spawn = world.getSpawnLocation();
      gather.mergeTps(compat.regionTps(world, spawn.getBlockX() >> 4, spawn.getBlockZ() >> 4));
    }

    for (Player player : online) {
      tasks.runFor(player, () -> {
        try {
          gather.players.add(player(player, new Location(player.getWorld(), 0, 0, 0)));
          double[] region = compat.hasRegionTps()
              ? compat.regionTps(player.getLocation())
              : compat.serverTps();
          gather.mergeTps(region);
          gather.mergeMspt(compat.averageTickTime());
        } finally {
          arrived.run();
        }
      }, arrived);
    }
    if (online.isEmpty()) {
      finishFolia(ts, worlds, gather);
    } else {
      tasks.later(() -> finishFolia(ts, worlds, gather), FOLIA_GATHER_TIMEOUT_TICKS);
    }
  }

  private void finishFolia(long ts, List<Map<String, Object>> worlds, FoliaGather gather) {
    if (!gather.finished.compareAndSet(false, true)) return;
    publish(ts, worlds, new ArrayList<>(gather.players), gather.tps(), gather.mspt());
  }

  /** Worst region wins, the panel shows where it hurts */
  private static final class FoliaGather {
    final ConcurrentLinkedQueue<Map<String, Object>> players = new ConcurrentLinkedQueue<>();
    final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicInteger remaining;
    private double[] worstTps;
    private double worstMspt = -1;
    private int msptSamples;

    FoliaGather(int expected) {
      remaining = new AtomicInteger(expected);
    }

    boolean arrive() {
      return remaining.decrementAndGet() == 0;
    }

    synchronized void mergeTps(double[] raw) {
      if (raw == null || raw.length < 3) return;
      // region arrays are 5s/15s/1m/5m/15m, getTPS on a region thread is 1m/5m/15m
      int offset = raw.length >= 5 ? 2 : 0;
      if (worstTps == null) {
        worstTps = new double[] {raw[offset], raw[offset + 1], raw[offset + 2]};
        return;
      }
      for (int i = 0; i < 3; i++) worstTps[i] = Math.min(worstTps[i], raw[offset + i]);
    }

    synchronized void mergeMspt(Double avg) {
      if (avg == null) return;
      msptSamples++;
      if (avg > worstMspt) worstMspt = avg;
    }

    synchronized double[] tps() {
      if (worstTps == null) return null;
      double[] out = new double[3];
      for (int i = 0; i < 3; i++) out[i] = Format.round(worstTps[i], 2);
      return out;
    }

    synchronized Map<String, Object> mspt() {
      if (worstMspt < 0) return null;
      Map<String, Object> out = Obj.map();
      out.put("source", "region");
      out.put("avg", Format.round(worstMspt, 2));
      // one reading per player, several players can share a region
      out.put("samples", msptSamples);
      return out;
    }
  }
}
