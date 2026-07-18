package com.storytimeproductions.prophunt.game;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Listener that handles player join events specifically for Hunt game functionality. Ensures that
 * players have their entity size reset when they join the server, and that a returning player
 * without a currently-valid class selection can't keep a leftover kit item from before a server
 * restart.
 */
public class HuntPlayerJoinListener implements Listener {

  private final HuntDisguiseManager disguiseManager;
  private final HuntKitManager kitManager;
  private final HuntLobbyManager lobbyManager;
  private final JavaPlugin plugin;

  /**
   * Constructs a new HuntPlayerJoinListener.
   *
   * @param disguiseManager The disguise manager to reset player sizes
   * @param kitManager The kit manager, used to clear a stale kit on join
   * @param lobbyManager The lobby manager, used to check the player's current class selection
   * @param plugin The JavaPlugin instance
   */
  public HuntPlayerJoinListener(
      HuntDisguiseManager disguiseManager,
      HuntKitManager kitManager,
      HuntLobbyManager lobbyManager,
      JavaPlugin plugin) {
    this.disguiseManager = disguiseManager;
    this.kitManager = kitManager;
    this.lobbyManager = lobbyManager;
    this.plugin = plugin;
  }

  /**
   * Handles player join events to reset entity size and clear a stale kit.
   *
   * @param event The PlayerJoinEvent
   */
  @EventHandler
  public void onPlayerJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();

    // Team/class selections live only in memory and don't survive a server restart, but a
    // player's actual inventory does (it's saved by vanilla Minecraft, not by this plugin). A
    // player joining with no currently-valid class selection has no legitimate reason to be
    // holding a class kit item (e.g. an Ender Pearl usable as a vanilla item), so clear it.
    HuntPlayerData data = lobbyManager.getPlayerData(player.getUniqueId());
    boolean hasValidClassSelection =
        data != null
            && data.getSelectedTeam() != null
            && (data.getSelectedHunterClass() != null || data.getSelectedHiderClass() != null);
    if (!hasValidClassSelection) {
      kitManager.removePlayerKit(player);
    }

    // Schedule the size reset with a small delay to ensure the player is fully
    // loaded
    plugin
        .getServer()
        .getScheduler()
        .runTaskLater(
            plugin,
            () -> {
              disguiseManager.resetPlayerSize(player);
              plugin.getLogger().info("Reset entity size for player: " + player.getName());
            },
            20L); // 1 second delay
  }
}
