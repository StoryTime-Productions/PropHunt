package com.storytimeproductions.prophunt.game;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Listener for disguise-related events in the Hunt game. */
public class HuntDisguiseListener implements Listener {

  private final HuntDisguiseManager disguiseManager;

  /**
   * Constructs a new HuntDisguiseListener.
   *
   * @param disguiseManager The disguise manager instance
   * @param hologramManager Unused — kept for API compatibility with PropHunt.java
   */
  public HuntDisguiseListener(
      HuntDisguiseManager disguiseManager, HuntHologramManager hologramManager) {
    this.disguiseManager = disguiseManager;
  }

  /**
   * Handles shift-right-click to play a character screech while disguised.
   *
   * @param event The interaction event
   */
  @EventHandler
  public void onPlayerInteract(PlayerInteractEvent event) {
    Player player = event.getPlayer();

    if (!player.isSneaking()
        || event.getAction() != Action.RIGHT_CLICK_AIR
            && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
      return;
    }

    if (!disguiseManager.isPlayerDisguised(player.getUniqueId())) {
      return;
    }

    disguiseManager.playCharacterScreech(player);
  }

  /**
   * Removes disguises when a player quits the server.
   *
   * @param event The player quit event
   */
  @EventHandler
  public void onPlayerQuit(PlayerQuitEvent event) {
    disguiseManager.removeDisguise(event.getPlayer());
  }
}
