package com.storytimeproductions.prophunt.game;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Handles player elimination, PvP rule enforcement, and win-condition detection for Hunt games.
 * Players are never allowed to actually die - reaching 0 HP puts them into spectator mode instead,
 * tracked here until the round ends. Also enforces the team-based PvP rules (hunters can damage
 * hiders, not vice versa, with a narrow Trickster stun-on-hit exception - see
 * specs/trickster-stun-on-hit.md) and checks after every elimination whether a team has won.
 */
public class HuntDeathHandler implements Listener {
  private final JavaPlugin plugin;
  private final HuntPrepPhaseManager prepPhaseManager;
  private final HuntLobbyManager lobbyManager;
  private final Map<UUID, Location> deathLocations = new HashMap<>();
  private int aliveHidersCount = 0; // Track number of alive hiders
  private HuntDisguiseManager disguiseManager; // Reference to disguise manager for cleanup
  private HiderUtilityListener hiderUtilityListener; // For Trickster's stun-on-hit ability
  private final Set<String> huntWorlds; // Set of configured hunt world names
  private final Set<UUID> pendingSpectator =
      new HashSet<>(); // Tracks deaths being processed by handlePlayerSpectator to prevent

  // double-decrement from onGameModeChange

  // Known-safe location on the active map (the hunter-spawn from hunt.yml) to teleport players
  // who die in the void to, since the world's generic default spawn is not guaranteed to be safe
  // on custom hand-built map worlds.
  private Location safeRespawnLocation;

  /**
   * Constructs a new HuntDeathHandler.
   *
   * @param plugin The plugin instance
   * @param prepPhaseManager The prep phase manager to end the game when needed
   * @param lobbyManager The lobby manager for checking player teams
   */
  public HuntDeathHandler(
      JavaPlugin plugin, HuntPrepPhaseManager prepPhaseManager, HuntLobbyManager lobbyManager) {
    this.plugin = plugin;
    this.prepPhaseManager = prepPhaseManager;
    this.lobbyManager = lobbyManager;
    this.huntWorlds = new HashSet<>();

    // Load all hunt world names from config
    loadHuntWorlds();
  }

  /**
   * Loads all hunt world names from the hunt.yml configuration file. This includes both the main
   * hunt world and all map worlds.
   */
  private void loadHuntWorlds() {
    try {
      File huntConfigFile = new File(plugin.getDataFolder(), "hunt.yml");
      if (!huntConfigFile.exists()) {
        plugin.getLogger().warning("hunt.yml not found, using default hunt world detection");
        return;
      }

      FileConfiguration config = YamlConfiguration.loadConfiguration(huntConfigFile);

      // DO NOT add the main hunt world (lobby) - death handling should only apply to game worlds
      String mainWorld = config.getString("hunt.world");
      if (mainWorld != null && !mainWorld.isEmpty()) {
        plugin
            .getLogger()
            .info("Lobby world identified: " + mainWorld + " (death handling disabled)");
      }

      // Add only map worlds (actual game worlds) where death handling should apply
      ConfigurationSection mapsSection = config.getConfigurationSection("hunt.maps");
      if (mapsSection != null) {
        for (String mapKey : mapsSection.getKeys(false)) {
          String mapWorld = mapsSection.getString(mapKey + ".world");
          if (mapWorld != null && !mapWorld.isEmpty()) {
            huntWorlds.add(mapWorld);
            plugin
                .getLogger()
                .info("Added hunt game world: " + mapWorld + " (from map: " + mapKey + ")");
          }
        }
      }

      plugin
          .getLogger()
          .info(
              "Loaded "
                  + huntWorlds.size()
                  + " hunt game worlds from configuration (excluding lobby)");
    } catch (Exception e) {
      plugin.getLogger().severe("Error loading hunt worlds from configuration: " + e.getMessage());
      e.printStackTrace();
    }
  }

  /**
   * Sets the disguise manager reference for undisguising eliminated hiders.
   *
   * @param disguiseManager The disguise manager instance
   */
  public void setDisguiseManager(HuntDisguiseManager disguiseManager) {
    this.disguiseManager = disguiseManager;
  }

