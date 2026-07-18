package com.storytimeproductions.prophunt.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * Implements each hunter class's utility item ability (Brute's Shockwave, Nimble's Dash, Saboteur's
 * Scanner) and their cooldowns. Also owns the persistent, unified ability-status action bar shown
 * to every hunter and hider - merging in a hider's heartbeat proximity warning (from {@link
 * HuntPrepPhaseManager}) and idle-spotlight countdown (from {@link HuntSpotlightListener}) as
 * leading segments so the several independent tension systems don't fight over the action bar slot
 * - see specs/ability-status-actionbar.md.
 */
public class HuntUtilityListener implements Listener {

  private final JavaPlugin plugin;
  private final FileConfiguration config;
  private final Map<UUID, Long> resilienceCooldowns;
  private final Map<UUID, Long> dashCooldowns;
  private final Map<UUID, Long> scannerCooldowns;
  private final HuntLobbyManager lobbyManager;
  private HiderUtilityListener hiderUtilityListener;
  private HuntDisguisePassiveListener huntDisguisePassiveListener;
  private HuntPrepPhaseManager prepPhaseManager;
  private HuntSpotlightListener spotlightListener;

  // Ticks between action bar refreshes for the persistent ability status display.
  private static final long ABILITY_STATUS_INTERVAL_TICKS = 10L;

  // Tracks each player's previous ability on-cooldown states, keyed by ability label, so the
  // ready sound plays exactly once on the cooldown->ready transition rather than every refresh.
  private final Map<UUID, Map<String, Boolean>> previousOnCooldown;

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
    this.previousOnCooldown = new HashMap<>();

