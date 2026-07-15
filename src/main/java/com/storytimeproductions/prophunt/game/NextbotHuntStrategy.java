package com.storytimeproductions.prophunt.game;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Strategy for Nextbot Hunt gamemode. Uses similar requirements to Prop Hunt (team, class, and map
 * selection).
 */
public class NextbotHuntStrategy implements HuntGameModeStrategy {

  @Override
  public ReadyResult canPlayerReady(
      Player player,
      HuntPlayerData playerData,
      Map<UUID, HuntMap> playerMapVotes,
      HuntHologramManager hologramManager) {

    // Check if player has selected team and class (similar to Prop Hunt)
    if (playerData == null || playerData.getSelectedTeam() == null) {
      return ReadyResult.failure("Please select a team and class before readying up!");
    }

    // Check if player has selected appropriate class for their team
    if (playerData.getSelectedTeam() == HuntTeam.HUNTERS
        && playerData.getSelectedHunterClass() == null) {
      return ReadyResult.failure("Please select a hunter class before readying up!");
    }

    if (playerData.getSelectedTeam() == HuntTeam.HIDERS
        && playerData.getSelectedHiderClass() == null) {
      return ReadyResult.failure("Please select a hider class before readying up!");
    }

    // Check if player has voted for a map
    final UUID playerId = player.getUniqueId();
    if (!playerMapVotes.containsKey(playerId)) {
      return ReadyResult.failure("Please vote for a map before readying up!");
    }

    return ReadyResult.success();
  }

  @Override
  public int getMinimumPlayers() {
    return 2; // Minimum for Nextbot Hunt
  }

  @Override
  public boolean canStartGame(
      List<UUID> readyPlayers,
      Map<UUID, HuntMap> playerMapVotes,
      HuntHologramManager hologramManager) {

    // Check minimum player count
    if (readyPlayers.size() < getMinimumPlayers()) {
      return false;
    }

    // Verify all ready players have the required selections
    for (UUID playerId : readyPlayers) {
      // Check map vote
      if (!playerMapVotes.containsKey(playerId)) {
        return false;
      }

      // Check class selection through hologram manager
      String classSelection = hologramManager.getPlayerClassSelection(playerId);
      if (classSelection == null) {
        return false;
      }
    }

    return true;
  }

  @Override
  public String getDisplayName() {
    return "Nextbot Hunt";
  }
}
