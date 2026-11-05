package io.github.kubekpanel.companion.collect;

import io.github.kubekpanel.companion.json.Obj;
import io.github.kubekpanel.companion.platform.Compat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitWorker;

/**
 * On-demand only. Entities are grouped by position, not Chunk.getEntities,
 * that one blocks the main thread on Spigot 1.17+ until entities load
 */
public final class LagReport {

  private static final int TYPES_PER_CHUNK = 3;

  private final Compat compat;

  public LagReport(Compat compat) {
    this.compat = compat;
  }

  /** Main thread only */
  public Map<String, Object> build(int limit) {
    Map<String, Object> out = Obj.map();
    out.put("ts", System.currentTimeMillis());
    if (compat.folia) {
      // entities live on region threads, can't scan them from here
      out.put("partial", true);
      out.put("worlds", Collections.emptyList());
      out.put("tasks", null);
      return out;
    }
    List<Map<String, Object>> worlds = new ArrayList<>();
    for (World world : Bukkit.getWorlds()) worlds.add(world(world, limit));
    out.put("worlds", worlds);
    out.put("tasks", tasks(limit));
    return out;
  }

  private Map<String, Object> world(World world, int limit) {
    List<Entity> entities = world.getEntities();
    Map<String, Integer> types = new HashMap<>();
    Map<Long, Integer> perChunk = new HashMap<>();
    Location scratch = new Location(world, 0, 0, 0);
    for (Entity entity : entities) {
      increment(types, entity.getType().name());
      entity.getLocation(scratch);
      increment(perChunk, key(scratch.getBlockX() >> 4, scratch.getBlockZ() >> 4));
    }

    List<Map.Entry<Long, Integer>> topChunks = top(perChunk, limit);
    Map<Long, Map<String, Integer>> chunkTypes = new HashMap<>();
    for (Map.Entry<Long, Integer> e : topChunks) chunkTypes.put(e.getKey(), new HashMap<>());
    // 2nd pass, only for chunks in the top
    for (Entity entity : entities) {
      entity.getLocation(scratch);
      Map<String, Integer> bucket = chunkTypes.get(key(scratch.getBlockX() >> 4, scratch.getBlockZ() >> 4));
      if (bucket != null) increment(bucket, entity.getType().name());
    }

    List<Map<String, Object>> chunks = new ArrayList<>();
    for (Map.Entry<Long, Integer> e : topChunks) {
      Map<String, Object> c = chunkEntry(e.getKey(), e.getValue());
      c.put("types", counts(top(chunkTypes.get(e.getKey()), TYPES_PER_CHUNK), "type"));
      chunks.add(c);
    }

    Map<String, Object> w = Obj.map();
    w.put("name", world.getName());
    w.put("chunks", world.getLoadedChunks().length);
    w.put("entities", entities.size());
    w.put("entityTypes", counts(top(types, limit), "type"));
    w.put("topEntityChunks", chunks);
    w.put("topTileChunks", tiles(world, limit));
    return w;
  }

  /** Paper only, Spigot would snapshot every tile */
  private List<Map<String, Object>> tiles(World world, int limit) {
    Map<Long, Integer> perChunk = new HashMap<>();
    Map<Long, Map<String, Integer>> chunkTypes = new HashMap<>();
    for (Chunk chunk : world.getLoadedChunks()) {
      Object[] tiles = compat.tilesNoSnapshot(chunk);
      if (tiles == null) return null;
      if (tiles.length == 0) continue;
      long key = key(chunk.getX(), chunk.getZ());
      perChunk.put(key, tiles.length);
      Map<String, Integer> kinds = new HashMap<>();
      for (Object tile : tiles) increment(kinds, tileName(tile));
      chunkTypes.put(key, kinds);
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map.Entry<Long, Integer> e : top(perChunk, limit)) {
      Map<String, Object> c = chunkEntry(e.getKey(), e.getValue());
      c.put("types", counts(top(chunkTypes.get(e.getKey()), TYPES_PER_CHUNK), "type"));
      out.add(c);
    }
    return out;
  }

  private static List<Map<String, Object>> tasks(int limit) {
    Map<String, int[]> perPlugin = new HashMap<>();
    for (BukkitTask task : Bukkit.getScheduler().getPendingTasks()) {
      int[] slot = slot(perPlugin, task.getOwner());
      slot[task.isSync() ? 0 : 1]++;
    }
    for (BukkitWorker worker : Bukkit.getScheduler().getActiveWorkers()) {
      slot(perPlugin, worker.getOwner())[2]++;
    }
    List<Map.Entry<String, int[]>> entries = new ArrayList<>(perPlugin.entrySet());
    entries.sort(Comparator.comparingInt((Map.Entry<String, int[]> e) -> -total(e.getValue())));
    List<Map<String, Object>> out = new ArrayList<>();
    for (int i = 0; i < entries.size() && i < limit; i++) {
      Map<String, Object> t = Obj.map();
      int[] v = entries.get(i).getValue();
      t.put("plugin", entries.get(i).getKey());
      t.put("sync", v[0]);
      t.put("async", v[1]);
      t.put("running", v[2]);
      out.add(t);
    }
    return out;
  }

  private static int[] slot(Map<String, int[]> map, Plugin owner) {
    return map.computeIfAbsent(owner == null ? "?" : owner.getName(), name -> new int[3]);
  }

  private static int total(int[] counts) {
    return counts[0] + counts[1] + counts[2];
  }

  private static String tileName(Object tile) {
    String name = tile.getClass().getSimpleName();
    return name.startsWith("Craft") ? name.substring(5) : name;
  }

  private static Map<String, Object> chunkEntry(long key, int count) {
    Map<String, Object> c = Obj.map();
    c.put("x", (int) (key >> 32));
    c.put("z", (int) key);
    c.put("count", count);
    return c;
  }

  private static long key(int x, int z) {
    return ((long) x << 32) | (z & 0xffffffffL);
  }

  private static <K> void increment(Map<K, Integer> map, K key) {
    map.merge(key, 1, Integer::sum);
  }

  private static <K> List<Map.Entry<K, Integer>> top(Map<K, Integer> map, int limit) {
    List<Map.Entry<K, Integer>> entries = new ArrayList<>(map.entrySet());
    entries.sort(Map.Entry.<K, Integer>comparingByValue().reversed());
    return entries.size() > limit ? entries.subList(0, limit) : entries;
  }

  private static List<Map<String, Object>> counts(List<Map.Entry<String, Integer>> entries, String label) {
    List<Map<String, Object>> out = new ArrayList<>(entries.size());
    for (Map.Entry<String, Integer> e : entries) {
      Map<String, Object> m = Obj.map();
      m.put(label, e.getKey());
      m.put("count", e.getValue());
      out.add(m);
    }
    return out;
  }
}
