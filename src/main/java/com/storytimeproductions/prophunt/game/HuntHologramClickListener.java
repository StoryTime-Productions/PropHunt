package com.storytimeproductions.prophunt.game;

import com.storytimeproductions.prophunt.commands.HuntCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;

/** Routes right-click interactions on hologram Interaction entities to the appropriate handler. */
public class HuntHologramClickListener implements Listener {

  private final HuntHologramManager hologramManager;
  private final HuntCommand huntCommand;
  private final HuntPrepPhaseManager prepPhaseManager;
  private final HuntGameModeManager gameModeManager;

  /**
   * Constructs a new HuntHologramClickListener.
   *
   * @param hologramManager The hologram manager
   * @param huntCommand The hunt command handler (class/map routing)
   * @param prepPhaseManager The prep phase manager (ready/start)
   * @param gameModeManager The game mode manager (mode cycling)
   */
  public HuntHologramClickListener(
      HuntHologramManager hologramManager,
      HuntCommand huntCommand,
      HuntPrepPhaseManager prepPhaseManager,
      HuntGameModeManager gameModeManager) {
    this.hologramManager = hologramManager;
    this.huntCommand = huntCommand;
    this.prepPhaseManager = prepPhaseManager;
    this.gameModeManager = gameModeManager;
  }

  /** Handles right-click on an Interaction entity and routes to the correct hologram action. */
  @EventHandler
  public void onInteract(PlayerInteractAtEntityEvent event) {
    String hologramId = hologramManager.getHologramIdForInteraction(event.getRightClicked());
    if (hologramId == null) {
      return;
    }

    event.setCancelled(true);
    Player player = event.getPlayer();

    if (hologramId.startsWith("hunter_")) {
      String className = hologramId.substring("hunter_".length());
      huntCommand.handleClassJoin(player, className);

    } else if (hologramId.startsWith("hider_")) {
      String className = hologramId.substring("hider_".length());
      huntCommand.handleClassJoin(player, className);

    } else if (hologramId.startsWith("map_")) {
      String mapName = hologramId.substring("map_".length());
      huntCommand.handleMapVote(player, mapName);

    } else if (hologramId.equals("start_game")) {
      prepPhaseManager.attemptGameStart();

    } else if (hologramId.equals("ready_status")) {
      boolean currentlyReady =
          Boolean.TRUE.equals(prepPhaseManager.getPlayerReadyStatus(player.getUniqueId()));
      prepPhaseManager.setPlayerReady(player, !currentlyReady);

    } else if (hologramId.equals("gamemode_selection")) {
      gameModeManager.cycleGameMode();
      hologramManager.updateGameModeDisplay(gameModeManager.getCurrentGameMode());
    }
  }
}
