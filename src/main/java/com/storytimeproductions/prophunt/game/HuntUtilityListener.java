package com.storytimeproductions.prophunt.game;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import me.libraryaddict.disguise.DisguiseAPI;
import me.libraryaddict.disguise.disguisetypes.DisguiseType;
import me.libraryaddict.disguise.disguisetypes.MiscDisguise;
import me.libraryaddict.disguise.disguisetypes.watchers.FallingBlockWatcher;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Listener that handles the logic for hunter utility item abilities. Each hunter class has a unique
 * utility item with special abilities and cooldowns.
 */
public class HuntUtilityListener implements Listener {

  private final JavaPlugin plugin;
  private final FileConfiguration config;
  private final Map<UUID, Long> resilienceCooldowns;
  private final Map<UUID, Long> dashCooldowns;
  private final Map<UUID, Long> scannerCooldowns;
  private final HuntLobbyManager lobbyManager;
  private HiderUtilityListener hiderUtilityListener;

  /**
   * Constructs a new HuntUtilityListener.
   *
   * @param plugin The plugin instance
   * @param config The hunt configuration
   * @param lobbyManager The lobby manager for checking player states
   */
  public HuntUtilityListener(
      JavaPlugin plugin, FileConfiguration config, HuntLobbyManager lobbyManager) {
    this.plugin = plugin;
    this.config = config;
    this.lobbyManager = lobbyManager;
    this.resilienceCooldowns = new HashMap<>();
    this.dashCooldowns = new HashMap<>();
    this.scannerCooldowns = new HashMap<>();
  }

  /** Sets the hider utility listener for accessing stored block data. */
  public void setHiderUtilityListener(HiderUtilityListener listener) {
    this.hiderUtilityListener = listener;
  }

  /**
   * Handles player interactions with utility items.
   *
   * @param event The PlayerInteractEvent
   */
  @EventHandler
  public void onPlayerInteract(PlayerInteractEvent event) {
    Player player = event.getPlayer();
    ItemStack item = event.getItem();

    if (item == null || !item.hasItemMeta()) {
      return;
    }

    // Only handle right-click interactions
    if (!event.getAction().toString().contains("RIGHT_CLICK")) {
      return;
    }

    // Check if player is in hunt world and is a hunter
    boolean inHuntWorld = isPlayerInHuntWorld(player);
    boolean isHunter = isPlayerHunter(player);

    if (!inHuntWorld) {
      // Debug: Player not in hunt world
      org.bukkit.Bukkit.getLogger()
          .info(
              "[DEBUG] "
                  + player.getName()
                  + " tried to use utility in non-hunt world: "
                  + player.getWorld().getName());
      return;
    }

    if (!isHunter) {
      // Debug: Player not a hunter
      org.bukkit.Bukkit.getLogger()
          .info("[DEBUG] " + player.getName() + " tried to use utility but is not a hunter");
      return;
    }

    Material material = item.getType();
    UUID playerId = player.getUniqueId();

    org.bukkit.Bukkit.getLogger()
        .info("[DEBUG] " + player.getName() + " is using utility item: " + material.name());

    switch (material) {
      case TOTEM_OF_UNDYING:
        handleResilienceTotem(player, playerId, event);
        break;
      case FEATHER:
        handleDash(player, playerId, event);
        break;
      case ENDER_EYE:
        handleScanner(player, playerId, event);
        break;
      default:
        break;
    }
  }

