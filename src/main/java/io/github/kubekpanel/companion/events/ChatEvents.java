package io.github.kubekpanel.companion.events;

import io.github.kubekpanel.companion.json.Obj;
import io.github.kubekpanel.companion.platform.Compat;
import io.github.kubekpanel.companion.transport.Channel;
import io.github.kubekpanel.companion.util.Format;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.plugin.Plugin;

/**
 * Chat feed, off by default, registered only while the panel wants it
 * On Paper 1.16.5+ it's AsyncChatEvent, a legacy listener there would push chat through the old pipeline
 */
public final class ChatEvents implements Listener {

  private static final String PAPER_CHAT = "io.papermc.paper.event.player.AsyncChatEvent";
  private static final String COMPONENT = "net.kyori.adventure.text.Component";
  // plainText() is Adventure 4.8+, older Paper only has plain()
  private static final String[][] PLAIN_SERIALIZERS = {
      {"net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer", "plainText"},
      {"net.kyori.adventure.text.serializer.plain.PlainComponentSerializer", "plain"},
  };

  private final Plugin plugin;
  private final Channel channel;
  private boolean registered;

  public ChatEvents(Plugin plugin, Channel channel) {
    this.plugin = plugin;
    this.channel = channel;
  }

  public synchronized void setEnabled(boolean enabled) {
    if (enabled == registered) return;
    if (enabled) {
      if (!registerPaper()) registerLegacy();
      registered = true;
    } else {
      HandlerList.unregisterAll(this);
      registered = false;
    }
  }

  private boolean registerPaper() {
    Class<?> raw = Compat.findClass(PAPER_CHAT);
    if (raw == null || !PlayerEvent.class.isAssignableFrom(raw)) return false;
    MethodHandle message = Compat.method(raw, "message");
    MethodHandle toPlain = plainSerializer();
    if (message == null || toPlain == null) return false;
    @SuppressWarnings("unchecked")
    Class<? extends Event> type = (Class<? extends Event>) raw;
    Bukkit.getPluginManager().registerEvent(type, this, EventPriority.MONITOR, (listener, event) -> {
      if (!raw.isInstance(event)) return;
      try {
        String text = (String) toPlain.invoke(message.invoke(event));
        emit(((PlayerEvent) event).getPlayer(), text);
      } catch (Throwable ignored) {
        // lose one line, never break chat
      }
    }, plugin, true);
    return true;
  }

  private void registerLegacy() {
    Bukkit.getPluginManager().registerEvent(AsyncPlayerChatEvent.class, this, EventPriority.MONITOR,
        (listener, event) -> {
          if (!(event instanceof AsyncPlayerChatEvent)) return;
          AsyncPlayerChatEvent chat = (AsyncPlayerChatEvent) event;
          emit(chat.getPlayer(), Format.strip(chat.getMessage()));
        }, plugin, true);
  }

  private void emit(Player player, String text) {
    Map<String, Object> e = Obj.map();
    e.put("type", "chat");
    e.put("ts", System.currentTimeMillis());
    e.put("uuid", player.getUniqueId().toString());
    e.put("name", player.getName());
    e.put("message", text);
    channel.event(e);
  }

  /** Component -> plain String, bound to the serializer instance */
  private static MethodHandle plainSerializer() {
    Class<?> component = Compat.findClass(COMPONENT);
    if (component == null) return null;
    for (String[] spec : PLAIN_SERIALIZERS) {
      Class<?> type = Compat.findClass(spec[0]);
      MethodHandle factory = Compat.method(type, spec[1]);
      MethodHandle serialize = Compat.method(type, "serialize", component);
      if (factory == null || serialize == null) continue;
      try {
        return serialize.bindTo(factory.invoke());
      } catch (Throwable ignored) {
      }
    }
    return null;
  }
}
