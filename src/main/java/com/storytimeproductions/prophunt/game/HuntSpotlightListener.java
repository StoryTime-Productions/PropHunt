package com.storytimeproductions.prophunt.game;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Idle-triggered spotlight reveal (see specs/idle-spotlight.md): each disguised hider has their own
 * idle clock. Once it crosses the threshold, that specific hider is briefly forced undisguised and
 * glowing, so a hider can't camp one perfect spot indefinitely without ever being at risk. A hider
 * who's actually moving around stays effectively safe from it. The remaining countdown is exposed
 * to the unified hider action bar so a camping hider can see exactly how long they have left.
 */
public class HuntSpotlightListener {

  private final JavaPlugin plugin;
  private final FileConfiguration config;
  private final HuntPrepPhaseManager prepPhaseManager;
  private final HiderUtilityListener hiderUtilityListener;

  private final Map<UUID, Location> lastLocations = new HashMap<>();
  private final Map<UUID, Long> stillSinceTimestamps = new HashMap<>();
  private final Map<UUID, Long> lastSpotlightedTimestamps = new HashMap<>();
  private final Set<UUID> telegraphedPlayers = new HashSet<>();

  /**
   * Constructs a new HuntSpotlightListener and starts its per-second idle-tracking task.
   *
   * @param plugin The plugin instance
   * @param config The hunt configuration
   * @param prepPhaseManager The prep phase manager, for game-active state and participants
   * @param hiderUtilityListener The hider utility listener, for block-disguise state
   */
  public HuntSpotlightListener(
      JavaPlugin plugin,
      FileConfiguration config,
      HuntPrepPhaseManager prepPhaseManager,
      HiderUtilityListener hiderUtilityListener) {
    this.plugin = plugin;
    this.config = config;
    this.prepPhaseManager = prepPhaseManager;
    this.hiderUtilityListener = hiderUtilityListener;

    Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
  }

  private void tick() {
    if (!prepPhaseManager.isGameActive()) {
      return;
    }

    long minIdleMillis = config.getInt("hunt.spotlight.min-idle-seconds", 12) * 1000L;
    long telegraphLeadMillis = config.getInt("hunt.spotlight.telegraph-lead-seconds", 2) * 1000L;
    long reselectCooldownMillis =
        config.getInt("hunt.spotlight.reselect-cooldown-seconds", 25) * 1000L;
    long now = System.currentTimeMillis();

    for (Map.Entry<UUID, HuntTeam> entry : prepPhaseManager.getGameParticipants().entrySet()) {
      if (entry.getValue() != HuntTeam.HIDERS) {
        continue;
      }
      UUID playerId = entry.getKey();
      Player hider = Bukkit.getPlayer(playerId);
      if (hider == null || !hider.isOnline() || hider.getGameMode() == GameMode.SPECTATOR) {
        continue;
      }

      updateMovement(hider, reselectCooldownMillis);

      if (hiderUtilityListener.getPlayerBlockData(playerId) == null) {
        continue; // Nothing to reveal - not currently block-disguised.
      }

      Long lastSpotlighted = lastSpotlightedTimestamps.get(playerId);
      if (lastSpotlighted != null && now - lastSpotlighted < reselectCooldownMillis) {
        continue;
      }

      Long stillSince = stillSinceTimestamps.get(playerId);
      if (stillSince == null) {
        continue;
      }
      long idleMillis = now - stillSince;
      long remainingMillis = minIdleMillis - idleMillis;

      if (remainingMillis <= telegraphLeadMillis
          && remainingMillis > 0
          && telegraphedPlayers.add(playerId)) {
        hider.playSound(hider.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.0f, 1.0f);
      }

      if (idleMillis >= minIdleMillis) {
        lastSpotlightedTimestamps.put(playerId, now);
        stillSinceTimestamps.remove(playerId);
        telegraphedPlayers.remove(playerId);
        revealHider(playerId);
      }
    }
  }

