package com.storytimeproductions.prophunt.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

/** Sends chat messages with a shared {@code [Hunt] } prefix. */
public final class HuntMessages {

  private static final Component PREFIX = Component.text("[Hunt] ", NamedTextColor.GOLD);

  private HuntMessages() {}

  /** Sends the given message to the recipient, prefixed with {@code [Hunt] }. */
  public static void send(CommandSender recipient, Component message) {
    recipient.sendMessage(PREFIX.append(message));
  }
}
