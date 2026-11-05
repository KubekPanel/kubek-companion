package io.github.kubekpanel.companion.collect;

import io.github.kubekpanel.companion.json.Obj;
import io.github.kubekpanel.companion.platform.Compat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;

/** Every loaded plugin, incl. ones that failed to enable */
public final class PluginList {

  private final Compat compat;

  public PluginList(Compat compat) {
    this.compat = compat;
  }

  @SuppressWarnings("deprecation")
  public List<Map<String, Object>> build() {
    Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
    List<Map<String, Object>> out = new ArrayList<>(plugins.length);
    for (Plugin plugin : plugins) {
      // deprecated on Paper, but the only option on 1.8
      PluginDescriptionFile description = plugin.getDescription();
      Map<String, Object> p = Obj.map();
      p.put("name", plugin.getName());
      p.put("version", description.getVersion());
      p.put("enabled", plugin.isEnabled());
      p.put("authors", description.getAuthors());
      p.put("apiVersion", compat.apiVersion(description));
      p.put("depend", description.getDepend());
      p.put("softDepend", description.getSoftDepend());
      out.add(p);
    }
    return out;
  }
}