    Bukkit.getScheduler()
        .runTaskTimer(
            plugin, this::updateAbilityStatusActionBars, 20L, ABILITY_STATUS_INTERVAL_TICKS);
  }

  /** Sets the hider utility listener for accessing stored block data. */
  public void setHiderUtilityListener(HiderUtilityListener listener) {
    this.hiderUtilityListener = listener;
  }

  /** Sets the disguise passive listener for reading hunters' disguise-specific ability status. */
  public void setHuntDisguisePassiveListener(HuntDisguisePassiveListener listener) {
    this.huntDisguisePassiveListener = listener;
  }

  /** Sets the prep phase manager for merging in a hider's heartbeat proximity warning. */
  public void setPrepPhaseManager(HuntPrepPhaseManager manager) {
    this.prepPhaseManager = manager;
  }

  /** Sets the spotlight listener for merging in a hider's idle-spotlight countdown. */
  public void setSpotlightListener(HuntSpotlightListener listener) {
    this.spotlightListener = listener;
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

    boolean debug = plugin.getConfig().getBoolean("debug", false);

    // Check if player is in hunt world and is a hunter
    boolean inHuntWorld = isPlayerInHuntWorld(player);
    boolean isHunter = isPlayerHunter(player);

    if (!inHuntWorld) {
      if (debug) {
        org.bukkit.Bukkit.getLogger()
            .info(
                "[DEBUG] "
                    + player.getName()
                    + " tried to use utility in non-hunt world: "
                    + player.getWorld().getName());
      }
      return;
    }

    if (!isHunter) {
      if (debug) {
        org.bukkit.Bukkit.getLogger()
            .info("[DEBUG] " + player.getName() + " tried to use utility but is not a hunter");
      }
      return;
    }

    Material material = item.getType();
    UUID playerId = player.getUniqueId();

    if (debug) {
      org.bukkit.Bukkit.getLogger()
          .info("[DEBUG] " + player.getName() + " is using utility item: " + material.name());
    }

    switch (material) {
      case TOTEM_OF_UNDYING:
        handleBruteShockwave(player, playerId, event);
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
   * Handles the Brute's Shockwave ability - replaced the old Resilience Totem (see
   * specs/brute-rebrand-research.md), which was a defensive buff for a class that almost never
   * takes real damage in this game. Knocks back nearby hiders and reveals any who are currently
   * disguised, giving Brute its own proactive "smash the room, see who scatters" detection lane
   * distinct from Saboteur's longer-range Scanner.
   *
   * @param player The player using the ability
   * @param playerId The player's UUID
   * @param event The interaction event
   */
  private void handleBruteShockwave(Player player, UUID playerId, PlayerInteractEvent event) {
    int cooldownSeconds = config.getInt("hunt.utility-cooldowns.brute-shockwave", 30);

    if (isOnCooldown(playerId, resilienceCooldowns, cooldownSeconds)) {
      event.setCancelled(true);
      return;
    }

    double radius = config.getDouble("hunt.utility-cooldowns.brute-shockwave-radius", 6.0);
    int glowDurationSeconds =
        config.getInt("hunt.utility-cooldowns.brute-shockwave-glow-seconds", 4);
    org.bukkit.Location origin = player.getLocation();

    for (Entity nearby : player.getWorld().getNearbyEntities(origin, radius, radius, radius)) {
      if (!(nearby instanceof Player target) || target.equals(player) || !isPlayerHider(target)) {
        continue;
      }

      Vector direction = target.getLocation().toVector().subtract(origin.toVector());
      if (direction.lengthSquared() < 0.0001) {
        direction = new Vector(0, 0, 1);
      }
      direction.normalize().multiply(1.4).setY(0.5);
      target.setVelocity(direction);

      if (hiderUtilityListener != null
          && hiderUtilityListener.getPlayerBlockData(target.getUniqueId()) != null) {
        target.addPotionEffect(
            new PotionEffect(
                PotionEffectType.GLOWING, glowDurationSeconds * 20, 0, false, false, true));
        target.sendMessage(Component.text("A shockwave revealed you!", NamedTextColor.RED));
      }
    }

    // Visual and audio effects
    player.getWorld().spawnParticle(Particle.EXPLOSION, origin, 1);
    player
        .getWorld()
        .spawnParticle(Particle.SWEEP_ATTACK, origin, 8, radius / 3, 0.3, radius / 3, 0);
    player.playSound(origin, Sound.ENTITY_RAVAGER_ROAR, 1.0f, 0.8f);
    player.playSound(origin, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.4f);

    // Set cooldown - status is shown by the persistent ability status action bar
    resilienceCooldowns.put(playerId, System.currentTimeMillis());
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

    // Set cooldown - status is shown by the persistent ability status action bar
    dashCooldowns.put(playerId, System.currentTimeMillis());
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
          } else if (scanTarget.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
            // Cloaker's invisibility used to be a hard counter to Scanner - a cloaked hider
            // isn't disguised, so the branch above never caught them (see
            // specs/cloaker-balance.md). Strip the invisibility too so Scanner has real
            // counterplay against it.
            scanTarget.removePotionEffect(PotionEffectType.INVISIBILITY);
            scanTarget.setGlowing(true);
            scanTarget.sendMessage(
                Component.text("A scanner revealed you despite your cloak!", NamedTextColor.RED));
            new BukkitRunnable() {
              @Override
              public void run() {
                if (scanTarget.isOnline()) {
                  scanTarget.setGlowing(false);
                }
              }
            }.runTaskLater(plugin, 100L);
          }
        }
      }
    }

    // Visual and audio effects
    player.getWorld().spawnParticle(Particle.END_ROD, player.getLocation(), 30, 3, 3, 3, 0.1);
    player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.2f);

    // Set cooldown - status is shown by the persistent ability status action bar
    scannerCooldowns.put(playerId, System.currentTimeMillis());
    event.setCancelled(true);
  }

  /**
   * Refreshes the persistent ability status action bar for every online player currently in the
   * hunt world with a class selected, and plays the ready sound for any ability that just came off
   * cooldown since the previous refresh.
   */
  private void updateAbilityStatusActionBars() {
    boolean debug = plugin.getConfig().getBoolean("debug", false);
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (!isPlayerInHuntWorld(player)) {
        continue;
      }

      boolean hunter = isPlayerHunter(player);
      boolean hider = hiderUtilityListener != null && isPlayerHider(player);
      List<AbilityStatus> statuses;
      if (hunter) {
        statuses = getHunterAbilityStatuses(player);
      } else if (hider) {
        statuses = hiderUtilityListener.getHiderAbilityStatuses(player);
        if (spotlightListener != null) {
          Integer idleCountdown = spotlightListener.getIdleCountdownSeconds(player.getUniqueId());
          if (idleCountdown != null) {
            int minIdleSeconds = config.getInt("hunt.spotlight.min-idle-seconds", 12);
            statuses.add(
                new AbilityStatus("EXPOSED IN", true, idleCountdown, minIdleSeconds, true));
          }
        }
      } else {
        statuses = List.of();
      }
      if (debug) {
        plugin
            .getLogger()
            .info(
                "[DEBUG] ability-bar "
                    + player.getName()
                    + " hunter="
                    + hunter
                    + " hider="
                    + hider
                    + " statuses="
                    + statuses.size());
      }
      // Merge in the hider's heartbeat proximity warning (if any) as the leading segment,
      // instead of the two systems fighting over the action bar slot independently.
      Component heartbeatMessage =
          (hider && prepPhaseManager != null)
              ? prepPhaseManager.getHeartbeatMessage(player.getUniqueId())
              : null;

      if (statuses.isEmpty() && heartbeatMessage == null) {
        continue;
      }

      Map<String, Boolean> playerPrevious =
          previousOnCooldown.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
      Component bar = Component.empty();
      if (heartbeatMessage != null) {
        bar = bar.append(heartbeatMessage);
        if (!statuses.isEmpty()) {
          bar = bar.append(Component.text("   "));
        }
      }
      for (int i = 0; i < statuses.size(); i++) {
        AbilityStatus status = statuses.get(i);
        Boolean wasOnCooldown = playerPrevious.get(status.label());
        if (Boolean.TRUE.equals(wasOnCooldown) && !status.onCooldown()) {
          player.playSound(player.getLocation(), Sound.BLOCK_CHAIN_BREAK, 1.0f, 1.0f);
        }
        playerPrevious.put(status.label(), status.onCooldown());

        if (i > 0) {
          bar = bar.append(Component.text("   "));
        }
        bar = bar.append(status.render());
      }
      player.sendActionBar(bar);
    }
  }

  /**
   * Returns this hunter's current ability statuses for the persistent action bar: their class
   * ability first, then their disguise-specific passive ability if they're currently disguised as
   * one with an idle-time ability. Empty if they aren't a valid hunter.
   *
   * @param player The player to check
   * @return The player's ability statuses, in display order
   */
  private List<AbilityStatus> getHunterAbilityStatuses(Player player) {
    HuntPlayerData playerData = lobbyManager.getPlayerData(player.getUniqueId());
    HunterClass hunterClass = playerData != null ? playerData.getSelectedHunterClass() : null;
    if (hunterClass == null) {
      return List.of();
    }

    UUID playerId = player.getUniqueId();
    List<AbilityStatus> statuses = new ArrayList<>();
    switch (hunterClass) {
      case BRUTE ->
          statuses.add(
              buildHunterStatus("SHOCKWAVE", playerId, resilienceCooldowns, "brute-shockwave", 30));
      case NIMBLE -> statuses.add(buildHunterStatus("DASH", playerId, dashCooldowns, "dash", 15));
      case SABOTEUR ->
          statuses.add(buildHunterStatus("SCANNER", playerId, scannerCooldowns, "scanner", 25));
      default -> {
        // No action needed for unknown hunter classes
      }
    }

    if (huntDisguisePassiveListener != null) {
      huntDisguisePassiveListener.getDisguiseAbilityStatus(player).ifPresent(statuses::add);
    }

    // Ambient victim-side status for a Trickster trap - parity with hiders' "SLOWED" indicator
    // for Springtrap's Vengeful Echo (see specs/ambient-tracking-layer.md).
    PotionEffect slowness = player.getPotionEffect(PotionEffectType.SLOWNESS);
    if (slowness != null) {
      int remainingSeconds = (int) Math.ceil(slowness.getDuration() / 20.0);
      statuses.add(new AbilityStatus("TRAPPED", true, remainingSeconds, 3, true));
    }

    return statuses;
  }

  private AbilityStatus buildHunterStatus(
      String label,
      UUID playerId,
      Map<UUID, Long> cooldownMap,
      String configKey,
      int defaultCooldown) {
    int totalSeconds = config.getInt("hunt.utility-cooldowns." + configKey, defaultCooldown);
    boolean onCooldown = isOnCooldown(playerId, cooldownMap, totalSeconds);
    long remaining = onCooldown ? getRemainingCooldown(playerId, cooldownMap, totalSeconds) : 0;
    // No coded activation condition for hunter abilities today - always shown as met.
    return new AbilityStatus(label, onCooldown, remaining, totalSeconds, true);
  }

  /**
   * Returns the remaining cooldown time in seconds for a player's ability.
   *
   * @param playerId The player's UUID
   * @param cooldownMap The cooldown map for the ability
   * @param cooldownSeconds The cooldown duration in seconds
   * @return Remaining seconds, or 0 if not on cooldown
   */
  private long getRemainingCooldown(
      UUID playerId, Map<UUID, Long> cooldownMap, int cooldownSeconds) {
    Long lastUsed = cooldownMap.get(playerId);
    if (lastUsed == null) {
      return 0;
    }
    long remainingMillis = (cooldownSeconds * 1000L) - (System.currentTimeMillis() - lastUsed);
    // Round up, not down - see HiderUtilityListener.getRemainingCooldown for why.
    return Math.max(0, (long) Math.ceil(remainingMillis / 1000.0));
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
    boolean debug = plugin.getConfig().getBoolean("debug", false);

    // Don't allow spectators to use abilities
    if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
      if (debug) {
        org.bukkit.Bukkit.getLogger()
            .info("[DEBUG] " + player.getName() + " is spectator, not hunter");
      }
      return false;
    }

    HuntPlayerData playerData = lobbyManager.getPlayerData(player.getUniqueId());

    if (playerData == null) {
      if (debug) {
        org.bukkit.Bukkit.getLogger().info("[DEBUG] " + player.getName() + " has no player data");
      }
      return false;
    }

    HuntTeam selectedTeam = playerData.getSelectedTeam();
    HunterClass hunterClass = playerData.getSelectedHunterClass();

    if (selectedTeam != HuntTeam.HUNTERS) {
      if (debug) {
        org.bukkit.Bukkit.getLogger()
            .info(
                "[DEBUG] "
                    + player.getName()
                    + " is not on hunters team: "
                    + (selectedTeam != null ? selectedTeam.name() : "null"));
      }
      return false;
    }

    boolean hasHunterClass = hunterClass != null;
    if (debug) {
      org.bukkit.Bukkit.getLogger()
          .info(
              "[DEBUG] "
                  + player.getName()
                  + " hunter class: "
                  + (hunterClass != null ? hunterClass.name() : "null"));
    }
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

  /** Prevents hunger from depleting anywhere in the hunt lobby or map worlds. */
  @EventHandler(ignoreCancelled = true)
  public void onFoodLevelChange(FoodLevelChangeEvent event) {
    if (!(event.getEntity() instanceof Player)) {
      return;
    }
    if (!isHuntWorld(event.getEntity().getWorld().getName())) {
      return;
    }
    event.setCancelled(true);
  }

  /** Disables the locator bar for players joining directly into a hunt world. */
  @EventHandler
  public void onPlayerJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    if (isHuntWorld(player.getWorld().getName())) {
      setLocatorBarEnabled(player, false);
    }
  }

  /** Disables the locator bar entering a hunt world, and restores it when leaving one. */
  @EventHandler
  public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
    Player player = event.getPlayer();
    boolean nowInHuntWorld = isHuntWorld(player.getWorld().getName());
    boolean wasInHuntWorld = isHuntWorld(event.getFrom().getName());
    if (nowInHuntWorld != wasInHuntWorld) {
      setLocatorBarEnabled(player, !nowInHuntWorld);
    }
  }

  /**
   * Enables or disables the vanilla locator bar for a player by zeroing (or restoring) the waypoint
   * transmit/receive attributes, so hiders and hunters can't see each other's positions via the
   * HUD.
   */
  private void setLocatorBarEnabled(Player player, boolean enabled) {
    setWaypointAttribute(player, Attribute.WAYPOINT_TRANSMIT_RANGE, enabled);
    setWaypointAttribute(player, Attribute.WAYPOINT_RECEIVE_RANGE, enabled);
  }

  private void setWaypointAttribute(Player player, Attribute attribute, boolean enabled) {
    AttributeInstance instance = player.getAttribute(attribute);
    if (instance == null) {
      return;
    }
    instance.setBaseValue(enabled ? instance.getDefaultValue() : 0.0);
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
    previousOnCooldown.remove(playerId);
  }

  /** Clears all cooldowns for all players. This should be called during server shutdown. */
  public void clearAllCooldowns() {
    resilienceCooldowns.clear();
    dashCooldowns.clear();
    scannerCooldowns.clear();
    previousOnCooldown.clear();
  }
}
