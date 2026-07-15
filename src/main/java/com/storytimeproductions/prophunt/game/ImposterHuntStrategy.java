package com.storytimeproductions.prophunt.game;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Strategy for Imposter Hunt gamemode. Only requires players to vote for a map before readying up.
 * No team or class selection required.
 */
public class ImposterHuntStrategy implements HuntGameModeStrategy {

  @Override
  public ReadyResult canPlayerReady(
      Player player,
      HuntPlayerData playerData,
      Map<UUID, HuntMap> playerMapVotes,
      HuntHologramManager hologramManager) {

    UUID playerId = player.getUniqueId();

    // For Imposter Hunt, only require map vote
    if (!playerMapVotes.containsKey(playerId)) {
      return ReadyResult.failure("Please vote for a map before readying up!");
    }

    return ReadyResult.success();
  }

  @Override
  public int getMinimumPlayers() {
    return 3; // Minimum for Imposter Hunt (need at least 1 imposter + 2 survivors)
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

    // Verify all ready players have voted for a map
    for (UUID playerId : readyPlayers) {
      if (!playerMapVotes.containsKey(playerId)) {
        return false;
      }
    }

    return true;
  }

  @Override
  public String getDisplayName() {
    return "Imposter Hunt";
  }
}
