package com.storytimeproductions.prophunt.game;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Manages the current Hunt game mode and handles mode switching. */
public class HuntGameModeManager {
  private final JavaPlugin plugin;
  private HuntGameMode currentGameMode;
  private HuntLobbyManager lobbyManager;
  private HuntKitManager kitManager;
  private HuntDisguiseManager disguiseManager;
  private HuntHologramManager hologramManager;

  /**
   * Creates a new HuntGameModeManager instance.
   *
   * @param plugin The plugin instance
   */
  public HuntGameModeManager(JavaPlugin plugin) {
    this.plugin = plugin;
    this.currentGameMode = HuntGameMode.PROP_HUNT; // Default to Prop Hunt
  }

  /**
   * Sets dependencies for cleanup operations when game mode changes.
   *
   * @param lobbyManager The lobby manager instance
   * @param kitManager The kit manager instance
   * @param disguiseManager The disguise manager instance
   * @param hologramManager The hologram manager instance
   */
  public void setDependencies(
      HuntLobbyManager lobbyManager,
      HuntKitManager kitManager,
      HuntDisguiseManager disguiseManager,
      HuntHologramManager hologramManager) {
    this.lobbyManager = lobbyManager;
    this.kitManager = kitManager;
    this.disguiseManager = disguiseManager;
    this.hologramManager = hologramManager;
  }

  /**
   * Gets the current game mode.
   *
   * @return The current HuntGameMode
   */
  public HuntGameMode getCurrentGameMode() {
    return currentGameMode;
  }

  /**
   * Sets the current game mode and updates the hologram. Clears player data when switching away
   * from Prop Hunt.
   *
   * @param gameMode The new game mode to set
   */
  public void setGameMode(HuntGameMode gameMode) {
    HuntGameMode previousMode = this.currentGameMode;
    this.currentGameMode = gameMode;

    plugin
        .getLogger()
        .info(
            "Hunt game mode changed from "
                + previousMode.getDisplayName()
                + " to "
                + gameMode.getDisplayName());

    // Clear player data when switching away from Prop Hunt
    if (previousMode == HuntGameMode.PROP_HUNT && gameMode != HuntGameMode.PROP_HUNT) {
      clearAllPlayerHuntData();
    }

    // Update the gamemode hologram
    updateGameModeHologram();
  }

  /**
   * Cycles to the next game mode in the enum order.
   *
   * @return The new current game mode
   */
  public HuntGameMode cycleGameMode() {
    HuntGameMode[] modes = HuntGameMode.values();
    int currentIndex = currentGameMode.ordinal();
    int nextIndex = (currentIndex + 1) % modes.length;

    setGameMode(modes[nextIndex]);
    return currentGameMode;
  }

  /** Updates the gamemode display to show the current mode. */
  private void updateGameModeHologram() {
    if (hologramManager != null) {
      hologramManager.updateGameModeDisplay(currentGameMode);
    }
    plugin.getLogger().info("Updated gamemode display to: " + currentGameMode.getDisplayName());
  }

  /**
   * Initializes the gamemode display with the current mode. Should be called after
   * HuntHologramManager.initialize() has run.
   */
  public void initializeGameModeHologram() {
    updateGameModeHologram();
    plugin
        .getLogger()
        .info("Initialized gamemode display with: " + currentGameMode.getDisplayName());
  }

  /**
   * Clears all player Hunt-related data when switching away from Prop Hunt mode. This includes
   * removing kits, disguises, class assignments, and holograms.
   */
  private void clearAllPlayerHuntData() {
    if (lobbyManager == null
        || kitManager == null
        || disguiseManager == null
        || hologramManager == null) {
      plugin.getLogger().warning("Cannot clear player Hunt data - dependencies not set");
      return;
    }

    plugin
        .getLogger()
        .info("Clearing all player Hunt data due to game mode change away from Prop Hunt");

    // Remove all disguises from all players
    disguiseManager.removeAllDisguises();

    // Remove all players from holograms
    hologramManager.removeAllPlayersFromHolograms();

    // Remove kits and clear class assignments for all online players
    for (Player player : Bukkit.getOnlinePlayers()) {
      // Remove kit items
      kitManager.removePlayerKit(player);

      // Clear class assignments in player data
      java.util.UUID playerId = player.getUniqueId();
      com.storytimeproductions.prophunt.game.HuntPlayerData playerData =
          lobbyManager.getPlayerData(playerId);
      if (playerData != null) {
        playerData.setSelectedTeam(null);
        playerData.setSelectedHunterClass(null);
        playerData.setSelectedHiderClass(null);
        playerData.setSelectedDisguise(null);
        playerData.setSelectedDisguiseSkin(null);
        playerData.setReady(false);

        player.sendMessage(
            net.kyori.adventure.text.Component.text(
                "Game mode changed! Your class selections and items have been cleared.",
                net.kyori.adventure.text.format.NamedTextColor.YELLOW));
      }
    }

    plugin
        .getLogger()
        .info(
            "Finished clearing player Hunt data for "
                + Bukkit.getOnlinePlayers().size()
                + " players");
  }
}
