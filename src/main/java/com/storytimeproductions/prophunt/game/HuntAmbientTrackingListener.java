package com.storytimeproductions.prophunt.game;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/**
 * Passive, always-on information channel for hunters and hiders, inspired by Dead by Daylight's
 * scratch marks and red stain (see specs/ambient-tracking-layer.md): a faint particle trail behind
 * sprinting hiders, visible only to hunters, and a directional particle cone in front of hunters,
 * visible only to hiders. Neither is an activatable ability - both run continuously during a round,
 * giving each side a low-grade information stream instead of everything being gated behind timed
 * abilities.
 */
public class HuntAmbientTrackingListener {

  private static final Particle.DustOptions FOOTPRINT_DUST =
      new Particle.DustOptions(Color.fromRGB(90, 60, 40), 0.8f);
  private static final Particle.DustOptions FACING_CONE_DUST =
      new Particle.DustOptions(Color.fromRGB(200, 20, 20), 0.9f);

  private final FileConfiguration config;
  private final HuntLobbyManager lobbyManager;

  private final Map<UUID, Deque<TrailPoint>> hiderTrails = new HashMap<>();

  private record TrailPoint(Location location, long timestampMillis) {}

  /**
   * Constructs a new HuntAmbientTrackingListener and starts its periodic tracking task.
   *
   * @param plugin The plugin instance
   * @param config The hunt configuration
   * @param lobbyManager The lobby manager for checking player team/class state
   */
  public HuntAmbientTrackingListener(
      JavaPlugin plugin, FileConfiguration config, HuntLobbyManager lobbyManager) {
    this.config = config;
    this.lobbyManager = lobbyManager;

    long intervalTicks = config.getInt("hunt.tracking.interval-ticks", 4);
    Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, intervalTicks);
  }

  private void tick() {
    recordHiderTrails();
    pruneExpiredTrailPoints(
        config.getDouble("hunt.tracking.footprint-lifetime-seconds", 4.0) * 1000);
    renderFootprintsForHunters(
        config.getDouble("hunt.tracking.footprint-max-visible-distance", 20.0));
    renderFacingConeForHiders(
        config.getDouble("hunt.tracking.facing-cone-range-blocks", 6.0),
        config.getDouble("hunt.tracking.facing-cone-max-visible-distance", 20.0));
  }

  private void recordHiderTrails() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (!player.isSprinting() || !isMapWorld(player.getWorld().getName()) || !isHider(player)) {
        continue;
      }
      hiderTrails
          .computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>())
          .addLast(new TrailPoint(player.getLocation(), System.currentTimeMillis()));
    }
  }

  private void pruneExpiredTrailPoints(double lifetimeMillis) {
    long now = System.currentTimeMillis();
    Iterator<Map.Entry<UUID, Deque<TrailPoint>>> entries = hiderTrails.entrySet().iterator();
    while (entries.hasNext()) {
      Deque<TrailPoint> trail = entries.next().getValue();
      while (!trail.isEmpty() && now - trail.peekFirst().timestampMillis() > lifetimeMillis) {
        trail.pollFirst();
      }
      if (trail.isEmpty()) {
        entries.remove();
      }
    }
  }

  private void renderFootprintsForHunters(double maxDistance) {
    if (hiderTrails.isEmpty()) {
      return;
    }
    for (Player hunter : Bukkit.getOnlinePlayers()) {
      if (!isMapWorld(hunter.getWorld().getName()) || !isHunter(hunter)) {
        continue;
      }
      for (Deque<TrailPoint> trail : hiderTrails.values()) {
        for (TrailPoint point : trail) {
          if (!point.location().getWorld().equals(hunter.getWorld())) {
            continue;
          }
          if (point.location().distanceSquared(hunter.getLocation()) > maxDistance * maxDistance) {
            continue;
          }
          hunter.spawnParticle(
              Particle.DUST, point.location(), 1, 0.0, 0.0, 0.0, 0.0, FOOTPRINT_DUST);
        }
      }
    }
  }

  private void renderFacingConeForHiders(double coneRange, double maxDistance) {
    for (Player hunter : Bukkit.getOnlinePlayers()) {
      if (!isMapWorld(hunter.getWorld().getName()) || !isHunter(hunter)) {
        continue;
      }

      Location eye = hunter.getEyeLocation();
      Vector direction = eye.getDirection().normalize();

      for (Player hider : Bukkit.getOnlinePlayers()) {
        if (!hider.getWorld().equals(hunter.getWorld()) || !isHider(hider)) {
          continue;
        }
        if (hider.getLocation().distanceSquared(hunter.getLocation()) > maxDistance * maxDistance) {
          continue;
        }
        for (double distance = 1.0; distance <= coneRange; distance += 1.0) {
          Location point = eye.clone().add(direction.clone().multiply(distance));
          hider.spawnParticle(Particle.DUST, point, 1, 0.05, 0.05, 0.05, 0.0, FACING_CONE_DUST);
        }
      }
    }
  }

  private boolean isHider(Player player) {
    HuntPlayerData data = lobbyManager.getPlayerData(player.getUniqueId());
    return data != null
        && data.getSelectedTeam() == HuntTeam.HIDERS
        && data.getSelectedHiderClass() != null;
  }

  private boolean isHunter(Player player) {
    HuntPlayerData data = lobbyManager.getPlayerData(player.getUniqueId());
    return data != null
        && data.getSelectedTeam() == HuntTeam.HUNTERS
        && data.getSelectedHunterClass() != null;
  }

  /** True only for an actual map world (not the lobby) - tracking only makes sense mid-round. */
  private boolean isMapWorld(String worldName) {
    if (worldName == null) {
      return false;
    }
    ConfigurationSection maps = config.getConfigurationSection("hunt.maps");
    if (maps == null) {
      return false;
    }
    for (String mapKey : maps.getKeys(false)) {
      if (worldName.equals(config.getString("hunt.maps." + mapKey + ".world"))) {
        return true;
      }
    }
    return false;
  }

  /** Clears all tracked hider trails. Call on round end / cleanup. */
  public void clearAllTrails() {
    hiderTrails.clear();
  }
}