  /**
   * Sets the hider utility listener reference for Trickster's stun-on-hit ability.
   *
   * @param hiderUtilityListener The hider utility listener instance
   */
  public void setHiderUtilityListener(HiderUtilityListener hiderUtilityListener) {
    this.hiderUtilityListener = hiderUtilityListener;
  }

  /**
   * Sets the known-safe respawn location (the active map's hunter-spawn) that void-death players
   * are teleported to. Should be updated each time a game starts on a map.
   *
   * @param location The safe location to teleport void-death players to
   */
  public void setSafeRespawnLocation(Location location) {
    this.safeRespawnLocation = location;
  }

  /**
   * Handles damage events to detect when a player would die. If their health would reach 0, we
   * cancel the damage and handle death ourselves.
   */
  @EventHandler(priority = EventPriority.HIGH)
  public void onPlayerDamage(EntityDamageEvent event) {
    if (event.getEntity() instanceof Player) {
      Player player = (Player) event.getEntity();

      // Only handle players in Hunt worlds
      if (!isPlayerInHuntWorld(player)) {
        return;
      }

      // Check if this damage would kill the player
      double finalHealth = player.getHealth() - event.getFinalDamage();
      if (finalHealth <= 0) {
        // Cancel the damage to prevent actual death
        event.setCancelled(true);

        // Handle the "death" ourselves
        handlePlayerSpectator(player, event.getCause());

        // Check if game should end (all hiders eliminated)
        checkGameEndCondition();
      }
    }
  }

  /** Backup handler in case a player somehow dies despite our damage handler. */
  @EventHandler(priority = EventPriority.HIGH)
  public void onPlayerDeath(PlayerDeathEvent event) {
    Player player = event.getEntity();

    // Only handle players in Hunt worlds
    if (!isPlayerInHuntWorld(player)) {
      return;
    }

    // Cancel the death event
    event.setCancelled(true);

    // Set player health back to full
    player.setHealth(20.0); // Set to full health (20 is default max)

    // Handle the "death" ourselves
    handlePlayerSpectator(
        player,
        player.getLastDamageCause() != null
            ? player.getLastDamageCause().getCause()
            : EntityDamageEvent.DamageCause.CUSTOM);

    // Check if game should end (all hiders eliminated)
    checkGameEndCondition();
  }

  /**
   * Puts a player into spectator mode instead of letting them die.
   *
   * @param player The player who would have died
   * @param cause The cause of the "death"
   */
  private void handlePlayerSpectator(Player player, EntityDamageEvent.DamageCause cause) {
    // Safety check: Only handle players in Hunt worlds
    if (!isPlayerInHuntWorld(player)) {
      plugin
          .getLogger()
          .info("Skipping spectator handling for " + player.getName() + " - not in Hunt world");
      return;
    }

    // Store the player's death location for later effects
    deathLocations.put(player.getUniqueId(), player.getLocation().clone());

    // Mark as being processed so onGameModeChange doesn't double-decrement
    pendingSpectator.add(player.getUniqueId());

    // Teleport out of void before switching to spectator to prevent looping damage. Prefer the
    // map's known-safe hunter-spawn location over the world's generic default spawn, since the
    // latter is not guaranteed to be safe on custom hand-built map worlds and can still be in
    // (or above) the void, causing the loop this is meant to prevent.
    if (cause == EntityDamageEvent.DamageCause.VOID) {
      Location target =
          safeRespawnLocation != null ? safeRespawnLocation : player.getWorld().getSpawnLocation();
      player.teleport(target);
    }

    // Set to spectator mode
    player.setGameMode(GameMode.SPECTATOR);

    // Play death-like effects without actual death
    player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_DEATH, 1.0f, 1.0f);

    // Identify their team from the locked-in game roster (not the live, mutable lobby data)
    HuntTeam team = prepPhaseManager.getGameParticipants().get(player.getUniqueId());
    if (team == null) {
      return;
    }

    boolean isHider = team == HuntTeam.HIDERS;

