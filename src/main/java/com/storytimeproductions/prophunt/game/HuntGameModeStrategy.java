package com.storytimeproductions.prophunt.game;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Strategy interface for different Hunt game modes. Each gamemode implements its own specific ready
 * and start requirements.
 */
public interface HuntGameModeStrategy {

  /**
   * Checks if a player can mark themselves as ready for this gamemode.
   *
   * @param player The player attempting to ready up
   * @param playerData The player's lobby data (may be null for some gamemodes)
   * @param playerMapVotes Map of player UUIDs to their map votes
   * @param hologramManager The hologram manager for checking selections
   * @return A result indicating if the player can ready up and any error message
   */
  ReadyResult canPlayerReady(
      Player player,
      HuntPlayerData playerData,
      Map<UUID, HuntMap> playerMapVotes,
      HuntHologramManager hologramManager);

  /**
   * Gets the minimum number of players required to start this gamemode.
   *
   * @return The minimum player count
   */
  int getMinimumPlayers();

  /**
   * Checks if the game can start with the current ready players.
   *
   * @param readyPlayers List of ready player UUIDs
   * @param playerMapVotes Map of player UUIDs to their map votes
   * @param hologramManager The hologram manager for checking selections
   * @return true if the game can start
   */
  boolean canStartGame(
      List<UUID> readyPlayers,
      Map<UUID, HuntMap> playerMapVotes,
      HuntHologramManager hologramManager);

  /**
   * Gets the display name for this gamemode strategy.
   *
   * @return The display name
   */
  String getDisplayName();

  /** Result class for ready checks. */
  class ReadyResult {
    private final boolean canReady;
    private final String errorMessage;

    /**
     * Creates a new ReadyResult.
     *
     * @param canReady Whether the player can ready up
     * @param errorMessage Error message if canReady is false (null if canReady is true)
     */
    public ReadyResult(boolean canReady, String errorMessage) {
      this.canReady = canReady;
      this.errorMessage = errorMessage;
    }

    /**
     * Creates a successful ready result.
     *
     * @return A successful ReadyResult
     */
    public static ReadyResult success() {
      return new ReadyResult(true, null);
    }

    /**
     * Creates a failed ready result with an error message.
     *
     * @param errorMessage The error message
     * @return A failed ReadyResult
     */
    public static ReadyResult failure(String errorMessage) {
      return new ReadyResult(false, errorMessage);
    }

    /**
     * Gets whether the player can ready up.
     *
     * @return true if the player can ready up
     */
    public boolean canReady() {
      return canReady;
    }

    /**
     * Gets the error message if canReady is false.
     *
     * @return The error message, or null if canReady is true
     */
    public String getErrorMessage() {
      return errorMessage;
    }
  }
}
