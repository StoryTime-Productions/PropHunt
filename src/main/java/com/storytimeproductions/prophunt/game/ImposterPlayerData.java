package com.storytimeproductions.prophunt.game;

import java.util.UUID;

/**
 * Per-player state for Imposter Hunt: their assigned {@link ImposterRole}, coin balance, zapper
 * charges, alive/dead status, and cooldown timestamps for role-specific actions (investigation,
 * throwables). Separate from {@link HuntPlayerData} since this mode's roles/economy don't overlap
 * with Prop Hunt's team/class model.
 */
public class ImposterPlayerData {
  private final UUID playerId;
  private ImposterRole role;
  private int coins;
  private int zappers;
  private boolean isDead;
  private long lastInvestigationTime;
  private long lastThrowableUse;

  /**
   * Creates a new ImposterPlayerData instance.
   *
   * @param playerId The UUID of the player
   */
  public ImposterPlayerData(UUID playerId) {
    this.playerId = playerId;
    this.role = null;
    this.coins = 0;
    this.zappers = 0;
    this.isDead = false;
    this.lastInvestigationTime = 0;
    this.lastThrowableUse = 0;
  }

  /**
   * Gets the player's UUID.
   *
   * @return The player's UUID
   */
  public UUID getPlayerId() {
    return playerId;
  }

  /**
   * Gets the player's role.
   *
   * @return The player's role
   */
  public ImposterRole getRole() {
    return role;
  }

  /**
   * Sets the player's role.
   *
   * @param role The role to set
   */
  public void setRole(ImposterRole role) {
    this.role = role;
  }

  /**
   * Gets the player's coin count.
   *
   * @return The number of coins
   */
  public int getCoins() {
    return coins;
  }

  /**
   * Sets the player's coin count.
   *
   * @param coins The number of coins to set
   */
  public void setCoins(int coins) {
    this.coins = Math.max(0, coins);
  }

  /**
   * Adds coins to the player's total.
   *
   * @param amount The amount of coins to add
   */
  public void addCoins(int amount) {
    this.coins += amount;
  }

  /**
   * Removes coins from the player's total.
   *
   * @param amount The amount of coins to remove
   * @return true if the player had enough coins, false otherwise
   */
  public boolean removeCoins(int amount) {
    if (this.coins >= amount) {
      this.coins -= amount;
      return true;
    }
    return false;
  }

  /**
   * Gets the player's zapper count.
   *
   * @return The number of zappers
   */
  public int getZappers() {
    return zappers;
  }

  /**
   * Sets the player's zapper count.
   *
   * @param zappers The number of zappers to set
   */
  public void setZappers(int zappers) {
    this.zappers = Math.max(0, zappers);
  }

  /**
   * Adds zappers to the player's total.
   *
   * @param amount The amount of zappers to add
   */
  public void addZappers(int amount) {
    this.zappers += amount;
  }

  /**
   * Uses a zapper if the player has any.
   *
   * @return true if a zapper was used, false if no zappers available
   */
  public boolean useZapper() {
    if (this.zappers > 0) {
      this.zappers--;
      return true;
    }
    return false;
  }

  /**
   * Checks if the player is dead.
   *
   * @return true if the player is dead
   */
  public boolean isDead() {
    return isDead;
  }

  /**
   * Sets the player's death status.
   *
   * @param isDead true if the player should be marked as dead
   */
  public void setDead(boolean isDead) {
    this.isDead = isDead;
  }

  /**
   * Gets the last investigation time for the Sheriff role.
   *
   * @return The timestamp of the last investigation
   */
  public long getLastInvestigationTime() {
    return lastInvestigationTime;
  }

  /**
   * Sets the last investigation time for the Sheriff role.
   *
   * @param time The timestamp to set
   */
  public void setLastInvestigationTime(long time) {
    this.lastInvestigationTime = time;
  }

  /**
   * Gets the last throwable use time for the Murderer role.
   *
   * @return The timestamp of the last throwable use
   */
  public long getLastThrowableUse() {
    return lastThrowableUse;
  }

  /**
   * Sets the last throwable use time for the Murderer role.
   *
   * @param time The timestamp to set
   */
  public void setLastThrowableUse(long time) {
    this.lastThrowableUse = time;
  }

  /**
   * Checks if the Sheriff can investigate (not on cooldown).
   *
   * @param cooldownSeconds The investigation cooldown in seconds
   * @return true if investigation is available
   */
  public boolean canInvestigate(int cooldownSeconds) {
    long currentTime = System.currentTimeMillis();
    long cooldownMillis = cooldownSeconds * 1000L;
    return (currentTime - lastInvestigationTime) >= cooldownMillis;
  }

  /**
   * Checks if the Murderer can use throwable (not on cooldown).
   *
   * @param cooldownSeconds The throwable cooldown in seconds
   * @return true if throwable is available
   */
  public boolean canUseThrowable(int cooldownSeconds) {
    long currentTime = System.currentTimeMillis();
    long cooldownMillis = cooldownSeconds * 1000L;
    return (currentTime - lastThrowableUse) >= cooldownMillis;
  }

  /** Resets the player data for a new game. */
  public void reset() {
    this.role = null;
    this.coins = 0;
    this.zappers = 0;
    this.isDead = false;
    this.lastInvestigationTime = 0;
    this.lastThrowableUse = 0;
  }
}
