package io.github.kubekpanel.companion.util;

import org.bukkit.ChatColor;

public final class Format {

  private Format() {
  }

  public static String color(String text) {
    return ChatColor.translateAlternateColorCodes('&', text);
  }

  public static String strip(String text) {
    return text == null ? null : ChatColor.stripColor(text);
  }

  public static double round(double value, int decimals) {
    double scale = Math.pow(10, decimals);
    return Math.round(value * scale) / scale;
  }
}