  /**
   * Handles the Resilience Totem ability for the Brute class. Provides temporary resistance and
   * regeneration.
   *
   * @param player The player using the ability
   * @param playerId The player's UUID
   * @param event The interaction event
   */
  private void handleResilienceTotem(Player player, UUID playerId, PlayerInteractEvent event) {
    int cooldownSeconds = config.getInt("hunt.utility-cooldowns.resilience-totem", 30);

    if (isOnCooldown(playerId, resilienceCooldowns, cooldownSeconds)) {
      event.setCancelled(true);
      return;
    }

    // Apply resilience effects
    player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 200, 1)); // 10s
    player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1)); // 5s

    // Visual and audio effects
    player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation(), 20);
    player.playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 1.0f, 1.0f);

    // Set cooldown and notify
    resilienceCooldowns.put(playerId, System.currentTimeMillis());
    player.sendActionBar(
        Component.text("Resilience activated! ", NamedTextColor.GOLD)
            .append(Component.text("(" + cooldownSeconds + "s CD)", NamedTextColor.GRAY)));
    Bukkit.getScheduler()
        .runTaskLater(
            plugin,
            () -> {
              if (player.isOnline()) {
                player.sendActionBar(Component.text("✔ Resilience — Ready!", NamedTextColor.GREEN));
              }
            },
            cooldownSeconds * 20L);
    event.setCancelled(true);
  }

  /**
   * Handles the Dash ability for the Nimble class. Provides a quick speed boost.
   *
   * @param player The player using the ability
   * @param playerId The player's UUID
   * @param event The interaction event
   */
  private void handleDash(Player player, UUID playerId, PlayerInteractEvent event) {
    int cooldownSeconds = config.getInt("hunt.utility-cooldowns.dash", 15);

    if (isOnCooldown(playerId, dashCooldowns, cooldownSeconds)) {
      event.setCancelled(true);
      return;
    }

    // Apply dash effect
    player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 3)); // 3s of Speed IV

    // Visual and audio effects
    player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 15, 0.5, 0.1, 0.5, 0.1);
    player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 1.5f);

    // Set cooldown and notify
    dashCooldowns.put(playerId, System.currentTimeMillis());
    player.sendActionBar(
        Component.text("Dash activated! ", NamedTextColor.AQUA)
            .append(Component.text("(" + cooldownSeconds + "s CD)", NamedTextColor.GRAY)));
    Bukkit.getScheduler()
        .runTaskLater(
            plugin,
            () -> {
              if (player.isOnline()) {
                player.sendActionBar(Component.text("✔ Dash — Ready!", NamedTextColor.GREEN));
              }
            },
            cooldownSeconds * 20L);
    event.setCancelled(true);
  }

  /**
   * Handles the Scanner ability for the Saboteur class. Reveals nearby hiders temporarily.
   *
   * @param player The player using the ability
   * @param playerId The player's UUID
   * @param event The interaction event
   */
  private void handleScanner(Player player, UUID playerId, PlayerInteractEvent event) {
    int cooldownSeconds = config.getInt("hunt.utility-cooldowns.scanner", 25);

    if (isOnCooldown(playerId, scannerCooldowns, cooldownSeconds)) {
      event.setCancelled(true);
      return;
    }

    // Scan for nearby players (hiders) within 20 blocks
    int scanRadius = 20;

    for (Player nearbyPlayer : player.getWorld().getPlayers()) {
      if (!nearbyPlayer.equals(player)
          && nearbyPlayer.getLocation().distance(player.getLocation()) <= scanRadius) {
        // Check if the nearby player is a hider
        if (isPlayerHider(nearbyPlayer)) {
          final Player scanTarget = nearbyPlayer;
          var blockData =
              hiderUtilityListener != null
                  ? hiderUtilityListener.getPlayerBlockData(scanTarget.getUniqueId())
                  : null;
          if (DisguiseAPI.isDisguised(scanTarget) && blockData != null) {
            // Strip disguise so all hunters can see and chase them
            DisguiseAPI.undisguiseToAll(scanTarget);
            scanTarget.setGlowing(true);
            scanTarget.sendMessage(
                Component.text("A scanner revealed you! Re-disguise quickly!", NamedTextColor.RED));
            final var savedBlockData = blockData;
            new BukkitRunnable() {
              @Override
              public void run() {
                if (!scanTarget.isOnline()) {
                  return;
                }
                scanTarget.setGlowing(false);
                // Re-apply their block disguise after 5 seconds
                MiscDisguise restore = new MiscDisguise(DisguiseType.FALLING_BLOCK);
                restore.setReplaceSounds(true);
                var rw = (FallingBlockWatcher) restore.getWatcher();
                rw.setBlockData(savedBlockData);
                DisguiseAPI.disguiseToAll(scanTarget, restore);
              }
            }.runTaskLater(plugin, 100L);
          }
        }
      }
    }

    // Visual and audio effects
    player.getWorld().spawnParticle(Particle.END_ROD, player.getLocation(), 30, 3, 3, 3, 0.1);
    player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.2f);

    // Set cooldown and notify
    scannerCooldowns.put(playerId, System.currentTimeMillis());
    player.sendActionBar(
        Component.text("Scanner activated! ", NamedTextColor.LIGHT_PURPLE)
            .append(Component.text("(" + cooldownSeconds + "s CD)", NamedTextColor.GRAY)));
    Bukkit.getScheduler()
        .runTaskLater(
            plugin,
            () -> {
              if (player.isOnline()) {
                player.sendActionBar(Component.text("✔ Scanner — Ready!", NamedTextColor.GREEN));
              }
            },
            cooldownSeconds * 20L);
    event.setCancelled(true);
  }

  /**
   * Checks if a player is on cooldown for a specific ability.
   *
   * @param playerId The player's UUID
   * @param cooldownMap The cooldown map for the ability
   * @param cooldownSeconds The cooldown duration in seconds
   * @return true if the player is on cooldown, false otherwise
   */
  private boolean isOnCooldown(UUID playerId, Map<UUID, Long> cooldownMap, int cooldownSeconds) {
    Long lastUsed = cooldownMap.get(playerId);
    if (lastUsed == null) {
      return false;
    }
    long currentTime = System.currentTimeMillis();
    long cooldownMillis = cooldownSeconds * 1000L;
    return (currentTime - lastUsed) < cooldownMillis;
  }

  /**
   * Checks if a player is in the hunt world.
   *
   * @param player The player to check
   * @return true if the player is in the hunt world, false otherwise
   */
  private boolean isPlayerInHuntWorld(Player player) {
    String playerWorldName = player.getWorld().getName();

    // Get the lobby world name from config
    String lobbyWorldName = config.getString("hunt.world", "hunt");

    // The lobby world is a valid hunt world
    if (playerWorldName.equals(lobbyWorldName)) {
      return true;
    }

    // Check if the player is in any of the configured map worlds
    if (config.getConfigurationSection("hunt.maps") != null) {
      for (String mapKey : config.getConfigurationSection("hunt.maps").getKeys(false)) {
        String mapWorldName = config.getString("hunt.maps." + mapKey + ".world");
        if (mapWorldName != null && playerWorldName.equals(mapWorldName)) {
          return true;
        }
      }
    }

    // Fallback: check if it contains "hunt" but is NOT the lobby world
    if (playerWorldName.toLowerCase().contains("hunt") && !playerWorldName.equals(lobbyWorldName)) {
      return true;
    }

    return false;
  }

  /**
   * Checks if a player is a hunter (has selected a hunter class).
   *
   * @param player The player to check
   * @return true if the player is a hunter, false otherwise
   */
  private boolean isPlayerHunter(Player player) {
    // Don't allow spectators to use abilities
    if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
      org.bukkit.Bukkit.getLogger()
          .info("[DEBUG] " + player.getName() + " is spectator, not hunter");
      return false;
    }

    HuntPlayerData playerData = lobbyManager.getPlayerData(player.getUniqueId());

    if (playerData == null) {
      org.bukkit.Bukkit.getLogger().info("[DEBUG] " + player.getName() + " has no player data");
      return false;
    }

    HuntTeam selectedTeam = playerData.getSelectedTeam();
    HunterClass hunterClass = playerData.getSelectedHunterClass();

    if (selectedTeam != HuntTeam.HUNTERS) {
      org.bukkit.Bukkit.getLogger()
          .info(
              "[DEBUG] "
                  + player.getName()
                  + " is not on hunters team: "
                  + (selectedTeam != null ? selectedTeam.name() : "null"));
      return false;
    }

    boolean hasHunterClass = hunterClass != null;
    org.bukkit.Bukkit.getLogger()
        .info(
            "[DEBUG] "
                + player.getName()
                + " hunter class: "
                + (hunterClass != null ? hunterClass.name() : "null"));
    return hasHunterClass;
  }

  /**
   * Checks if a player is a hider (has selected a hider class).
   *
   * @param player The player to check
   * @return true if the player is a hider, false otherwise
   */
  private boolean isPlayerHider(Player player) {
    HuntPlayerData playerData = lobbyManager.getPlayerData(player.getUniqueId());
    if (playerData == null || playerData.getSelectedTeam() != HuntTeam.HIDERS) {
      return false;
    }
    return playerData.getSelectedHiderClass() != null;
  }

  /**
   * Blocks natural hostile mob spawning in hunt worlds while allowing plugin-spawned mobs (e.g.
   * Scarecrow clones). The world difficulty stays above PEACEFUL so plugin mob spawns work
   * normally.
   */
  @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
  public void onCreatureSpawn(CreatureSpawnEvent event) {
    String worldName = event.getLocation().getWorld().getName();
    if (!isHuntWorld(worldName)) {
      return;
    }

    SpawnReason reason = event.getSpawnReason();
    if (reason == SpawnReason.CUSTOM || reason == SpawnReason.SPAWNER_EGG) {
      return;
    }

    if (isHostileMob(event.getEntityType())) {
      event.setCancelled(true);
    }
  }

  private boolean isHuntWorld(String worldName) {
    if (worldName == null) {
      return false;
    }
    String lobbyWorldName = config.getString("hunt.world", "hunt");
    if (worldName.equals(lobbyWorldName)) {
      return true;
    }
    if (config.getConfigurationSection("hunt.maps") != null) {
      for (String mapKey : config.getConfigurationSection("hunt.maps").getKeys(false)) {
        String mapWorldName = config.getString("hunt.maps." + mapKey + ".world");
        if (worldName.equals(mapWorldName)) {
          return true;
        }
      }
    }
    return worldName.toLowerCase().contains("hunt");
  }

  private boolean isHostileMob(EntityType type) {
    switch (type) {
      case ZOMBIE:
      case ZOMBIE_VILLAGER:
      case HUSK:
      case DROWNED:
      case SKELETON:
      case STRAY:
      case WITHER_SKELETON:
      case SPIDER:
      case CAVE_SPIDER:
      case CREEPER:
      case ENDERMAN:
      case ENDERMITE:
      case SILVERFISH:
      case WITCH:
      case SLIME:
      case MAGMA_CUBE:
      case BLAZE:
      case GHAST:
      case PHANTOM:
      case PILLAGER:
      case VINDICATOR:
      case EVOKER:
      case VEX:
      case RAVAGER:
      case GUARDIAN:
      case ELDER_GUARDIAN:
      case WARDEN:
      case PIGLIN_BRUTE:
      case HOGLIN:
      case ZOGLIN:
      case BREEZE:
      case BOGGED:
        return true;
      default:
        return false;
    }
  }

  /**
   * Clears all cooldowns for a specific player. This should be called when a player disconnects or
   * leaves the hunt.
   *
   * @param playerId The UUID of the player
   */
  public void clearPlayerCooldowns(UUID playerId) {
    resilienceCooldowns.remove(playerId);
    dashCooldowns.remove(playerId);
    scannerCooldowns.remove(playerId);
  }

  /** Clears all cooldowns for all players. This should be called during server shutdown. */
  public void clearAllCooldowns() {
    resilienceCooldowns.clear();
    dashCooldowns.clear();
    scannerCooldowns.clear();
  }
}
