package com.storytimeproductions.prophunt.game;

import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/** Handles clicks on the gamemode selection area to cycle between Hunt game modes. */
public class HuntGameModeHologramListener implements Listener {
  private final JavaPlugin plugin;
  private final HuntGameModeManager gameModeManager;

  /**
   * Creates a new HuntGameModeHologramListener instance.
   *
   * @param plugin The plugin instance
   * @param gameModeManager The game mode manager
   */
  public HuntGameModeHologramListener(JavaPlugin plugin, HuntGameModeManager gameModeManager) {
    this.plugin = plugin;
    this.gameModeManager = gameModeManager;
  }

  /**
   * Checks if a player is in the hunt world.
   *
   * @param player The player to check
   * @return true if the player is in the hunt world
   */
  private boolean isPlayerInHuntWorld(Player player) {
    String playerWorldName = player.getWorld().getName();
    return playerWorldName.toLowerCase().contains("hunt");
  }
}
