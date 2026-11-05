package io.github.kubekpanel.companion.platform;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginDescriptionFile;

/** Anything newer than the 1.8.8 API, looked up once. Missing method = null handle = "unknown" for callers */
public final class Compat {

  private static final MethodHandles.Lookup LOOKUP = MethodHandles.publicLookup();

  public final boolean folia;

  // Paper 1.9.4+, throws off-region on Folia
  private final MethodHandle serverTps;
  // Paper 1.15.2+
  private final MethodHandle averageTickTime;
  // Folia 1.21.4+, safe from any thread
  private final MethodHandle regionTpsAt;
  private final MethodHandle regionTpsLocation;

  // Paper 1.11.2+, O(1) counters
  private final MethodHandle worldChunkCount;
  private final MethodHandle worldEntityCount;
  private final MethodHandle worldTileCount;
  private final MethodHandle worldTickingTileCount;
  // Paper, tiles w/o BlockState snapshots
  private final MethodHandle chunkTilesNoSnapshot;

  // 1.17+ (late 1.16.5 builds too)
  private final MethodHandle playerPing;
  // Paper and PaperSpigot 1.8.8+
  private final MethodHandle spigotPing;
  // Paper 1.13.2+
  private final MethodHandle lastLogin;
  // 1.11+
  private final MethodHandle sendTitleTimed;

  // Paper 1.16.5+
  private final MethodHandle quitReason;
  private final MethodHandle kickCause;
  // 1.13+
  private final MethodHandle pluginApiVersion;

  // Spigot fallback. Paper dropped the field in 1.21.9, it has getTPS anyway
  private final Object minecraftServer;
  private final Field recentTps;

  public Compat() {
    folia = classExists("io.papermc.paper.threadedregions.RegionizedServer");

    serverTps = virtual(Server.class, "getTPS", double[].class);
    averageTickTime = virtual(Server.class, "getAverageTickTime", double.class);
    regionTpsAt = virtual(Server.class, "getRegionTPS", double[].class, World.class, int.class, int.class);
    regionTpsLocation = virtual(Server.class, "getRegionTPS", double[].class, Location.class);

    worldChunkCount = virtual(World.class, "getChunkCount", int.class);
    worldEntityCount = virtual(World.class, "getEntityCount", int.class);
    worldTileCount = virtual(World.class, "getTileEntityCount", int.class);
    worldTickingTileCount = virtual(World.class, "getTickableTileEntityCount", int.class);
    chunkTilesNoSnapshot = chunkTilesHandle();

    playerPing = virtual(Player.class, "getPing", int.class);
    spigotPing = virtual(Player.Spigot.class, "getPing", int.class);
    lastLogin = virtual(Player.class, "getLastLogin", long.class);
    sendTitleTimed = virtual(Player.class, "sendTitle", void.class,
        String.class, String.class, int.class, int.class, int.class);

    quitReason = method(PlayerQuitEvent.class, "getReason");
    kickCause = method(PlayerKickEvent.class, "getCause");
    pluginApiVersion = virtual(PluginDescriptionFile.class, "getAPIVersion", String.class);

    Object mc = null;
    Field tps = null;
    if (serverTps == null && !folia) {
      try {
        Object craftServer = Bukkit.getServer();
        Method getServer = craftServer.getClass().getMethod("getServer");
        mc = getServer.invoke(craftServer);
        tps = findField(mc.getClass(), "recentTps");
        if (tps != null && tps.getType() != double[].class) tps = null;
      } catch (Throwable ignored) {
        mc = null;
        tps = null;
      }
    }
    minecraftServer = tps != null ? mc : null;
    recentTps = tps;
  }

  public boolean hasServerTps() {
    return serverTps != null && !folia;
  }

  public boolean hasRecentTps() {
    return recentTps != null;
  }

  public boolean hasRegionTps() {
    return regionTpsAt != null;
  }

  public boolean hasAverageTickTime() {
    return averageTickTime != null;
  }

  public boolean hasPing() {
    return playerPing != null || spigotPing != null;
  }

  public boolean hasWorldTileCounters() {
    return worldTileCount != null;
  }

  /** Folia: region thread only */
  public double[] serverTps() {
    if (serverTps == null) return null;
    try {
      return (double[]) serverTps.invoke(Bukkit.getServer());
    } catch (Throwable e) {
      return null;
    }
  }

  public Double averageTickTime() {
    if (averageTickTime == null) return null;
    try {
      return (double) averageTickTime.invoke(Bukkit.getServer());
    } catch (Throwable e) {
      return null;
    }
  }

  public double[] recentTps() {
    if (recentTps == null) return null;
    try {
      double[] raw = (double[]) recentTps.get(minecraftServer);
      return raw == null ? null : raw.clone();
    } catch (Throwable e) {
      return null;
    }
  }