    // Decrement the hider count if this was a hider
    if (isHider) {
      int previousCount = aliveHidersCount;
      aliveHidersCount = Math.max(0, aliveHidersCount - 1);
      plugin
          .getLogger()
          .info(
              "Hider "
                  + player.getName()
                  + " eliminated. Previous count: "
                  + previousCount
                  + ", New count: "
                  + aliveHidersCount);

      // Remove disguise and clear all effects for eliminated hiders
      if (disguiseManager != null) {
        disguiseManager.removeDisguise(player);
      }

      // Clear all potion effects to remove any hunter abilities or hider passives
      // Use a scheduled task to ensure effects are cleared after any potential
      // reapplication
      Bukkit.getScheduler()
          .runTaskLater(
              plugin,
              () -> {
                if (player.isOnline() && player.getGameMode() == GameMode.SPECTATOR) {
                  for (org.bukkit.potion.PotionEffect effect : player.getActivePotionEffects()) {
                    player.removePotionEffect(effect.getType());
                  }
                  plugin
                      .getLogger()
                      .info("Cleared effects from spectator hider: " + player.getName());
                }
              },
              5L); // Delay by 5 ticks (0.25 seconds) to ensure cleanup happens after any
      // reapplication

      plugin
          .getLogger()
          .info("Removed disguise and all effects from eliminated hider: " + player.getName());
    }

    // Broadcast message about the elimination
    String message;
    if (isHider) {
      message = player.getName() + " has been eliminated! (" + aliveHidersCount + " hiders remain)";
      broadcastMessage(message, NamedTextColor.RED);
    } else {
      message = player.getName() + " has been knocked out!";
      broadcastMessage(message, NamedTextColor.GOLD);
    }

    // Personalized message to the player
    if (isHider) {
      player.sendMessage(
          Component.text("You've been eliminated! You are now spectating.", NamedTextColor.RED));
    } else {
      player.sendMessage(
          Component.text("You've been knocked out! You are now spectating.", NamedTextColor.GOLD));
    }

