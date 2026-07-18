package com.storytimeproductions.prophunt.game;

import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Handles right-clicks on the gamemode-selection hologram, cycling through Prop Hunt, Imposter
 * Hunt, and NextBot Hunt via {@link HuntGameModeManager}. Only active while no prep phase is
 * currently running, since switching gamemode mid-round wouldn't be meaningful.
 */
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