  /** Folia: 5s/15s/1m/5m/15m of the region owning the chunk, null if no region owns it */
  public double[] regionTps(World world, int chunkX, int chunkZ) {
    if (regionTpsAt == null) return null;
    try {
      return (double[]) regionTpsAt.invoke(Bukkit.getServer(), world, chunkX, chunkZ);
    } catch (Throwable e) {
      return null;
    }
  }

  public double[] regionTps(Location location) {
    if (regionTpsLocation == null) return null;
    try {
      return (double[]) regionTpsLocation.invoke(Bukkit.getServer(), location);
    } catch (Throwable e) {
      return null;
    }
  }

  public Integer chunkCount(World world) {
    return intCall(worldChunkCount, world);
  }

  public Integer entityCount(World world) {
    return intCall(worldEntityCount, world);
  }

  public Integer tileCount(World world) {
    return intCall(worldTileCount, world);
  }

  public Integer tickingTileCount(World world) {
    return intCall(worldTickingTileCount, world);
  }

  /** Paper only, Spigot would snapshot every tile */
  public Object[] tilesNoSnapshot(Chunk chunk) {
    if (chunkTilesNoSnapshot == null) return null;
    try {
      return (Object[]) chunkTilesNoSnapshot.invoke(chunk, false);
    } catch (Throwable e) {
      return null;
    }
  }

  public Integer ping(Player player) {
    try {
      if (playerPing != null) return (int) playerPing.invoke(player);
      if (spigotPing != null) return (int) spigotPing.invoke(player.spigot());
    } catch (Throwable ignored) {
    }
    return null;
  }

  public Long lastLogin(Player player) {
    if (lastLogin == null) return null;
    try {
      long value = (long) lastLogin.invoke(player);
      return value > 0 ? value : null;
    } catch (Throwable e) {
      return null;
    }
  }

  @SuppressWarnings("deprecation")
  public void sendTitle(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
    if (sendTitleTimed != null) {
      try {
        sendTitleTimed.invoke(player, title, subtitle, fadeIn, stay, fadeOut);
        return;
      } catch (Throwable ignored) {
      }
    }
    player.sendTitle(title, subtitle);
  }

  public String quitReason(PlayerQuitEvent event) {
    return enumName(quitReason, event);
  }

  public String kickCause(PlayerKickEvent event) {
    return enumName(kickCause, event);
  }

  public String apiVersion(PluginDescriptionFile description) {
    if (pluginApiVersion == null) return null;
    try {
      return (String) pluginApiVersion.invoke(description);
    } catch (Throwable e) {
      return null;
    }
  }

  public static boolean classExists(String name) {
    return findClass(name) != null;
  }

  public static Class<?> findClass(String name) {
    try {
      return Class.forName(name, false, Compat.class.getClassLoader());
    } catch (Throwable e) {
      return null;
    }
  }

  public static MethodHandle virtual(Class<?> owner, String name, Class<?> ret, Class<?>... params) {
    if (owner == null) return null;
    try {
      return LOOKUP.findVirtual(owner, name, MethodType.methodType(ret, params)).asFixedArity();
    } catch (Throwable e) {
      return null;
    }
  }

  private static MethodHandle chunkTilesHandle() {
    Class<?> blockState = findClass("org.bukkit.block.BlockState");
    if (blockState == null) return null;
    Class<?> array = Array.newInstance(blockState, 0).getClass();
    return virtual(Chunk.class, "getTileEntities", array, boolean.class);
  }

  /** For signatures we can't spell against 1.8.8 (Paper-only return types etc), args and result erased to Object */
  public static MethodHandle method(Class<?> owner, String name, Class<?>... params) {
    if (owner == null) return null;
    try {
      MethodHandle handle = LOOKUP.unreflect(owner.getMethod(name, params)).asFixedArity();
      return handle.asType(MethodType.genericMethodType(handle.type().parameterCount()));
    } catch (Throwable e) {
      return null;
    }
  }

  private static String enumName(MethodHandle handle, Object target) {
    if (handle == null) return null;
    try {
      Object value = handle.invoke(target);
      return value instanceof Enum ? ((Enum<?>) value).name() : null;
    } catch (Throwable e) {
      return null;
    }
  }

  private static Integer intCall(MethodHandle handle, Object target) {
    if (handle == null) return null;
    try {
      return (int) handle.invoke(target);
    } catch (Throwable e) {
      return null;
    }
  }

  private static Field findField(Class<?> type, String name) {
    for (Class<?> c = type; c != null; c = c.getSuperclass()) {
      try {
        Field field = c.getDeclaredField(name);
        field.setAccessible(true);
        return field;
      } catch (NoSuchFieldException ignored) {
      } catch (Throwable e) {
        return null;
      }
    }
    return null;
  }
}