    // Debug log
    plugin
        .getLogger()
        .info(
            "Player "
                + player.getName()
                + " eliminated and set to spectator mode. Cause: "
                + cause);
  }

  /**
   * Checks if all hiders have been eliminated or all hunters are knocked out, and ends the game if
   * needed.
   */
  private void checkGameEndCondition() {
    // Don't check win conditions during loading/lock-in phase
    if (!prepPhaseManager.isGameActive()) {
      plugin.getLogger().info("Skipping game end condition check - game not yet active");
      return;
    }

    // Get all active players
    Map<HuntTeam, List<Player>> activePlayersByTeam = getActivePlayersByTeam();

    // Debug logging for game end condition check
    List<Player> activeHiders =
        activePlayersByTeam.getOrDefault(HuntTeam.HIDERS, new ArrayList<>());
    List<Player> activeHunters =
        activePlayersByTeam.getOrDefault(HuntTeam.HUNTERS, new ArrayList<>());

    plugin
        .getLogger()
        .info(
            "Checking game end condition - aliveHidersCount: "
                + aliveHidersCount
                + ", active hiders: "
                + activeHiders.size()
                + ", active hunters: "
                + activeHunters.size());

    // Log each hider's status
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (isPlayerInHuntWorld(player)) {
        HuntPlayerData playerData = lobbyManager.getPlayerData(player.getUniqueId());
        if (playerData != null && playerData.getSelectedTeam() == HuntTeam.HIDERS) {
          plugin
              .getLogger()
              .info(
                  "Hider "
                      + player.getName()
                      + ": GameMode="
                      + player.getGameMode()
                      + ", Online="
                      + player.isOnline());
        }
      }
    }

    // Check if all hiders have been eliminated using our counter
    if (aliveHidersCount <= 0) {
      // All hiders eliminated, hunters win
      plugin.getLogger().info("All hiders eliminated - ending game with hunters win");
      prepPhaseManager.endGame(HuntTeam.HUNTERS);
      return;
    }

    // Check if all hunters have been knocked out (less common scenario)
    if (activeHunters.isEmpty()) {
      // All hunters knocked out, hiders win
      plugin.getLogger().info("All hunters eliminated - ending game with hiders win");
      prepPhaseManager.endGame(HuntTeam.HIDERS);
    }
  }

  /**
   * Spawns victory fireworks for the winning team and at all death locations.
   *
   * @param activePlayers The list of active players on the winning team
   */
  public void spawnVictoryFireworks(List<Player> activePlayers) {
    // Spawn fireworks for 3 seconds as requested
    new BukkitRunnable() {
      private int counter = 0;
      private final int totalFireworks = 3; // 3 second duration, 1 firework per second

      @Override
      public void run() {
        if (counter >= totalFireworks) {
          cancel();
          // Teleport all players back to lobby after fireworks
          prepPhaseManager.teleportAllPlayersToLobby();
          return;
        }

        // Spawn fireworks at active player locations
        for (Player player : activePlayers) {
          if (player != null && player.isOnline()) {
            spawnRandomFirework(player.getLocation());
          }
        }

        // Spawn fireworks at death locations
        for (Location location : deathLocations.values()) {
          spawnRandomFirework(location);
        }

        counter++;
      }
    }.runTaskTimer(plugin, 0L, 20L); // Run every second for 3 seconds
  }

  /**
   * Spawns a random colorful firework at the given location.
   *
   * @param location The location to spawn the firework
   */
  private void spawnRandomFirework(Location location) {
    World world = location.getWorld();
    if (world == null) {
      return;
    }

    Firework firework = (Firework) world.spawnEntity(location, EntityType.FIREWORK_ROCKET);
    FireworkMeta meta = firework.getFireworkMeta();

    // Random colors for the firework
    Color[] colors = {
      Color.RED,
      Color.BLUE,
      Color.GREEN,
      Color.YELLOW,
      Color.PURPLE,
      Color.AQUA,
      Color.FUCHSIA,
      Color.LIME,
      Color.ORANGE
    };

    Color primaryColor = colors[(int) (Math.random() * colors.length)];
    Color fadeColor = colors[(int) (Math.random() * colors.length)];

    // Random effect type
    FireworkEffect.Type[] types = FireworkEffect.Type.values();
    FireworkEffect.Type effectType = types[(int) (Math.random() * types.length)];

    FireworkEffect effect =
        FireworkEffect.builder()
            .withColor(primaryColor)
            .withFade(fadeColor)
            .with(effectType)
            .trail(Math.random() > 0.5)
            .flicker(Math.random() > 0.5)
            .build();

    meta.addEffect(effect);
    meta.setPower(1); // Short duration
    firework.setFireworkMeta(meta);
  }

  /**
   * Gets a map of active players (not in spectator mode) by team.
   *
   * <p>Team membership is read from the locked-in game roster ({@link
   * HuntPrepPhaseManager#getGameParticipants()}) rather than the live, mutable {@link
   * HuntPlayerData} in the lobby - the latter can drift after a game has locked in (e.g. a player's
   * lobby menu selections change mid-game), which previously caused win conditions to be evaluated
   * against the wrong team counts.
   *
   * @return A map with HuntTeam as key and a list of active players as value
   */
  private Map<HuntTeam, List<Player>> getActivePlayersByTeam() {
    Map<HuntTeam, List<Player>> result = new HashMap<>();
    Map<UUID, HuntTeam> lockedRoster = prepPhaseManager.getGameParticipants();

    for (Player player : Bukkit.getOnlinePlayers()) {
      // Skip players who are not in a hunt world or are in spectator mode
      if (!isPlayerInHuntWorld(player) || player.getGameMode() == GameMode.SPECTATOR) {
        continue;
      }

      // Get player's team from the locked-in roster
      HuntTeam team = lockedRoster.get(player.getUniqueId());
      if (team != null) {
        List<Player> teamPlayers = result.computeIfAbsent(team, k -> new ArrayList<>());
        teamPlayers.add(player);
      }
    }

    return result;
  }

  /** Resets stored death locations for a new game. */
  public void resetDeathLocations() {
    deathLocations.clear();
  }

  /** Resets stored death locations and counters for a new game. */
  public void resetForNewGame() {
    deathLocations.clear();
    aliveHidersCount = 0;
    plugin.getLogger().info("HuntDeathHandler reset for new game");
  }

  /**
   * Broadcasts a message to all players in Hunt worlds.
   *
   * @param message The message to broadcast
   * @param color The color of the message
   */
  private void broadcastMessage(String message, NamedTextColor color) {
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (isPlayerInHuntWorld(player)) {
        player.sendMessage(Component.text(message, color));
      }
    }
  }

  /**
   * Checks if a player is in a Hunt world.
   *
   * @param player The player to check
   * @return true if the player is in a Hunt world, false otherwise
   */
  private boolean isPlayerInHuntWorld(Player player) {
    String worldName = player.getWorld().getName();

    // First check our loaded hunt worlds from config (game worlds only)
    if (huntWorlds.contains(worldName)) {
      return true;
    }

    // If no worlds were loaded from config, fall back to name-based detection
    if (huntWorlds.isEmpty()) {
      // Check if it contains "hunt" in the name but exclude the lobby world
      String lowerWorldName = worldName.toLowerCase();
      if (lowerWorldName.contains("hunt") && !worldName.equals("hunt")) {
        plugin.getLogger().info("Detected hunt game world via naming convention: " + worldName);
        return true;
      }
    }

    return false;
  }

  /**
   * Initializes the alive hiders count at the start of a game. Should be called when the game
   * starts.
   *
   * <p>Counts from the locked-in game roster ({@link HuntPrepPhaseManager#getGameParticipants()})
   * rather than the live, mutable {@link HuntPlayerData} in the lobby - the latter can drift after
   * game start (e.g. a hider's lobby class selection changing), which previously undercounted alive
   * hiders and caused hunters to win prematurely after eliminating just one of several hiders.
   */
  public void initializeAliveHidersCount() {
    aliveHidersCount = 0;

    // Count all hiders from the locked-in roster
    for (HuntTeam team : prepPhaseManager.getGameParticipants().values()) {
      if (team == HuntTeam.HIDERS) {
        aliveHidersCount++;
      }
    }

    plugin.getLogger().info("Hunt game started with " + aliveHidersCount + " alive hiders");
  }

  /**
   * Gets the current count of alive hiders.
   *
   * @return The number of alive hiders
   */
  public int getAliveHidersCount() {
    return aliveHidersCount;
  }

  /**
   * Handles players manually changing to spectator mode. This ensures hiders who switch to
   * spectator are counted as eliminated.
   */
  @EventHandler
  public void onGameModeChange(PlayerGameModeChangeEvent event) {
    Player player = event.getPlayer();

    // Only handle players in Hunt worlds
    if (!isPlayerInHuntWorld(player)) {
      return;
    }

    // Only handle changes TO spectator mode (not FROM spectator mode)
    if (event.getNewGameMode() == GameMode.SPECTATOR
        && player.getGameMode() != GameMode.SPECTATOR) {
      // Skip if handlePlayerSpectator already decremented for this death
      if (pendingSpectator.remove(player.getUniqueId())) {
        return;
      }

      // Identify their team from the locked-in game roster (not the live, mutable lobby data)
      HuntTeam team = prepPhaseManager.getGameParticipants().get(player.getUniqueId());
      if (team == null) {
        return;
      }

      boolean isHider = team == HuntTeam.HIDERS;

      // Decrement the hider count if this was a hider
      if (isHider) {
        aliveHidersCount = Math.max(0, aliveHidersCount - 1);
        plugin
            .getLogger()
            .info(
                "Hider "
                    + player.getName()
                    + " manually became spectator. Remaining hiders: "
                    + aliveHidersCount);

        // Remove disguise and clear all effects for eliminated hiders
        if (disguiseManager != null) {
          disguiseManager.removeDisguise(player);
        }

        // Clear all potion effects to remove any hunter abilities or hider passives
        // Use a scheduled task to ensure effects are cleared after any potential
        // reapplication
        Bukkit.getScheduler()
            .runTaskLater(
                plugin,
                () -> {
                  if (player.isOnline() && player.getGameMode() == GameMode.SPECTATOR) {
                    for (org.bukkit.potion.PotionEffect effect : player.getActivePotionEffects()) {
                      player.removePotionEffect(effect.getType());
                    }
                    plugin
                        .getLogger()
                        .info("Cleared effects from manually spectated hider: " + player.getName());
                  }
                },
                5L); // Delay by 5 ticks (0.25 seconds) to ensure cleanup happens after any
        // reapplication

        plugin
            .getLogger()
            .info(
                "Removed disguise and all effects from manually eliminated hider: "
                    + player.getName());

        // Check if game should end
        checkGameEndCondition();
      }
    }
  }

  /**
   * Handles PvP events to enforce Hunt game rules: - Hiders cannot damage hunters - Hunters can
   * damage hiders - Spectators cannot damage anyone or be damaged by anyone.
   */
  @EventHandler(priority = EventPriority.HIGHEST)
  public void onPlayerVsPlayerDamage(EntityDamageByEntityEvent event) {
    // Only handle player vs player damage
    if (!(event.getEntity() instanceof Player) || !(event.getDamager() instanceof Player)) {
      return;
    }

    Player victim = (Player) event.getEntity();
    Player attacker = (Player) event.getDamager();

    // Only handle players in Hunt worlds
    if (!isPlayerInHuntWorld(victim) || !isPlayerInHuntWorld(attacker)) {
      return;
    }

    // Prevent spectators from dealing or taking damage
    if (victim.getGameMode() == GameMode.SPECTATOR
        || attacker.getGameMode() == GameMode.SPECTATOR) {
      event.setCancelled(true);
      return;
    }

    // Get player team data
    HuntPlayerData victimData = lobbyManager.getPlayerData(victim.getUniqueId());
    HuntPlayerData attackerData = lobbyManager.getPlayerData(attacker.getUniqueId());

    // If either player doesn't have data, they're not in the game - allow damage
    if (victimData == null || attackerData == null) {
      return;
    }

    HuntTeam victimTeam = victimData.getSelectedTeam();
    HuntTeam attackerTeam = attackerData.getSelectedTeam();

    // If either player doesn't have a team assigned, they're not in the game -
    // allow damage
    if (victimTeam == null || attackerTeam == null) {
      return;
    }

    // Enforce Hunt PvP rules
    if (attackerTeam == HuntTeam.HIDERS && victimTeam == HuntTeam.HUNTERS) {
      // Hiders cannot deal real damage to hunters, but Trickster's stun-on-hit ability is a
      // deliberate exception - see specs/trickster-stun-on-hit.md. The hit itself never deals
      // damage either way; it either lands the stun or is just a blocked attack.
      event.setCancelled(true);
      boolean stunned =
          hiderUtilityListener != null && hiderUtilityListener.tryTricksterStrike(attacker, victim);
      if (!stunned) {
        attacker.sendMessage(
            Component.text("You cannot attack hunters as a hider!", NamedTextColor.RED));
        plugin
            .getLogger()
            .info(
                "Blocked hider "
                    + attacker.getName()
                    + " from attacking hunter "
                    + victim.getName());
      }
    } else if (attackerTeam == HuntTeam.HUNTERS && victimTeam == HuntTeam.HIDERS) {
      // Hunters can damage hiders - allow the damage (don't cancel)
      prepPhaseManager.recordHiderHit(victim.getUniqueId());
      plugin
          .getLogger()
          .info("Allowed hunter " + attacker.getName() + " to attack hider " + victim.getName());
    } else if (attackerTeam == victimTeam) {
      // Same team - prevent friendly fire
      event.setCancelled(true);
      attacker.sendMessage(
          Component.text("You cannot attack your teammates!", NamedTextColor.YELLOW));
      plugin
          .getLogger()
          .info("Blocked friendly fire between " + attacker.getName() + " and " + victim.getName());
    }
  }
}