  private void updateMovement(Player hider, long reselectCooldownMillis) {
    UUID playerId = hider.getUniqueId();
    Location current = hider.getLocation();
    Location last = lastLocations.get(playerId);

    boolean movedSignificantly =
        last == null
            || !last.getWorld().equals(current.getWorld())
            || last.distanceSquared(current) > 0.01;

    if (movedSignificantly) {
      // Moved - reset. This clears the still-timer rather than setting it to "now", so the
      // idle countdown only actually starts once we've confirmed they've stopped (next tick),
      // matching the same pattern already used for hunter idle-charge tracking in
      // HuntDisguisePassiveListener.
      lastLocations.put(playerId, current.clone());
      stillSinceTimestamps.remove(playerId);
      telegraphedPlayers.remove(playerId);
      return;
    }

    // Don't (re)start the idle clock while still within the post-reveal reselect cooldown -
    // otherwise it silently finishes counting down during that grace window (since a hider who
    // stays put has nothing to reset it), and the moment the cooldown clears, the very next
    // tick already sees them past the threshold - an instant reveal with no countdown or
    // telegraph ever shown. Keeping the two windows serial (wait out the cooldown, then start
    // the real countdown) is what actually makes the countdown visible every time.
    Long lastSpotlighted = lastSpotlightedTimestamps.get(playerId);
    if (lastSpotlighted != null
        && System.currentTimeMillis() - lastSpotlighted < reselectCooldownMillis) {
      stillSinceTimestamps.remove(playerId);
      return;
    }

    if (!stillSinceTimestamps.containsKey(playerId)) {
      // Not moved, no still-timer running yet, and not on cooldown - start it now. Without
      // this branch, a hider whose still-timer was just cleared (e.g. right after a reveal)
      // but who doesn't move again afterward would never get a still-timer restarted at all,
      // since nothing would ever detect a "movement" to trigger it - matching the earlier
      // reported bug where the countdown only ever came back after a hunter's knockback
      // forced real movement.
      stillSinceTimestamps.put(playerId, System.currentTimeMillis());
    }
  }

  /**
   * Returns how many seconds remain until this hider gets spotlight-revealed, or {@code null} if
   * they're not currently idling toward one (moving, not disguised, or on their post-reveal
   * cooldown). Used by the unified hider action bar so a camping hider can see their countdown.
   *
   * @param playerId The hider to check
   * @return Remaining seconds until reveal, or null if not applicable
   */
  public Integer getIdleCountdownSeconds(UUID playerId) {
    // Without this, the countdown could stick at its last value (usually 0s) forever once a
    // round ends and everyone's back in the lobby - tick() stops updating anything once the
    // game isn't active, but this method is called independently from the action bar refresh
    // and was reading that frozen, stale state regardless (see
    // specs/spotlight-countdown-persists-after-round-end.md).
    if (!prepPhaseManager.isGameActive()) {
      return null;
    }

    Long stillSince = stillSinceTimestamps.get(playerId);
    if (stillSince == null) {
      return null;
    }

    long reselectCooldownMillis =
        config.getInt("hunt.spotlight.reselect-cooldown-seconds", 25) * 1000L;
    Long lastSpotlighted = lastSpotlightedTimestamps.get(playerId);
    long now = System.currentTimeMillis();
    if (lastSpotlighted != null && now - lastSpotlighted < reselectCooldownMillis) {
      return null;
    }

    long minIdleMillis = config.getInt("hunt.spotlight.min-idle-seconds", 12) * 1000L;
    long remainingMillis = minIdleMillis - (now - stillSince);
    if (remainingMillis <= 0) {
      return 0;
    }
    return (int) Math.ceil(remainingMillis / 1000.0);
  }

  private void revealHider(UUID playerId) {
    Player player = Bukkit.getPlayer(playerId);
    if (player == null || !player.isOnline()) {
      return;
    }

    hiderUtilityListener.temporarilyUndisguise(player);

    int glowDurationSeconds = config.getInt("hunt.spotlight.glow-duration-seconds", 5);
    player.addPotionEffect(
        new PotionEffect(PotionEffectType.GLOWING, glowDurationSeconds * 20, 0, false, false));

    Bukkit.getScheduler()
        .runTaskLater(
            plugin,
            () -> {
              Player current = Bukkit.getPlayer(playerId);
              if (current != null && current.isOnline()) {
                hiderUtilityListener.reapplyStoredBlockDisguise(current);
              }
            },
            (long) glowDurationSeconds * 20L);
  }

  /** Clears all tracked state for a single player - used when they leave the hunt experience. */
  public void clearPlayerState(UUID playerId) {
    lastLocations.remove(playerId);
    stillSinceTimestamps.remove(playerId);
    lastSpotlightedTimestamps.remove(playerId);
    telegraphedPlayers.remove(playerId);
  }

  /**
   * Clears all tracked state for every player - used on round/prep-phase end so nothing carries
   * over into the lobby or leaks stale timing into the next round.
   */
  public void clearAllState() {
    lastLocations.clear();
    stillSinceTimestamps.clear();
    lastSpotlightedTimestamps.clear();
    telegraphedPlayers.clear();
  }
}
