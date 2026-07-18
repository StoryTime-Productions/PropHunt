package com.storytimeproductions.prophunt.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

/**
 * Owns the full Hunt round lifecycle: map voting and ready-status tracking during the lobby prep
 * phase, the countdown and hunter lock-in that start a round, the round timer, win-condition
 * hand-off to {@link HuntDeathHandler}, and returning everyone to the lobby (and restarting prep
 * phase) once a round ends. Also drives the two proximity-based tension systems layered on top of
 * the core loop - the distance-scaled heartbeat warning and the "recently hurt" combat-tension
 * state (elevated heartbeat plus a raid-bell alarm that persists until a hider actually escapes) -
 * see specs/proximity-tier-loops.md and specs/recently-hurt-tension.md.
 */
public class HuntPrepPhaseManager {
  private final JavaPlugin plugin;
  private final HuntKitManager kitManager;
  private final HuntLobbyManager lobbyManager;
  private final HuntDisguiseManager disguiseManager;
  private final HuntHologramManager hologramManager;
  private final FileConfiguration config;
  private final HuntGameModeManager gameModeManager;
  private final ImposterGameManager imposterGameManager;
  private HuntDeathHandler deathHandler; // Reference to death handler for counting hiders
  private HuntSpotlightListener spotlightListener; // For clearing idle-spotlight state on end

  // Voting and ready status tracking
  private final Map<UUID, HuntMap> playerMapVotes;
  private final Map<HuntMap, Integer> mapVoteCounts;
  private final Map<UUID, Boolean> playerReadyStatus;

  // Phase state management
  private boolean prepPhaseActive;
  private boolean gameStarting;
  private boolean gameActive; // True when actual gameplay has started (after lock-in)
  private boolean gameEnded;

  private HuntMap selectedMap;
  private BukkitTask countdownTask;
  private BukkitTask gameTimer;
  private BukkitTask heartbeatTask;
  private BossBar gameTimerBar;

  // Individual heartbeat tasks for each hider (proximity-based)
  private final Map<UUID, BukkitRunnable> hiderHeartbeatTasks = new HashMap<>();

  // Current heartbeat warning per hider, if any - tracked here instead of sent directly via
  // sendActionBar() so HuntUtilityListener's unified ability status action bar can merge it in
  // as one segment instead of the two systems fighting over the action bar slot.
  private final Map<UUID, Component> currentHeartbeatMessages = new HashMap<>();

  // "Recently hurt" combat-tension state - see specs/recently-hurt-tension.md.
  // Independent of the proximity heartbeat above: driven by hits, not hunter distance.
  private final Map<UUID, Long> lastHitTimestamps = new HashMap<>();
  private final Set<UUID> hidersInHurtState = new HashSet<>();
  private final Map<UUID, BukkitRunnable> hurtHeartbeatTasks = new HashMap<>();
  private final Map<UUID, BukkitRunnable> hurtBellRepeatTasks = new HashMap<>();

  // Game participants (only ready players)
  private final Map<UUID, HuntTeam> gameParticipants;
  private final Map<UUID, Object> gameClassSelections; // HunterClass or HiderClass

  // All players who have selected classes (regardless of ready status)
  private final Map<UUID, HuntTeam> allPlayerSelections;

  /**
   * Constructs a new HuntPrepPhaseManager.
   *
   * @param plugin The JavaPlugin instance
   * @param lobbyManager The lobby manager
   * @param hologramManager The hologram manager
   * @param disguiseManager The disguise manager
   * @param kitManager The kit manager
   * @param config The configuration
   * @param gameModeManager The gamemode manager
   */
  public HuntPrepPhaseManager(
      JavaPlugin plugin,
      HuntLobbyManager lobbyManager,
      HuntHologramManager hologramManager,
      HuntDisguiseManager disguiseManager,
      HuntKitManager kitManager,
      FileConfiguration config,
      HuntGameModeManager gameModeManager,
      ImposterGameManager imposterGameManager) {
    this.plugin = plugin;
    this.lobbyManager = lobbyManager;
    this.hologramManager = hologramManager;
    this.disguiseManager = disguiseManager;
    this.kitManager = kitManager;
    this.config = config;
    this.gameModeManager = gameModeManager;
    this.imposterGameManager = imposterGameManager;
    this.playerMapVotes = new HashMap<>();
    this.mapVoteCounts = new HashMap<>();
    this.playerReadyStatus = new HashMap<>();
    this.gameParticipants = new HashMap<>();
    this.gameClassSelections = new HashMap<>();
    this.allPlayerSelections = new HashMap<>();
    this.prepPhaseActive = false;
    this.gameStarting = false;
    this.gameActive = false;
    this.gameEnded = false;

    // Initialize map vote counts
    for (HuntMap map : HuntMap.values()) {
      mapVoteCounts.put(map, 0);
    }
  }

  /** Starts the prep phase and creates all necessary holograms. */
  public void startPrepPhase() {
    if (prepPhaseActive) {
      return;
    }

    prepPhaseActive = true;
    gameStarting = false;
    gameActive = false;
    gameEnded = false;

    plugin.getLogger().info("Starting Hunt prep phase");

    // Clear previous state
    playerMapVotes.clear();
    playerReadyStatus.clear();
    gameParticipants.clear();
    gameClassSelections.clear();
    allPlayerSelections.clear();

    for (HuntMap map : HuntMap.values()) {
      mapVoteCounts.put(map, 0);
    }

    // Refresh class/map/gamemode holograms so they show text even if startup timing was off.
    // Must run before resyncPersistedPlayerSelections() below - it clears all class-selection
    // state, so anything resynced (or already set by the player whose own click triggered this
    // prep phase) needs to be written after this clear, not before.
    hologramManager.initializeClassHologramTitles();
    hologramManager.updateGameModeDisplay(gameModeManager.getCurrentGameMode());

    // Re-sync any player who already has a persisted team+class from a previous round so
    // they don't have to reselect their role every round (map vote is intentionally excluded -
    // players vote fresh each round).
    resyncPersistedPlayerSelections();

    // Initialize start game hologram
    initializeStartGameHologram();

    // Initialize ready status hologram
    initializeReadyStatusHologram();

    // Update start game hologram to initial state
    updateStartGameHologram();

    // Notify all players
    for (Player player : Bukkit.getOnlinePlayers()) {
      player.sendMessage(
          Component.text(
              "Hunt prep phase started! Vote for a map and get ready!", NamedTextColor.GREEN));
    }
  }

  /**
   * Re-registers each online player's previously selected team+class (persisted in their
   * HuntPlayerData across rounds) into the hologram class tracking and allPlayerSelections, so a
   * returning player's role from the prior round is immediately valid again instead of requiring
   * them to reselect it every round.
   */
  private void resyncPersistedPlayerSelections() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      HuntPlayerData data = lobbyManager.getPlayerData(player.getUniqueId());
      if (data == null || data.getSelectedTeam() == null) {
        continue;
      }

      HuntTeam team = data.getSelectedTeam();
      Object classSelection =
          team == HuntTeam.HUNTERS ? data.getSelectedHunterClass() : data.getSelectedHiderClass();
      if (classSelection == null) {
        continue;
      }

      String className =
          team == HuntTeam.HUNTERS
              ? ((HunterClass) classSelection).name().toLowerCase()
              : ((HiderClass) classSelection).name().toLowerCase();

      // addPlayerToClass toggles: calling it for a player whose hologram-side selection
      // already matches their persisted data (e.g. the player whose own click just
      // triggered this resync) would un-select them instead of leaving them alone.
      String hologramId = (team == HuntTeam.HUNTERS ? "hunter_" : "hider_") + className;
      if (hologramId.equals(hologramManager.getPlayerClassSelection(player.getUniqueId()))) {
        allPlayerSelections.put(player.getUniqueId(), team);
        continue;
      }

      hologramManager.addPlayerToClass(
          player.getUniqueId(), player.getName(), className, team == HuntTeam.HUNTERS);
      allPlayerSelections.put(player.getUniqueId(), team);

      plugin
          .getLogger()
          .info(
              "Re-synced persisted role for " + player.getName() + ": " + team + " / " + className);
    }
  }

  /** Ends the prep phase and removes players from holograms. */
  public void endPrepPhase() {
    if (!prepPhaseActive) {
      return;
    }

    prepPhaseActive = false;

    // Cancel any running tasks
    if (countdownTask != null) {
      countdownTask.cancel();
      countdownTask = null;
    }

    if (heartbeatTask != null) {
      heartbeatTask.cancel();
      heartbeatTask = null;
    }

    // Cancel all individual hider heartbeat tasks
    for (BukkitRunnable task : hiderHeartbeatTasks.values()) {
      if (task != null) {
        task.cancel();
      }
    }
    hiderHeartbeatTasks.clear();
    currentHeartbeatMessages.clear();
    clearAllHurtTensionState();
    if (spotlightListener != null) {
      spotlightListener.clearAllState();
    }

    removePlayersFromAllHolograms();

    plugin.getLogger().info("Hunt prep phase ended");
  }

  /**
   * Handles a player voting for a map.
   *
   * @param player The player voting
   * @param map The map they're voting for
   */
  public void handleMapVote(Player player, HuntMap map) {
    if (!prepPhaseActive || gameStarting) {
      return;
    }

    UUID playerId = player.getUniqueId();
    HuntMap previousVote = playerMapVotes.get(playerId);

    // Remove previous vote
    if (previousVote != null) {
      mapVoteCounts.put(previousVote, mapVoteCounts.get(previousVote) - 1);
    }

    // Add new vote
    playerMapVotes.put(playerId, map);
    mapVoteCounts.put(map, mapVoteCounts.get(map) + 1);

    player.sendMessage(
        Component.text("Voted for " + map.getDisplayName() + "!", NamedTextColor.YELLOW));

    // Update game participants since map vote is now required
    HuntPlayerData data = lobbyManager.getPlayerData(playerId);
    if (data != null && data.getSelectedTeam() != null) {
      HuntTeam team = data.getSelectedTeam();
      Object classSelection =
          (team == HuntTeam.HUNTERS) ? data.getSelectedHunterClass() : data.getSelectedHiderClass();

      if (classSelection != null) {
        // Player now has team, class, and map vote - add to participants
        gameParticipants.put(playerId, team);
        gameClassSelections.put(playerId, classSelection);
        plugin
            .getLogger()
            .info("Added " + player.getName() + " to gameParticipants after map vote");
      }
    }

    // Update holograms to reflect new vote status
    updateReadyStatusHologram();
  }

  /**
   * Handles a player marking themselves as ready or not ready.
   *
   * @param player The player
   * @param ready Whether they are ready
   */
  public void setPlayerReady(Player player, boolean ready) {
    if (!prepPhaseActive || gameStarting) {
      return;
    }

    UUID playerId = player.getUniqueId();
    HuntPlayerData data = lobbyManager.getPlayerData(playerId);

    // Get the current gamemode strategy
    HuntGameMode currentMode = gameModeManager.getCurrentGameMode();
    HuntGameModeStrategy strategy = HuntGameModeStrategyFactory.getStrategy(currentMode);

    // Check if player can ready up using the strategy
    HuntGameModeStrategy.ReadyResult readyResult =
        strategy.canPlayerReady(player, data, playerMapVotes, hologramManager);

    if (!readyResult.canReady()) {
      player.sendMessage(Component.text(readyResult.getErrorMessage(), NamedTextColor.RED));
      return;
    }

    playerReadyStatus.put(playerId, ready);

    if (ready) {
      if (currentMode == HuntGameMode.IMPOSTER_HUNT) {
        // For Imposter Hunt: Just add player to participants without team/class requirements
        if (!gameParticipants.containsKey(playerId)) {
          // Set a default team or null for Imposter Hunt (teams are assigned by
          // ImposterGameManager)
          gameParticipants.put(playerId, null);

          // Debug logging
          Bukkit.getLogger()
              .info("Hunt added player to gameParticipants (Imposter Hunt): " + player.getName());
        }
      } else {
        // For other modes: Original logic with team/class requirements
        // Store player's selections for the game (they should already be in
        // gameParticipants from class selection)
        if (!gameParticipants.containsKey(playerId)) {
          gameParticipants.put(playerId, data.getSelectedTeam());
          if (data.getSelectedTeam() == HuntTeam.HUNTERS) {
            gameClassSelections.put(playerId, data.getSelectedHunterClass());
          } else {
            gameClassSelections.put(playerId, data.getSelectedHiderClass());
          }

          // Debug logging
          Bukkit.getLogger()
              .info(
                  "Hunt added player to gameParticipants: "
                      + player.getName()
                      + " - Team: "
                      + data.getSelectedTeam()
                      + ", Class: "
                      + (data.getSelectedTeam() == HuntTeam.HUNTERS
                          ? data.getSelectedHunterClass()
                          : data.getSelectedHiderClass()));
        }
      }
    }
    // Note: We don't remove from gameParticipants when unreadying - they're still
    // in the game

    player.sendMessage(
        Component.text(
            ready ? "You are now ready!" : "You are no longer ready",
            ready ? NamedTextColor.GREEN : NamedTextColor.YELLOW));

    // Update ready status hologram
    updateReadyStatusHologram();

    // Check if all players are ready
    checkAllPlayersReady();
  }

  /** Attempts to start the game if conditions are met. */
  public void attemptGameStart() {
    if (!prepPhaseActive || gameStarting) {
      return;
    }

    // Debug logging for game start attempt
    Bukkit.getLogger()
        .info(
            "Hunt game start attempt - gameParticipants size: "
                + gameParticipants.size()
                + ", playerReadyStatus size: "
                + playerReadyStatus.size()
                + ", gameClassSelections size: "
                + gameClassSelections.size());

    // Create copy to avoid ConcurrentModificationException
    Set<UUID> participantsCopy = new HashSet<>(gameParticipants.keySet());
    for (UUID playerId : participantsCopy) {
      Player player = Bukkit.getPlayer(playerId);
      String playerName = (player != null) ? player.getName() : "Unknown";
      boolean isReady = Boolean.TRUE.equals(playerReadyStatus.get(playerId));
      boolean hasClass = gameClassSelections.containsKey(playerId);
      Bukkit.getLogger()
          .info(
              "Hunt participant: "
                  + playerName
                  + " - Ready: "
                  + isReady
                  + ", Has class: "
                  + hasClass
                  + ", Team: "
                  + gameParticipants.get(playerId));
    }

    if (!canStartGame()) {
      // Send specific error messages
      if (gameParticipants.isEmpty()) {
        broadcastMessage("Cannot start game: No players ready!", NamedTextColor.RED);
      } else {
        boolean hasHunter =
            gameParticipants.values().stream().anyMatch(team -> team == HuntTeam.HUNTERS);
        if (!hasHunter) {
          broadcastMessage("Cannot start game: Need at least one hunter!", NamedTextColor.RED);
        } else {
          broadcastMessage("Cannot start game: Not all players are ready!", NamedTextColor.RED);
        }
      }
      return;
    }

    // Start countdown
    startGameCountdown();
  }

  /** Starts the 5-second countdown to game start. */
  private void startGameCountdown() {
    gameStarting = true;

    countdownTask =
        new BukkitRunnable() {
          int countdown = config.getInt("hunt.prep-phase.countdown-duration", 5);

          @Override
          public void run() {
            if (countdown > 0) {
              // Show countdown title to all participants with team-specific subtitle
              for (UUID playerId : new HashSet<>(gameParticipants.keySet())) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                  HuntTeam team = gameParticipants.get(playerId);
                  String subtitle =
                      team == HuntTeam.HUNTERS
                          ? "Get ready to hunt..."
                          : "Find your hiding spot...";
                  Title countdownTitle =
                      Title.title(
                          Component.text(countdown, NamedTextColor.RED),
                          Component.text(subtitle, NamedTextColor.YELLOW));
                  player.showTitle(countdownTitle);
                }
              }

              countdown--;
            } else {
              // Start the game
              startGame();
              cancel();
            }
          }
        }.runTaskTimer(plugin, 0L, 20L); // Run every second
  }

  /** Starts the actual game after countdown. */
  private void startGame() {
    // Reset per-game tracking state from previous round
    if (deathHandler != null) {
      deathHandler.resetForNewGame();
    }

    // Get the current game mode
    HuntGameMode currentMode = gameModeManager.getCurrentGameMode();

    // Populate game participants based on gamemode
    if (currentMode == HuntGameMode.IMPOSTER_HUNT) {
      // For Imposter Hunt: All players who voted for maps are participants
      gameParticipants.clear();
      gameClassSelections.clear();

      for (UUID playerId : playerMapVotes.keySet()) {
        // All Imposter Hunt players are considered "neutral" - roles assigned later
        gameParticipants.put(playerId, HuntTeam.HIDERS); // Use HIDERS as placeholder
        gameClassSelections.put(playerId, "imposter_participant"); // Placeholder class
      }

      plugin
          .getLogger()
          .info("Imposter Hunt: " + gameParticipants.size() + " participants from map votes");
    }
    // For other gamemodes, gameParticipants is already populated from the ready system

    // Select map (random if no clear winner)
    selectedMap = selectWinningMap();

    // Show map selection title with gamemode info
    Title mapTitle =
        Title.title(
            Component.text("MAP: " + selectedMap.getDisplayName(), NamedTextColor.GOLD),
            Component.text(
                "Mode: " + currentMode.getDisplayName() + " | Good luck!", NamedTextColor.YELLOW));

    for (UUID playerId : new HashSet<>(gameParticipants.keySet())) {
      Player player = Bukkit.getPlayer(playerId);
      if (player != null) {
        player.showTitle(mapTitle);
      }
    }

    // Teleport players to the selected map
    Bukkit.getScheduler()
        .runTaskLater(plugin, this::teleportPlayersToMap, 40L); // 2 seconds after title

    // End prep phase
    endPrepPhase();

    plugin
        .getLogger()
        .info(
            "Hunt game started on map: "
                + selectedMap.getDisplayName()
                + " with gamemode: "
                + currentMode.getDisplayName());

    // Handle different game modes
    switch (currentMode) {
      case PROP_HUNT:
        // Default Prop Hunt behavior - no additional setup needed
        break;
      case IMPOSTER_HUNT:
        // Initialize Imposter Hunt - start the game with all participants
        startImposterHunt();
        break;
      case NEXTBOT_HUNT:
        // TODO: Initialize NextBot Hunt specific logic
        plugin
            .getLogger()
            .info("Starting NextBot Hunt mode - additional setup will be implemented");
        break;
      default:
        plugin.getLogger().warning("Unknown game mode: " + currentMode);
        break;
    }
  }

  /** Starts the Imposter Hunt gamemode with all participants. */
  private void startImposterHunt() {
    List<Player> participants = new ArrayList<>();

    for (UUID playerId : gameParticipants.keySet()) {
      Player player = Bukkit.getPlayer(playerId);
      if (player != null && player.isOnline()) {
        participants.add(player);
      }
    }

    if (participants.size() < 3) {
      plugin
          .getLogger()
          .warning(
              "Not enough players for Imposter Hunt (need at least 3, have "
                  + participants.size()
                  + ")");
      return;
    }

    // Start the Imposter Hunt using ImposterGameManager
    plugin.getLogger().info("Starting Imposter Hunt with " + participants.size() + " participants");
    imposterGameManager.startGame(participants);
  }

  /**
   * Selects the winning map based on votes, with random selection for ties.
   *
   * @return The selected map
   */
  private HuntMap selectWinningMap() {
    // Find the map with the most votes
    HuntMap winningMap = null;
    int maxVotes = -1;

    for (Map.Entry<HuntMap, Integer> entry : mapVoteCounts.entrySet()) {
      if (entry.getValue() > maxVotes) {
        maxVotes = entry.getValue();
        winningMap = entry.getKey();
      }
    }

    // If no votes or tie, select randomly
    if (winningMap == null || maxVotes == 0) {
      HuntMap[] maps = HuntMap.values();
      winningMap = maps[new Random().nextInt(maps.length)];
    }

    return winningMap;
  }

  /** Teleports all players to their appropriate spawn locations on the selected map. */
  private void teleportPlayersToMap() {
    ConfigurationSection mapConfig =
        config.getConfigurationSection("hunt.maps." + selectedMap.name().toLowerCase());
    if (mapConfig == null) {
      plugin.getLogger().warning("No configuration found for map: " + selectedMap.name());
      return;
    }

    String worldName = mapConfig.getString("world", "hunt");
    World world = Bukkit.getWorld(worldName);
    if (world == null) {
      plugin.getLogger().warning("World not found: " + worldName);
      return;
    }

    // Create copies of participant lists to avoid ConcurrentModificationException
    Map<UUID, HuntTeam> participantsCopy = new HashMap<>(gameParticipants);

    int teleportedCount = 0;
    int hunterCount = 0;
    int hiderCount = 0;

    // Teleport hunters to hunter spawn
    ConfigurationSection hunterSpawnConfig = mapConfig.getConfigurationSection("hunter-spawn");
    if (hunterSpawnConfig != null) {
      Location hunterSpawn =
          new Location(
              world,
              hunterSpawnConfig.getDouble("x"),
              hunterSpawnConfig.getDouble("y"),
              hunterSpawnConfig.getDouble("z"),
              (float) hunterSpawnConfig.getDouble("yaw"),
              (float) hunterSpawnConfig.getDouble("pitch"));

      // Give the death handler a known-safe location on this map for void-death teleports
      if (deathHandler != null) {
        deathHandler.setSafeRespawnLocation(hunterSpawn);
      }

      for (Map.Entry<UUID, HuntTeam> entry : participantsCopy.entrySet()) {
        if (entry.getValue() == HuntTeam.HUNTERS) {
          Player player = Bukkit.getPlayer(entry.getKey());
          if (player != null && player.isOnline()) {
            // Teleport hunter without removing kit or disguise
            player.teleport(hunterSpawn);
            // Re-teleport a couple ticks later to override any world manager (e.g. Multiverse)
            // restoring the player's last position in this world - only observable on repeat
            // plays of the same map, where the player has a prior position on record.
            Bukkit.getScheduler()
                .runTaskLater(
                    plugin,
                    () -> {
                      if (player.isOnline()) {
                        player.teleport(hunterSpawn);
                      }
                    },
                    2L);
            teleportedCount++;
            hunterCount++;

            player.sendMessage(
                Component.text("You have been locked in the hunter area!", NamedTextColor.RED));

            plugin.getLogger().info("Teleported hunter " + player.getName() + " to hunter spawn");
          } else {
            plugin.getLogger().warning("Hunter player is null or offline: " + entry.getKey());
          }
        }
      }
    } else {
      plugin
          .getLogger()
          .warning("No hunter spawn configuration found for map: " + selectedMap.name());
    }

    // Teleport hiders to random hider spawns
    List<Map<?, ?>> hiderSpawns = mapConfig.getMapList("hider-spawns");
    if (!hiderSpawns.isEmpty()) {
      Random random = new Random();

      for (Map.Entry<UUID, HuntTeam> entry : participantsCopy.entrySet()) {
        if (entry.getValue() == HuntTeam.HIDERS) {
          Player player = Bukkit.getPlayer(entry.getKey());
          if (player != null && player.isOnline()) {
            // Select random spawn point
            Map<?, ?> spawnData = hiderSpawns.get(random.nextInt(hiderSpawns.size()));
            Location hiderSpawn =
                new Location(
                    world,
                    ((Number) spawnData.get("x")).doubleValue(),
                    ((Number) spawnData.get("y")).doubleValue(),
                    ((Number) spawnData.get("z")).doubleValue(),
                    ((Number) spawnData.get("yaw")).floatValue(),
                    ((Number) spawnData.get("pitch")).floatValue());

            // Teleport hider without removing kit
            player.teleport(hiderSpawn);
            // Re-teleport a couple ticks later to override any world manager (e.g. Multiverse)
            // restoring the player's last position in this world - only observable on repeat
            // plays of the same map, where the player has a prior position on record.
            Bukkit.getScheduler()
                .runTaskLater(
                    plugin,
                    () -> {
                      if (player.isOnline()) {
                        player.teleport(hiderSpawn);
                      }
                    },
                    2L);
            teleportedCount++;
            hiderCount++;

            player.sendMessage(Component.text("Find a hiding spot quickly!", NamedTextColor.BLUE));

            plugin.getLogger().info("Teleported hider " + player.getName() + " to hider spawn");
          } else {
            plugin.getLogger().warning("Hider player is null or offline: " + entry.getKey());
          }
        }
      }
    } else {
      plugin
          .getLogger()
          .warning("No hider spawn configuration found for map: " + selectedMap.name());
    }

    plugin
        .getLogger()
        .info(
            "Teleported "
                + teleportedCount
                + " players ("
                + hunterCount
                + " hunters, "
                + hiderCount
                + " hiders) to map: "
                + selectedMap.name());

    // After teleporting all players, re-apply kits, disguises, and
    // passives/abilities - add a short delay to ensure teleport completes first
    Bukkit.getScheduler()
        .runTaskLater(
            plugin,
            () -> {
              // Use the pre-teleport snapshot so players removed from gameParticipants
              // by world-change cleanup are still given their kits/disguises.
              Map<UUID, HuntTeam> participantsCopyInner = participantsCopy;

              plugin
                  .getLogger()
                  .info("Re-applying kits, abilities, and disguises after teleport...");

              for (Map.Entry<UUID, HuntTeam> entry : participantsCopyInner.entrySet()) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                  HuntPlayerData data = lobbyManager.getPlayerData(entry.getKey());
                  if (data == null) {
                    plugin
                        .getLogger()
                        .warning(
                            "[DEBUG] No player data found for "
                                + player.getName()
                                + " during teleport");
                    continue;
                  }

                  // Log current world for debugging
                  plugin
                      .getLogger()
                      .info(
                          "[DEBUG] Player "
                              + player.getName()
                              + " is in world: "
                              + player.getWorld().getName());

                  // Heal all players to full health after teleport
                  player.setHealth(20.0); // Standard max health for players
                  player.setFoodLevel(20);
                  player.setSaturation(20.0f);

                  if (entry.getValue() == HuntTeam.HUNTERS) {
                    // Re-apply hunter kit, abilities, and disguise
                    if (data.getSelectedHunterClass() != null) {
                      // First apply the kit (only if not spectator)
                      if (player.getGameMode() != GameMode.SPECTATOR) {
                        kitManager.giveHunterKit(player, data.getSelectedHunterClass());

                        // Then apply abilities (these are important for gameplay)
                        kitManager.applyHunterAbilities(player, data.getSelectedHunterClass());

                        plugin
                            .getLogger()
                            .info(
                                "[DEBUG] Applied hunter abilities for "
                                    + player.getName()
                                    + " as "
                                    + data.getSelectedHunterClass().name());
                      } else {
                        plugin
                            .getLogger()
                            .info(
                                "[DEBUG] Skipped applying hunter abilities to spectator: "
                                    + player.getName());
                      }
                    }

                    // Re-apply disguise
                    if (disguiseManager != null) {
                      disguiseManager.reapplyDisguise(player);
                    }
                  } else if (entry.getValue() == HuntTeam.HIDERS) {
                    // Re-apply hider kit and passives
                    if (data.getSelectedHiderClass() != null) {
                      // First apply the kit (only if not spectator)
                      if (player.getGameMode() != GameMode.SPECTATOR) {
                        kitManager.giveHiderKit(player, data.getSelectedHiderClass());

                        // Then apply passives (these are important for gameplay)
                        kitManager.applyHiderPassives(player, data.getSelectedHiderClass());
                        plugin
                            .getLogger()
                            .info(
                                "Applied hider passives for "
                                    + player.getName()
                                    + " as "
                                    + data.getSelectedHiderClass().name());
                      } else {
                        plugin
                            .getLogger()
                            .info(
                                "Skipped applying hider passives to spectator: "
                                    + player.getName());
                      }
                    }
                  }
                }
              }

              // Initialize hider count in death handler AFTER players are teleported and
              // setup
              if (deathHandler != null) {
                deathHandler.initializeAliveHidersCount();
                plugin.getLogger().info("Initialized alive hiders count after teleport and setup");
              }

              startHunterLockIn();
            },
            10L); // 0.5 second delay after teleport

    // Start game timer (after lock-in period)
    int lockDuration = config.getInt("hunt.prep-phase.hunter-lock-duration", 30);
    Bukkit.getScheduler().runTaskLater(plugin, this::startGameTimer, (lockDuration + 2) * 20L);
  }

  /** Starts the hunter lock-in period where hunters cannot move and are blinded. */
  private void startHunterLockIn() {
    int lockDuration = config.getInt("hunt.prep-phase.hunter-lock-duration", 30);

    // Create copies of participant lists to avoid ConcurrentModificationException
    Map<UUID, HuntTeam> participantsCopy = new HashMap<>(gameParticipants);

    // Create boss bar for lock-in countdown
    BossBar lockInBar =
        Bukkit.createBossBar(
            "Hunters locked in: " + lockDuration + " seconds", BarColor.YELLOW, BarStyle.SOLID);

    // Add all game participants to boss bar
    for (UUID playerId : participantsCopy.keySet()) {
      Player player = Bukkit.getPlayer(playerId);
      if (player != null && player.isOnline()) {
        lockInBar.addPlayer(player);
      }
    }
    lockInBar.setVisible(true);

    // Apply blindness and slowness to hunters (movement disabled)
    for (Map.Entry<UUID, HuntTeam> entry : participantsCopy.entrySet()) {
      if (entry.getValue() == HuntTeam.HUNTERS) {
        Player hunter = Bukkit.getPlayer(entry.getKey());
        if (hunter != null && hunter.isOnline()) {
          // Clear any existing potion effects first to prevent conflicts
          for (PotionEffectType effectType :
              new PotionEffectType[] {
                PotionEffectType.BLINDNESS,
                PotionEffectType.SLOWNESS,
                PotionEffectType.JUMP_BOOST,
                PotionEffectType.WEAKNESS,
                PotionEffectType.DARKNESS,
                PotionEffectType.MINING_FATIGUE
              }) {
            hunter.removePotionEffect(effectType);
          }

          // Apply extreme effects to fully disable movement
          hunter.addPotionEffect(
              new PotionEffect(
                  PotionEffectType.BLINDNESS, lockDuration * 20, 1, false, false, true));
          hunter.addPotionEffect(
              new PotionEffect(
                  PotionEffectType.DARKNESS, lockDuration * 20, 1, false, false, true));
          hunter.addPotionEffect(
              new PotionEffect(
                  PotionEffectType.SLOWNESS,
                  lockDuration * 20,
                  255,
                  false,
                  false,
                  true)); // Max slowness (level 255)
          hunter.addPotionEffect(
              new PotionEffect(
                  PotionEffectType.WEAKNESS,
                  lockDuration * 20,
                  100,
                  false,
                  false,
                  true)); // Make them very weak
          hunter.addPotionEffect(
              new PotionEffect(
                  PotionEffectType.MINING_FATIGUE,
                  lockDuration * 20,
                  100,
                  false,
                  false,
                  true)); // Prevent breaking blocks

          // Send title to hunter indicating they're locked
          Title lockedTitle =
              Title.title(
                  Component.text("LOCKED", NamedTextColor.RED),
                  Component.text("Wait for hiders to hide", NamedTextColor.YELLOW));
          hunter.showTitle(lockedTitle);

          plugin
              .getLogger()
              .info("Applied blindness and movement restrictions to hunter: " + hunter.getName());
        }
      }
    }

    // Send HIDE! title to hiders when hunters are locked
    Title hideTitle =
        Title.title(
            Component.text("HIDE!", NamedTextColor.AQUA),
            Component.text("Hunters are locked in!", NamedTextColor.YELLOW));
    for (Map.Entry<UUID, HuntTeam> entry : participantsCopy.entrySet()) {
      if (entry.getValue() == HuntTeam.HIDERS) {
        Player hider = Bukkit.getPlayer(entry.getKey());
        if (hider != null && hider.isOnline()) {
          hider.showTitle(hideTitle);
        }
      }
    }

    // Start countdown timer
    BukkitRunnable lockInCountdown =
        new BukkitRunnable() {
          int timeLeft = lockDuration;

          @Override
          public void run() {
            if (timeLeft <= 0) {
              // Release hunters
              releaseHunters();

              // Remove lock-in boss bar
              lockInBar.removeAll();

              cancel();
              return;
            }

            // Update boss bar
            lockInBar.setTitle("Hunters locked in: " + timeLeft + " seconds");
            lockInBar.setProgress((double) timeLeft / lockDuration);

            // Change color based on time remaining
            if (timeLeft <= 10) {
              lockInBar.setColor(BarColor.RED);
            } else if (timeLeft <= 20) {
              lockInBar.setColor(BarColor.YELLOW);
            }

            timeLeft--;
          }
        };

    lockInCountdown.runTaskTimer(plugin, 0L, 20L); // Run every second
  }

  /** Releases hunters from lock-in by removing effects and announcing. */
  private void releaseHunters() {
    // Create copies of participant lists to avoid ConcurrentModificationException
    Map<UUID, HuntTeam> participantsCopy = new HashMap<>(gameParticipants);

    // Remove effects from hunters
    for (Map.Entry<UUID, HuntTeam> entry : participantsCopy.entrySet()) {
      if (entry.getValue() == HuntTeam.HUNTERS) {
        Player hunter = Bukkit.getPlayer(entry.getKey());
        if (hunter != null && hunter.isOnline()) {
          // Remove ALL movement restriction effects
          for (PotionEffectType effectType :
              new PotionEffectType[] {
                PotionEffectType.BLINDNESS,
                PotionEffectType.SLOWNESS,
                PotionEffectType.JUMP_BOOST,
                PotionEffectType.WEAKNESS,
                PotionEffectType.DARKNESS,
                PotionEffectType.MINING_FATIGUE
              }) {
            hunter.removePotionEffect(effectType);
          }

          // Re-apply hunter abilities to ensure they're active after restrictions are
          // removed
          HuntPlayerData data = lobbyManager.getPlayerData(hunter.getUniqueId());
          if (data != null && data.getSelectedHunterClass() != null) {
            // Only apply abilities if still not in spectator mode
            if (hunter.getGameMode() != GameMode.SPECTATOR) {
              kitManager.applyHunterAbilities(hunter, data.getSelectedHunterClass());
              plugin
                  .getLogger()
                  .info("Re-applied abilities for " + hunter.getName() + " after release");
            } else {
              plugin
                  .getLogger()
                  .info("Skipped re-applying abilities to spectator hunter: " + hunter.getName());
            }
          }

          // Show dramatic "HUNT!" title
          Title huntTitle =
              Title.title(
                  Component.text("HUNT!", NamedTextColor.DARK_RED),
                  Component.text("Find the hiders!", NamedTextColor.YELLOW));
          hunter.showTitle(huntTitle);

          hunter.sendMessage(Component.text("You are now free to hunt!", NamedTextColor.GREEN));
          plugin.getLogger().info("Released hunter from lock-in: " + hunter.getName());
        }
      }
    }

    // Warn hiders that hunters are now loose, and give Cloakers their starting invisibility
    Title releasedTitle =
        Title.title(
            Component.text("HUNTERS RELEASED!", NamedTextColor.RED),
            Component.text("Stay hidden!", NamedTextColor.YELLOW));
    for (Map.Entry<UUID, HuntTeam> entry : gameParticipants.entrySet()) {
      if (entry.getValue() == HuntTeam.HIDERS) {
        Player hider = Bukkit.getPlayer(entry.getKey());
        if (hider != null && hider.isOnline()) {
          hider.showTitle(releasedTitle);

          // Give Cloakers 10 seconds of starting invisibility from the moment hunters are released
          HuntPlayerData hiderData = lobbyManager.getPlayerData(entry.getKey());
          if (hiderData != null && hiderData.getSelectedHiderClass() == HiderClass.CLOAKER) {
            hider.addPotionEffect(
                new PotionEffect(PotionEffectType.INVISIBILITY, 20 * 10, 0, false, false));
          }
        }
      }
    }

    broadcastMessage("Hunters are now released! The hunt begins!", NamedTextColor.RED);
  }

  /** Starts the main game timer displayed on the action bar. */
  private void startGameTimer() {
    // Mark that actual gameplay has started
    gameStarting = false;
    gameActive = true;

    int gameDuration = config.getInt("hunt.prep-phase.game-duration", 5); // minutes

    plugin
        .getLogger()
        .info("Starting Hunt game timer for " + gameDuration + " minutes - Game is now active!");

    // Create copies of participant lists to avoid ConcurrentModificationException
    Map<UUID, HuntTeam> participantsCopy = new HashMap<>(gameParticipants);

    // Create boss bar for game timer
    gameTimerBar =
        Bukkit.createBossBar(
            "Game Time Remaining: " + gameDuration + ":00", BarColor.GREEN, BarStyle.SOLID);

    // Add all game participants to boss bar
    for (UUID playerId : participantsCopy.keySet()) {
      Player player = Bukkit.getPlayer(playerId);
      if (player != null) {
        gameTimerBar.addPlayer(player);
      }
    }

    gameTimerBar.setVisible(true);

    // Start countdown timer
    gameTimer =
        new BukkitRunnable() {
          int timeLeft = gameDuration * 60; // Convert to seconds

          @Override
          public void run() {
            if (timeLeft <= 0) {
              // Game over - hiders win
              endGame(HuntTeam.HIDERS);
              cancel();
              return;
            }

            // Update boss bar
            int minutes = timeLeft / 60;
            int seconds = timeLeft % 60;
            String timeDisplay = String.format("%d:%02d", minutes, seconds);

            // Count remaining hiders
            long hidersAlive =
                gameParticipants.entrySet().stream()
                    .filter(entry -> entry.getValue() == HuntTeam.HIDERS)
                    .map(entry -> Bukkit.getPlayer(entry.getKey()))
                    .filter(player -> player != null && player.isOnline())
                    .count();

            gameTimerBar.setTitle("Time: " + timeDisplay + " | Hiders: " + hidersAlive);
            gameTimerBar.setProgress((double) timeLeft / (gameDuration * 60));

            // Change color based on time remaining
            if (timeLeft <= 60) {
              gameTimerBar.setColor(BarColor.RED);
            } else if (timeLeft <= 120) {
              gameTimerBar.setColor(BarColor.YELLOW);
            }

            timeLeft--;
          }
        }.runTaskTimer(plugin, 0L, 20L);

    // Start heartbeat task for hiders when hunters are nearby
    plugin.getLogger().info("Starting heartbeat task for hunt game");
    startHeartbeatTask();
  }

  /**
   * Forces the game to end immediately, bypassing the gameActive guard. Used by admin commands.
   *
   * @param sender The command sender for feedback
   */
  public void forceEndGame(org.bukkit.command.CommandSender sender) {
    if (gameEnded && !gameActive && !prepPhaseActive && !gameStarting) {
      sender.sendMessage(
          net.kyori.adventure.text.Component.text(
              "No active hunt game to end.",
              net.kyori.adventure.text.format.NamedTextColor.YELLOW));
      return;
    }
    if (prepPhaseActive && !gameStarting && !gameActive) {
      // Still purely in prep phase - players never left the lobby, so there's no round to end
      // and no one to retrieve from a map. Running the full endGame()/teleport sequence here
      // previously auto-restarted a brand new prep phase immediately afterward (see
      // specs/force-end-during-prep-phase.md) - an admin's "abort" looked like the game just
      // kept going.
      endPrepPhase();
    } else {
      // Mid lock-in/countdown or an active round - players are actually on a map and need to
      // be retrieved, so run the full sequence as normal.
      endPrepPhase();
      gameActive = true; // allow endGame to proceed
      endGame(HuntTeam.HUNTERS); // neutral winner for forced end
    }
    sender.sendMessage(
        net.kyori.adventure.text.Component.text(
            "Hunt round force-ended.", net.kyori.adventure.text.format.NamedTextColor.GREEN));
    plugin.getLogger().info("Hunt round force-ended by " + sender.getName());
  }

  /**
   * Ends the current Hunt game and announces the winning team.
   *
   * @param winningTeam The team that won the game
   */
  public void endGame(HuntTeam winningTeam) {
    // Prevent duplicate execution
    if (gameEnded) {
      return;
    }

    // Prevent ending game during loading/lock-in phase
    if (!gameActive) {
      plugin
          .getLogger()
          .warning(
              "Attempted to end game during loading phase - ignoring endGame call for "
                  + (winningTeam != null ? winningTeam.getDisplayName() : "null"));
      return;
    }

    gameEnded = true;
    gameActive = false;
    gameStarting = false;

    // Cancel game timer
    if (gameTimer != null) {
      gameTimer.cancel();
      gameTimer = null;
    }

    // Cancel heartbeat task
    if (heartbeatTask != null) {
      heartbeatTask.cancel();
      heartbeatTask = null;
    }

    // Cancel all individual hider heartbeat tasks
    for (BukkitRunnable task : hiderHeartbeatTasks.values()) {
      if (task != null) {
        task.cancel();
      }
    }
    hiderHeartbeatTasks.clear();
    currentHeartbeatMessages.clear();
    clearAllHurtTensionState();
    if (spotlightListener != null) {
      spotlightListener.clearAllState();
    }

    // Remove boss bar
    if (gameTimerBar != null) {
      gameTimerBar.removeAll();
      gameTimerBar = null;
    }

    // Announce winner
    Title winTitle =
        Title.title(
            Component.text(winningTeam.getDisplayName() + " WIN!", NamedTextColor.GOLD),
            Component.text("Game Over", NamedTextColor.YELLOW));

    for (UUID playerId : gameParticipants.keySet()) {
      Player player = Bukkit.getPlayer(playerId);
      if (player != null) {
        player.showTitle(winTitle);
      }
    }

    broadcastMessage(
        winningTeam.getDisplayName() + " have won the Hunt game!", NamedTextColor.GOLD);

    // Spawn fireworks for the winning team
    spawnWinningTeamFireworks(winningTeam);

    // Remove players from all holograms (map, class, prep phase)
    removePlayersFromAllHolograms();

    // Clear game state
    gameParticipants.clear();
    gameClassSelections.clear();
    allPlayerSelections.clear();

    plugin.getLogger().info("Hunt game ended. Winner: " + winningTeam.getDisplayName());
  }

  /**
   * Spawns fireworks for the winning team for 3 seconds.
   *
   * @param winningTeam The team that won the game
   */
  private void spawnWinningTeamFireworks(HuntTeam winningTeam) {
    // Get alive players on the winning team
    List<Player> winningPlayers = new ArrayList<>();

    for (UUID playerId : gameParticipants.keySet()) {
      Player player = Bukkit.getPlayer(playerId);
      if (player != null && player.isOnline() && player.getGameMode() != GameMode.SPECTATOR) {
        HuntPlayerData playerData = lobbyManager.getPlayerData(playerId);
        if (playerData != null && playerData.getSelectedTeam() == winningTeam) {
          winningPlayers.add(player);
        }
      }
    }

    // Use the death handler's fireworks method if available
    if (deathHandler != null) {
      deathHandler.spawnVictoryFireworks(winningPlayers);
      plugin
          .getLogger()
          .info(
              "Spawning victory fireworks for "
                  + winningTeam.getDisplayName()
                  + " ("
                  + winningPlayers.size()
                  + " players)");
    } else {
      plugin.getLogger().warning("Death handler not available for spawning victory fireworks");
      // Fallback - teleport players to lobby after a short delay
      Bukkit.getScheduler().runTaskLater(plugin, this::teleportAllPlayersToLobby, 60L); // 3 seconds
    }
  }

  // Hologram management methods
  private void initializeStartGameHologram() {
    hologramManager.updateStartGameDisplay(false);
  }

  private void updateStartGameHologram() {
    hologramManager.updateStartGameDisplay(canStartGame());
  }

  /** True once this player meets the current gamemode's requirements (team, class, map vote). */
  private boolean isPlayerReadyToStart(UUID playerId, HuntGameModeStrategy strategy) {
    Player player = Bukkit.getPlayer(playerId);
    if (player == null) {
      return false;
    }
    HuntPlayerData data = lobbyManager.getPlayerData(playerId);
    return strategy.canPlayerReady(player, data, playerMapVotes, hologramManager).canReady();
  }

  private boolean canStartGame() {
    HuntGameMode currentMode = gameModeManager.getCurrentGameMode();
    HuntGameModeStrategy strategy = HuntGameModeStrategyFactory.getStrategy(currentMode);

    // A player is ready the moment they meet the gamemode's requirements (team, class, map
    // vote) - no separate explicit ready toggle needed.
    List<UUID> readyPlayers = new ArrayList<>();
    for (Player player : Bukkit.getOnlinePlayers()) {
      UUID playerId = player.getUniqueId();
      HuntPlayerData data = lobbyManager.getPlayerData(playerId);

      HuntGameModeStrategy.ReadyResult readyResult =
          strategy.canPlayerReady(player, data, playerMapVotes, hologramManager);

      if (readyResult.canReady()) {
        readyPlayers.add(playerId);
      }
    }

    // Use strategy to determine if game can start
    return strategy.canStartGame(readyPlayers, playerMapVotes, hologramManager)
        && readyPlayers.size() >= strategy.getMinimumPlayers();
  }

  private void initializeReadyStatusHologram() {
    updateReadyStatusHologram();
  }

  private void updateReadyStatusHologram() {
    HuntGameMode currentMode = gameModeManager.getCurrentGameMode();
    HuntGameModeStrategy strategy = HuntGameModeStrategyFactory.getStrategy(currentMode);

    // CHECKSTYLE:OFF: AvoidEscapedUnicodeCharacters
    Component content =
        Component.text("READY STATUS CHECKLIST", NamedTextColor.AQUA, TextDecoration.BOLD);

    if (currentMode == HuntGameMode.IMPOSTER_HUNT) {
      int playersWithMapVotes = playerMapVotes.size();
      int minimumPlayers = strategy.getMinimumPlayers();
      boolean enoughVotes = playersWithMapVotes >= minimumPlayers;

      content =
          content
              .append(Component.newline())
              .append(
                  Component.text(
                      enoughVotes ? "\u2713 " : "\u2717 ",
                      enoughVotes ? NamedTextColor.GREEN : NamedTextColor.RED))
              .append(Component.text("Players voted for maps: ", NamedTextColor.GRAY))
              .append(
                  Component.text(
                      playersWithMapVotes + " (min " + minimumPlayers + ")", NamedTextColor.WHITE))
              .append(Component.newline())
              .append(Component.text("Mode: ", NamedTextColor.GOLD))
              .append(Component.text(currentMode.getDisplayName(), NamedTextColor.WHITE))
              .append(Component.newline())
              .append(Component.text("Vote for a map to join!", NamedTextColor.GRAY));
    } else {
      Set<UUID> hunterIds =
          allPlayerSelections.entrySet().stream()
              .filter(entry -> entry.getValue() == HuntTeam.HUNTERS)
              .map(Map.Entry::getKey)
              .collect(java.util.stream.Collectors.toSet());
      Set<UUID> disguisedPlayers = disguiseManager.getDisguisedPlayers();
      boolean allHuntersHaveDisguises =
          !hunterIds.isEmpty() && disguisedPlayers.containsAll(hunterIds);

      plugin
          .getLogger()
          .info(
              "Hunter check: hunterIds="
                  + hunterIds.size()
                  + ", disguisedPlayers="
                  + disguisedPlayers.size()
                  + ", allHuntersHaveDisguises="
                  + allHuntersHaveDisguises);

      boolean hasHider =
          allPlayerSelections.values().stream().anyMatch(team -> team == HuntTeam.HIDERS);
      long playersWithClassSelections = allPlayerSelections.size();
      long playersWithMapVotes = playerMapVotes.size();
      boolean allPlayersVoted =
          playersWithClassSelections > 0 && playersWithMapVotes >= playersWithClassSelections;

      content =
          content
              .append(Component.newline())
              .append(
                  Component.text(
                      allHuntersHaveDisguises ? "\u2713 " : "\u2717 ",
                      allHuntersHaveDisguises ? NamedTextColor.GREEN : NamedTextColor.RED))
              .append(Component.text("All Hunters have disguises", NamedTextColor.GRAY))
              .append(Component.newline())
              .append(
                  Component.text(
                      hasHider ? "\u2713 " : "\u2717 ",
                      hasHider ? NamedTextColor.GREEN : NamedTextColor.RED))
              .append(Component.text("At least one Hider", NamedTextColor.GRAY))
              .append(Component.newline())
              .append(
                  Component.text(
                      allPlayersVoted ? "\u2713 " : "\u2717 ",
                      allPlayersVoted ? NamedTextColor.GREEN : NamedTextColor.RED))
              .append(Component.text("All players voted for maps (", NamedTextColor.GRAY))
              .append(
                  Component.text(
                      playersWithMapVotes + "/" + playersWithClassSelections, NamedTextColor.WHITE))
              .append(Component.text(")", NamedTextColor.GRAY));

      // A player is ready the moment they meet the requirements (team, class, map vote) - no
      // separate explicit ready toggle needed.
      long readyHunters =
          gameParticipants.entrySet().stream()
              .filter(entry -> entry.getValue() == HuntTeam.HUNTERS)
              .filter(entry -> isPlayerReadyToStart(entry.getKey(), strategy))
              .count();
      long totalHunters =
          gameParticipants.values().stream().filter(team -> team == HuntTeam.HUNTERS).count();
      long readyHiders =
          gameParticipants.entrySet().stream()
              .filter(entry -> entry.getValue() == HuntTeam.HIDERS)
              .filter(entry -> isPlayerReadyToStart(entry.getKey(), strategy))
              .count();
      long totalHiders =
          gameParticipants.values().stream().filter(team -> team == HuntTeam.HIDERS).count();

      if (totalHunters > 0) {
        boolean allHuntersReady = readyHunters == totalHunters;
        content =
            content
                .append(Component.newline())
                .append(
                    Component.text(
                        allHuntersReady ? "\u2713 " : "\u2022 ",
                        allHuntersReady ? NamedTextColor.GREEN : NamedTextColor.YELLOW))
                .append(Component.text("Hunters Ready: ", NamedTextColor.GRAY))
                .append(Component.text(readyHunters + "/" + totalHunters, NamedTextColor.WHITE));
      }
      if (totalHiders > 0) {
        boolean allHidersReady = readyHiders == totalHiders;
        content =
            content
                .append(Component.newline())
                .append(
                    Component.text(
                        allHidersReady ? "\u2713 " : "\u2022 ",
                        allHidersReady ? NamedTextColor.GREEN : NamedTextColor.YELLOW))
                .append(Component.text("Hiders Ready: ", NamedTextColor.GRAY))
                .append(Component.text(readyHiders + "/" + totalHiders, NamedTextColor.WHITE));
      }
    }
    // CHECKSTYLE:ON: AvoidEscapedUnicodeCharacters

    hologramManager.updateReadyStatusDisplay(content);
    updateStartGameHologram();
  }

  private void checkAllPlayersReady() {
    HuntGameMode currentMode = gameModeManager.getCurrentGameMode();
    HuntGameModeStrategy strategy = HuntGameModeStrategyFactory.getStrategy(currentMode);

    // A player is ready the moment they meet the gamemode's requirements - no separate
    // explicit ready toggle needed.
    List<UUID> readyPlayers = new ArrayList<>();
    for (Player player : Bukkit.getOnlinePlayers()) {
      UUID playerId = player.getUniqueId();
      HuntPlayerData data = lobbyManager.getPlayerData(playerId);

      HuntGameModeStrategy.ReadyResult readyResult =
          strategy.canPlayerReady(player, data, playerMapVotes, hologramManager);

      if (readyResult.canReady()) {
        readyPlayers.add(playerId);
      }
    }

    // Check if game can start using the strategy
    if (strategy.canStartGame(readyPlayers, playerMapVotes, hologramManager)
        && readyPlayers.size() >= strategy.getMinimumPlayers()) {
      broadcastMessage("All players are ready! Game can now be started!", NamedTextColor.GREEN);
    }
  }

  private void broadcastMessage(String message, NamedTextColor color) {
    for (Player player : Bukkit.getOnlinePlayers()) {
      player.sendMessage(Component.text(message, color));
    }
  }

  /**
   * Checks if the prep phase is currently active.
   *
   * @return true if prep phase is active, false otherwise
   */
  public boolean isPrepPhaseActive() {
    return prepPhaseActive;
  }

  /**
   * Checks if a game is currently starting (in countdown).
   *
   * @return true if game is starting, false otherwise
   */
  public boolean isGameStarting() {
    return gameStarting;
  }

  /**
   * Checks if the actual gameplay is active (after lock-in period).
   *
   * @return true if game is active, false otherwise
   */
  public boolean isGameActive() {
    return gameActive;
  }

  /**
   * Gets the currently selected map (only valid during/after game start).
   *
   * @return the selected map, or null if no game is running
   */
  public HuntMap getSelectedMap() {
    return selectedMap;
  }

  /**
   * Gets a copy of the current game participants.
   *
   * @return map of player UUIDs to their teams
   */
  public Map<UUID, HuntTeam> getGameParticipants() {
    return new HashMap<>(gameParticipants);
  }

  /**
   * Gets a copy of the current game class selections.
   *
   * @return map of player UUIDs to their class selections
   */
  public Map<UUID, Object> getGameClassSelections() {
    return new HashMap<>(gameClassSelections);
  }

  /**
   * Gets a copy of the current player map votes.
   *
   * @return map of player UUIDs to their map votes
   */
  public Map<UUID, HuntMap> getPlayerMapVotes() {
    return new HashMap<>(playerMapVotes);
  }

  /**
   * Gets the ready status for a specific player.
   *
   * @param playerId The UUID of the player
   * @return true if the player is ready, false otherwise
   */
  public Boolean getPlayerReadyStatus(UUID playerId) {
    return playerReadyStatus.get(playerId);
  }

  /**
   * Removes a player from the prep phase when they leave.
   *
   * @param playerId The UUID of the player leaving
   */
  public void removePlayer(UUID playerId) {
    HuntMap previousVote = playerMapVotes.remove(playerId);
    if (previousVote != null) {
      mapVoteCounts.put(previousVote, mapVoteCounts.get(previousVote) - 1);
    }

    playerReadyStatus.remove(playerId);
    gameParticipants.remove(playerId);
    gameClassSelections.remove(playerId);
    allPlayerSelections.remove(playerId);

    if (prepPhaseActive) {
      updateReadyStatusHologram();
    }

    // Remove from game timer if active
    if (gameTimerBar != null) {
      Player player = Bukkit.getPlayer(playerId);
      if (player != null) {
        gameTimerBar.removePlayer(player);
      }
    }
  }

  /**
   * Updates the ready status hologram when players change their class selections. This should be
   * called from HuntCommand when players join or leave classes.
   *
   * @param playerId The UUID of the player whose class selection changed
   * @param newTeam The player's new team (null if they left)
   * @param newClass The player's new class (null if they left)
   */
  public void updatePlayerClassSelection(UUID playerId, HuntTeam newTeam, Object newClass) {
    // Auto-start prep phase if not active and someone joins a class
    if (!prepPhaseActive && newTeam != null) {
      startPrepPhase();
      return; // startPrepPhase will call updateReadyStatusHologram
    }

    if (!prepPhaseActive) {
      return;
    }

    // Update game participants if they have valid selections
    Player player = Bukkit.getPlayer(playerId);
    if (player != null) {
      // Track all player selections (regardless of other selections - just need team)
      if (newTeam != null) {
        allPlayerSelections.put(playerId, newTeam);
        plugin
            .getLogger()
            .info(
                "Added player "
                    + player.getName()
                    + " to allPlayerSelections as "
                    + newTeam
                    + ". Total in allPlayerSelections: "
                    + allPlayerSelections.size());
      } else {
        allPlayerSelections.remove(playerId);
        plugin
            .getLogger()
            .info(
                "Removed player "
                    + player.getName()
                    + " from allPlayerSelections. Total remaining: "
                    + allPlayerSelections.size());
      }

      // Add to gameParticipants if they have a valid team, class selection, AND map
      // vote
      if (newTeam != null && newClass != null && playerMapVotes.containsKey(playerId)) {
        gameParticipants.put(playerId, newTeam);
        gameClassSelections.put(playerId, newClass);
        plugin
            .getLogger()
            .info(
                "Added player "
                    + player.getName()
                    + " to gameParticipants as "
                    + newTeam
                    + " with class "
                    + newClass
                    + " and map vote "
                    + playerMapVotes.get(playerId)
                    + ". Total participants: "
                    + gameParticipants.size());
      } else {
        // Remove from participants if they left their class or don't have map vote
        gameParticipants.remove(playerId);
        gameClassSelections.remove(playerId);
        playerReadyStatus.remove(playerId); // Also remove ready status if they left
        plugin
            .getLogger()
            .info(
                "Removed player "
                    + player.getName()
                    + " from gameParticipants. Total remaining: "
                    + gameParticipants.size()
                    + " (missing: team="
                    + (newTeam != null)
                    + ", class="
                    + (newClass != null)
                    + ", mapVote="
                    + playerMapVotes.containsKey(playerId)
                    + ")");
      }
    }

    // Update holograms
    updateReadyStatusHologram();
  }

  /**
   * Updates the ready status hologram when players change their disguise selections. This should be
   * called from the disguise system when hunters select or deselect disguises.
   */
  public void updateDisguiseSelections() {
    plugin.getLogger().info("updateDisguiseSelections called, prepPhaseActive: " + prepPhaseActive);
    if (prepPhaseActive) {
      plugin.getLogger().info("Updating ready status hologram due to disguise selection change");
      updateReadyStatusHologram();
    }
  }

  /**
   * Removes a player's map vote.
   *
   * @param player The player whose vote to remove
   */
  public void removeMapVote(Player player) {
    if (!prepPhaseActive || gameStarting) {
      return;
    }

    UUID playerId = player.getUniqueId();
    HuntMap previousVote = playerMapVotes.remove(playerId);

    if (previousVote != null) {
      // Remove vote count
      mapVoteCounts.put(previousVote, mapVoteCounts.get(previousVote) - 1);

      // Remove from game participants since map vote is required
      gameParticipants.remove(playerId);
      gameClassSelections.remove(playerId);
      playerReadyStatus.remove(playerId);

      player.sendMessage(
          Component.text(
              "Removed vote for " + previousVote.getDisplayName() + "!", NamedTextColor.YELLOW));

      plugin
          .getLogger()
          .info("Removed " + player.getName() + " from gameParticipants after removing map vote");

      // Update holograms
      updateReadyStatusHologram();
    } else {
      player.sendMessage(Component.text("You haven't voted for any map!", NamedTextColor.RED));
    }
  }

  /**
   * Teleports all players in hunt worlds back to the lobby location. Used after a game ends and
   * fireworks finish.
   */
  public void teleportAllPlayersToLobby() {
    // Get the actual hunt lobby location from config - "lobby.*" was never a real config
    // section in hunt.yml (only "hunt.world"/"hunt.spawn.*" are), so this always silently fell
    // back to a bogus intermediate world/location, which in turn made HuntCleanupListener treat
    // this as "left the hunt experience" and strip kit/disguise/size before the follow-up
    // /hunt command could bring the player back (see specs/lobby-return-persistence.md).
    String worldName = config.getString("hunt.world", "hunt");
    double x = config.getDouble("hunt.spawn.x", 0.0);
    double y = config.getDouble("hunt.spawn.y", 65.0);
    double z = config.getDouble("hunt.spawn.z", 0.0);
    float yaw = (float) config.getDouble("hunt.spawn.yaw", 0.0);
    float pitch = (float) config.getDouble("hunt.spawn.pitch", 0.0);

    Location lobbyLocation = new Location(Bukkit.getWorld(worldName), x, y, z, yaw, pitch);

    // If lobby world doesn't exist, use the server's default world
    if (lobbyLocation.getWorld() == null) {
      lobbyLocation = Bukkit.getWorlds().get(0).getSpawnLocation();
      plugin.getLogger().warning("Lobby world not found. Using default world spawn instead.");
    }

    // Teleport all players in hunt worlds directly to the real hunt lobby - kit, disguise, and
    // entity size are intentionally left untouched so they persist into the lobby, matching a
    // round ending rather than a fresh /hunt join (which still clears everything via
    // HuntCommand.teleportToHuntSpawn/clearPlayerForHuntLobby).
    Location finalLobbyLocation = lobbyLocation;
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (player.getWorld().getName().toLowerCase().contains("hunt")) {
        // Reset ready flag so the lobby menu reflects the new prep phase state
        lobbyManager.getOrCreatePlayerData(player.getUniqueId()).setReady(false);

        // Reset game mode to adventure
        player.setGameMode(GameMode.ADVENTURE);

        // Remove any potion effects - short-lived combat buffs, not part of what should
        // persist (kit/disguise/size are - see specs/lobby-return-persistence.md)
        for (PotionEffect effect : player.getActivePotionEffects()) {
          player.removePotionEffect(effect.getType());
        }

        player.teleport(finalLobbyLocation);
        Bukkit.getScheduler()
            .runTaskLater(
                plugin,
                () -> {
                  if (!player.isOnline()) {
                    return;
                  }
                  // Only re-apply if something (e.g. Multiverse) actually moved the player
                  // away from the lobby in the interim - re-teleporting unconditionally here
                  // caused a visible double-teleport on every normal round end (see
                  // specs/double-teleport-to-lobby.md).
                  Location current = player.getLocation();
                  boolean driftedAway =
                      current.getWorld() == null
                          || !current.getWorld().equals(finalLobbyLocation.getWorld())
                          || current.distanceSquared(finalLobbyLocation) > 1.0;
                  if (driftedAway) {
                    player.teleport(finalLobbyLocation);
                  }
                },
                2L); // Matches HuntCommand#teleportToHuntSpawn's delay for the same
        // world-manager-interference scenario - a 2-second delay (the old value) meant
        // the correction, when it did fire, was as jarring as the problem it was
        // fixing (see specs/double-teleport-to-lobby.md).
      }
    }

    // Restart the prep phase for the next round - startPrepPhase() calls
    // resyncPersistedPlayerSelections() internally, which is what actually re-adds each
    // returning player's name back into the class holograms from their retained
    // HuntPlayerData. Without this, kit/team/class stay valid (see
    // specs/lobby-return-persistence.md) but the holograms stay empty until someone
    // happens to re-click a class selection for unrelated reasons (see
    // specs/hologram-names-lost-on-round-end.md).
    startPrepPhase();
  }

  /**
   * Sets the death handler for this prep phase manager.
   *
   * @param deathHandler The death handler
   */
  public void setDeathHandler(HuntDeathHandler deathHandler) {
    this.deathHandler = deathHandler;
    plugin.getLogger().info("Death handler set for HuntPrepPhaseManager");
  }

  /**
   * Sets the spotlight listener so its idle-tracking state can be cleared on round/prep-phase end,
   * so nothing carries over into the lobby (see
   * specs/spotlight-countdown-persists-after-round-end.md).
   *
   * @param spotlightListener The spotlight listener instance
   */
  public void setSpotlightListener(HuntSpotlightListener spotlightListener) {
    this.spotlightListener = spotlightListener;
  }

  /** Starts the heartbeat task that plays sounds to hiders when hunters are nearby. */
  private void startHeartbeatTask() {
    // Configuration for heartbeat detection
    double detectionRadius = config.getDouble("hunt.heartbeat.detection-radius", 15.0);

    // Proximity-based configuration
    double veryCloseDistance =
        config.getDouble("hunt.heartbeat.proximity.very-close-distance", 3.0);
    double closeDistance = config.getDouble("hunt.heartbeat.proximity.close-distance", 7.0);
    double mediumDistance = config.getDouble("hunt.heartbeat.proximity.medium-distance", 12.0);

    int maxInterval = config.getInt("hunt.heartbeat.proximity.max-interval-ticks", 60);
    int minInterval = config.getInt("hunt.heartbeat.proximity.min-interval-ticks", 8);

    float volume = (float) config.getDouble("hunt.heartbeat.proximity.volume", 0.7);
    float pitch = (float) config.getDouble("hunt.heartbeat.proximity.pitch", 0.8);

    boolean actionBarEnabled =
        config.getBoolean("hunt.heartbeat.proximity.action-bar-enabled", true);
    boolean titleFlashEnabled =
        config.getBoolean("hunt.heartbeat.proximity.title-flash-enabled", true);

    // Map to track individual heartbeat tasks for each hider
    // (using class field instead of local variable for cleanup)

    heartbeatTask =
        new BukkitRunnable() {
          @Override
          public void run() {
            // Create copies to avoid ConcurrentModificationException
            Map<UUID, HuntTeam> participantsCopy = new HashMap<>(gameParticipants);

            for (Map.Entry<UUID, HuntTeam> entry : participantsCopy.entrySet()) {
              UUID playerId = entry.getKey();
              HuntTeam team = entry.getValue();

              // Only check hiders
              if (team != HuntTeam.HIDERS) {
                continue;
              }

              Player hider = Bukkit.getPlayer(playerId);
              if (hider == null || !hider.isOnline()) {
                // Clean up task if player is offline
                BukkitRunnable task = hiderHeartbeatTasks.remove(playerId);
                if (task != null) {
                  task.cancel();
                }
                continue;
              }

              // Find the closest hunter
              double closestDistance = Double.MAX_VALUE;
              Location hiderLocation = hider.getLocation();

              for (Map.Entry<UUID, HuntTeam> otherEntry : participantsCopy.entrySet()) {
                UUID otherPlayerId = otherEntry.getKey();
                HuntTeam otherTeam = otherEntry.getValue();

                // Only check hunters
                if (otherTeam != HuntTeam.HUNTERS) {
                  continue;
                }

                Player hunter = Bukkit.getPlayer(otherPlayerId);
                if (hunter == null || !hunter.isOnline()) {
                  continue;
                }

                // Check distance
                Location hunterLocation = hunter.getLocation();
                if (hiderLocation.getWorld().equals(hunterLocation.getWorld())) {
                  double distance = hiderLocation.distance(hunterLocation);
                  if (distance <= detectionRadius && distance < closestDistance) {
                    closestDistance = distance;
                    plugin
                        .getLogger()
                        .info(
                            "Hunter "
                                + hunter.getName()
                                + " is "
                                + String.format("%.1f", distance)
                                + " blocks from hider "
                                + hider.getName());
                  }
                }
              }

              // "Recently hurt" exit check - see specs/recently-hurt-tension.md. Reuses the
              // closestDistance just computed above instead of scanning hunters again.
              if (hidersInHurtState.contains(playerId)) {
                long lastHit = lastHitTimestamps.getOrDefault(playerId, 0L);
                long timeoutMillis =
                    config.getLong("hunt.hurt-tension.timeout-seconds", 10) * 1000L;
                boolean timeoutElapsed = (System.currentTimeMillis() - lastHit) >= timeoutMillis;
                boolean distanceCleared = closestDistance > mediumDistance;
                if (timeoutElapsed && distanceCleared) {
                  clearHurtTensionState(playerId);
                }
              }

              // Handle heartbeat based on proximity
              if (closestDistance <= detectionRadius && closestDistance != Double.MAX_VALUE) {
                plugin
                    .getLogger()
                    .info(
                        "Starting heartbeat for hider "
                            + hider.getName()
                            + " with closest hunter at "
                            + String.format("%.1f", closestDistance)
                            + " blocks");

                // Calculate heartbeat interval based on distance
                int heartbeatInterval =
                    calculateHeartbeatInterval(
                        closestDistance,
                        detectionRadius,
                        minInterval,
                        maxInterval,
                        veryCloseDistance,
                        closeDistance,
                        mediumDistance);

                plugin
                    .getLogger()
                    .info(
                        "Calculated heartbeat interval: "
                            + heartbeatInterval
                            + " ticks for distance "
                            + String.format("%.1f", closestDistance));

                // Start or update heartbeat task for this hider
                BukkitRunnable existingTask = hiderHeartbeatTasks.get(playerId);
                if (existingTask != null) {
                  existingTask.cancel();
                  plugin
                      .getLogger()
                      .info("Cancelled existing heartbeat task for " + hider.getName());
                }

                // Capture the distance value to make it effectively final
                final double capturedClosestDistance = closestDistance;

                // Create new heartbeat task for this hider
                BukkitRunnable hiderTask =
                    new BukkitRunnable() {
                      @Override
                      public void run() {
                        // Check if hider is still online and in range
                        if (!hider.isOnline()) {
                          hiderHeartbeatTasks.remove(playerId);
                          cancel();
                          return;
                        }

                        plugin
                            .getLogger()
                            .info(
                                "Playing heartbeat for "
                                    + hider.getName()
                                    + " at distance "
                                    + String.format("%.1f", capturedClosestDistance));

                        // Play heartbeat sound
                        playHeartbeatEffect(
                            hider,
                            capturedClosestDistance,
                            veryCloseDistance,
                            closeDistance,
                            mediumDistance,
                            volume,
                            pitch,
                            actionBarEnabled,
                            titleFlashEnabled);
                      }
                    };

                hiderTask.runTaskTimer(plugin, 0L, heartbeatInterval);
                hiderHeartbeatTasks.put(playerId, hiderTask);

              } else {
                // No hunters nearby, cancel heartbeat task
                BukkitRunnable task = hiderHeartbeatTasks.remove(playerId);
                if (task != null) {
                  task.cancel();
                  plugin
                      .getLogger()
                      .info(
                          "Cancelled heartbeat task for "
                              + hider.getName()
                              + " - no hunters in range");

                  // Clear the tracked heartbeat warning - the ability status action bar will
                  // stop including it on its next refresh.
                  currentHeartbeatMessages.remove(hider.getUniqueId());
                }
              }
            }
          }
        }.runTaskTimer(plugin, 0L, 20L); // Check every second for proximity changes
  }

  /**
   * Records that a hider was hit by a hunter, entering (or refreshing) the "recently hurt"
   * combat-tension state. See specs/recently-hurt-tension.md.
   *
   * @param hiderId The hider who was hit
   */
  public void recordHiderHit(UUID hiderId) {
    lastHitTimestamps.put(hiderId, System.currentTimeMillis());

    Player hider = Bukkit.getPlayer(hiderId);
    if (hider == null || !hider.isOnline()) {
      return;
    }

    // Ring on every hit, not just entry into the hurt state - grilled with Nirav 2026-07-17
    // after the periodic 8-14s repeat felt too delayed during active combat.
    playHurtBellBurst(hiderId, hider);

    if (hidersInHurtState.contains(hiderId)) {
      return; // Heartbeat and bell-repeat tasks already running.
    }
    hidersInHurtState.add(hiderId);
    startHurtHeartbeatTask(hiderId, hider);
    startHurtBellRepeatTask(hiderId);
  }

  /**
   * Plays a burst of a few raid-bell rings in quick succession, immediately in response to a hit.
   * Vanilla randomizes its own strike variation each play.
   */
  private void playHurtBellBurst(UUID hiderId, Player hider) {
    float bellVolume = (float) config.getDouble("hunt.hurt-tension.bell-volume", 0.9);
    int minCount = config.getInt("hunt.hurt-tension.bell-ring-min-count", 4);
    int maxCount = config.getInt("hunt.hurt-tension.bell-ring-max-count", 6);
    int minIntervalTicks = config.getInt("hunt.hurt-tension.bell-ring-interval-min-ticks", 8);
    int maxIntervalTicks = config.getInt("hunt.hurt-tension.bell-ring-interval-max-ticks", 10);

    int ringCount = minCount + (int) (Math.random() * Math.max(1, maxCount - minCount + 1));
    long delayTicks = 0;
    for (int i = 0; i < ringCount; i++) {
      Bukkit.getScheduler()
          .runTaskLater(
              plugin,
              () -> {
                Player current = Bukkit.getPlayer(hiderId);
                if (current != null && current.isOnline()) {
                  current.playSound(current.getLocation(), Sound.BLOCK_BELL_USE, bellVolume, 1.0f);
                }
              },
              delayTicks);
      delayTicks +=
          minIntervalTicks
              + (int) (Math.random() * Math.max(1, maxIntervalTicks - minIntervalTicks + 1));
    }
  }

  /**
   * Starts a repeating bell-burst task for a hider in the hurt state, continuing for as long as the
   * state itself is active - i.e. until the "got away" exit condition (no hit for the timeout, and
   * the closest hunter beyond medium-distance) actually clears them. Requested by Nirav 2026-07-17:
   * "keep ringing it until they get far away from the hunter," replacing the one-shot-per-hit-only
   * behavior.
   */
  private void startHurtBellRepeatTask(UUID hiderId) {
    int intervalSeconds = config.getInt("hunt.hurt-tension.bell-repeat-interval-seconds", 5);

    BukkitRunnable task =
        new BukkitRunnable() {
          @Override
          public void run() {
            Player current = Bukkit.getPlayer(hiderId);
            if (current == null || !current.isOnline() || !hidersInHurtState.contains(hiderId)) {
              cancel();
              return;
            }
            playHurtBellBurst(hiderId, current);
          }
        };
    task.runTaskTimer(plugin, intervalSeconds * 20L, intervalSeconds * 20L);
    hurtBellRepeatTasks.put(hiderId, task);
  }

  /** Starts the fixed-interval elevated heartbeat task for a hider in the hurt state. */
  private void startHurtHeartbeatTask(UUID hiderId, Player hider) {
    int intervalTicks = config.getInt("hunt.hurt-tension.heartbeat-interval-ticks", 18);
    float heartbeatVolume = (float) config.getDouble("hunt.hurt-tension.heartbeat-volume", 0.65);
    float heartbeatPitch = (float) config.getDouble("hunt.hurt-tension.heartbeat-pitch", 1.0);

    BukkitRunnable task =
        new BukkitRunnable() {
          @Override
          public void run() {
            Player current = Bukkit.getPlayer(hiderId);
            if (current == null || !current.isOnline() || !hidersInHurtState.contains(hiderId)) {
              cancel();
              return;
            }
            current.playSound(
                current.getLocation(),
                Sound.ENTITY_WARDEN_HEARTBEAT,
                heartbeatVolume,
                heartbeatPitch);
          }
        };
    task.runTaskTimer(plugin, 0L, intervalTicks);
    hurtHeartbeatTasks.put(hiderId, task);
  }

  /**
   * Clears the "recently hurt" state for a single hider - cancels the elevated heartbeat task.
   * Called when the "got away" exit condition is met, or when the hider leaves the hunt experience.
   *
   * @param hiderId The hider whose hurt-tension state should be cleared
   */
  public void clearHurtTensionState(UUID hiderId) {
    hidersInHurtState.remove(hiderId);
    lastHitTimestamps.remove(hiderId);

    BukkitRunnable heartbeatTask = hurtHeartbeatTasks.remove(hiderId);
    if (heartbeatTask != null) {
      heartbeatTask.cancel();
    }
    BukkitRunnable bellRepeatTask = hurtBellRepeatTasks.remove(hiderId);
    if (bellRepeatTask != null) {
      bellRepeatTask.cancel();
    }
  }

  /** Clears the "recently hurt" state for every tracked hider - used on prep/game end. */
  private void clearAllHurtTensionState() {
    for (UUID hiderId : new HashSet<>(hidersInHurtState)) {
      clearHurtTensionState(hiderId);
    }
    hidersInHurtState.clear();
    lastHitTimestamps.clear();
    hurtHeartbeatTasks.clear();
    hurtBellRepeatTasks.clear();
  }

  /** Calculates the heartbeat interval based on hunter proximity. */
  private int calculateHeartbeatInterval(
      double distance,
      double maxDistance,
      int minInterval,
      int maxInterval,
      double veryClose,
      double close,
      double medium) {

    // Normalize distance (0.0 = very close, 1.0 = at max distance)
    double normalizedDistance = Math.min(distance / maxDistance, 1.0);

    // Use different formulas for different distance zones
    if (distance <= veryClose) {
      // Very close: use minimum interval
      return minInterval;
    } else if (distance <= close) {
      // Close: interpolate between min and a faster medium rate
      double factor = (distance - veryClose) / (close - veryClose);
      return (int) (minInterval + factor * (minInterval * 2));
    } else if (distance <= medium) {
      // Medium: interpolate between medium and slower rate
      double factor = (distance - close) / (medium - close);
      return (int) (minInterval * 2 + factor * (maxInterval * 0.6));
    } else {
      // Far: use slower rate based on remaining distance
      double factor = (distance - medium) / (maxDistance - medium);
      return (int) (maxInterval * 0.6 + factor * (maxInterval * 0.4));
    }
  }

  /** Plays heartbeat effect with intensity based on distance. */
  private void playHeartbeatEffect(
      Player hider,
      double distance,
      double veryClose,
      double close,
      double medium,
      float volume,
      float pitch,
      boolean actionBarEnabled,
      boolean titleFlashEnabled) {

    plugin
        .getLogger()
        .info(
            "Playing heartbeat effect for "
                + hider.getName()
                + " at distance "
                + String.format("%.1f", distance));

    // Play sound with intensity based on distance - see specs/proximity-tier-loops.md.
    // Sound.ENTITY_WARDEN_HEARTBEAT replaces the old placeholder sounds; the existing
    // distance-based interval (calculateHeartbeatInterval) already drives tempo, so tier
    // differentiation here is volume/pitch plus a chance of a tier-appropriate flavor layer.
    Sound heartbeatSound = Sound.ENTITY_WARDEN_HEARTBEAT;
    float adjustedVolume;
    float adjustedPitch;

    if (distance <= veryClose) {
      adjustedVolume = volume * 1.2f;
      adjustedPitch = pitch * 0.9f;
    } else if (distance <= close) {
      adjustedVolume = volume;
      adjustedPitch = pitch;
    } else if (distance <= medium) {
      adjustedVolume = volume * 0.65f;
      adjustedPitch = pitch;
    } else {
      adjustedVolume = volume * 0.4f;
      adjustedPitch = pitch * 1.1f;
    }

    // Try multiple sound approaches to ensure it plays
    try {
      // Primary sound method
      hider.playSound(hider.getLocation(), heartbeatSound, adjustedVolume, adjustedPitch);

      // Backup sound method with explicit sound category
      hider.playSound(
          hider.getLocation(),
          heartbeatSound,
          SoundCategory.PLAYERS,
          adjustedVolume,
          adjustedPitch);

      playProximityFlavorLayer(hider, distance, veryClose, close, medium, adjustedVolume);

      plugin.getLogger().info("Successfully played heartbeat sounds for " + hider.getName());
    } catch (Exception e) {
      plugin
          .getLogger()
          .warning("Failed to play heartbeat sound for " + hider.getName() + ": " + e.getMessage());

      // Fallback to a basic sound that should always work
      try {
        hider.playSound(hider.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 0.5f);
        plugin.getLogger().info("Played fallback heartbeat sound for " + hider.getName());
      } catch (Exception e2) {
        plugin
            .getLogger()
            .severe(
                "Could not play any heartbeat sound for "
                    + hider.getName()
                    + ": "
                    + e2.getMessage());
      }
    }

    // Re-enable action bar for debugging
    if (actionBarEnabled) {
      Component message;
      if (distance <= veryClose) {
        // CHECKSTYLE:OFF: AvoidEscapedUnicodeCharacters
        message =
            Component.text(
                    "\u26A0\u26A0\u26A0 HUNTER VERY CLOSE \u26A0\u26A0\u26A0",
                    NamedTextColor.DARK_RED)
                .decorate(TextDecoration.BOLD);
      } else if (distance <= close) {
        message = Component.text("\u26A0\u26A0 Hunter Close \u26A0\u26A0", NamedTextColor.RED);
      } else if (distance <= medium) {
        message = Component.text("\u26A0 Hunter Nearby \u26A0", NamedTextColor.YELLOW);
      } else {
        message = Component.text("Hunter Far", NamedTextColor.GRAY);
      }
      // CHECKSTYLE:ON: AvoidEscapedUnicodeCharacters
      currentHeartbeatMessages.put(hider.getUniqueId(), message);
    }
  }

  /**
   * Probabilistically layers a tier-appropriate flavor sound on top of the main heartbeat, matching
   * the layered ambience-bed-plus-stinger design from specs/proximity-tier-loops.md. Rolled once
   * per heartbeat tick rather than on its own independent schedule, reusing the existing
   * distance-driven interval as the retrigger clock.
   */
  private void playProximityFlavorLayer(
      Player hider,
      double distance,
      double veryClose,
      double close,
      double medium,
      float baseVolume) {
    double roll = Math.random();
    if (distance <= veryClose) {
      if (roll < 0.35) {
        hider.playSound(
            hider.getLocation(), Sound.ENTITY_WARDEN_TENDRIL_CLICKS, baseVolume * 0.8f, 1.0f);
      } else if (roll < 0.5) {
        Sound[] nearbySounds = {
          Sound.ENTITY_WARDEN_NEARBY_CLOSE,
          Sound.ENTITY_WARDEN_NEARBY_CLOSER,
          Sound.ENTITY_WARDEN_NEARBY_CLOSEST
        };
        Sound nearby = nearbySounds[(int) (Math.random() * nearbySounds.length)];
        hider.playSound(hider.getLocation(), nearby, baseVolume * 0.9f, 1.0f);
      }
    } else if (distance <= close) {
      if (roll < 0.3) {
        hider.playSound(
            hider.getLocation(), Sound.ENTITY_WARDEN_TENDRIL_CLICKS, baseVolume * 0.6f, 1.0f);
      } else if (roll < 0.45) {
        hider.playSound(
            hider.getLocation(), Sound.BLOCK_SCULK_SENSOR_CLICKING, baseVolume * 0.6f, 1.0f);
      } else if (roll < 0.5) {
        hider.playSound(
            hider.getLocation(), Sound.ENTITY_WARDEN_LISTENING_ANGRY, baseVolume * 0.5f, 1.0f);
      }
    } else if (distance <= medium) {
      if (roll < 0.2) {
        hider.playSound(
            hider.getLocation(), Sound.BLOCK_SCULK_SENSOR_CLICKING, baseVolume * 0.5f, 1.0f);
      } else if (roll < 0.28) {
        hider.playSound(hider.getLocation(), Sound.ENTITY_WARDEN_SNIFF, baseVolume * 0.4f, 1.0f);
      }
    }
    // Far tier: heartbeat only, no flavor layer - matches the sparse "barely a whisper" design.
  }

  /**
   * Returns this player's current heartbeat proximity warning, or null if none. Used by
   * HuntUtilityListener to merge it into the unified ability status action bar.
   *
   * @param playerId The player to check
   * @return The current heartbeat warning component, or null
   */
  public Component getHeartbeatMessage(UUID playerId) {
    return currentHeartbeatMessages.get(playerId);
  }

  private void removePlayersFromAllHolograms() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      hologramManager.removePlayerFromAllHolograms(player.getUniqueId());
    }
  }

  /**
   * Test method to manually trigger heartbeat for a player for debugging purposes.
   *
   * @param player The player to test heartbeat for
   * @param distance Simulated distance to test different heartbeat intensities
   */
  public void testHeartbeat(Player player, double distance) {
    plugin
        .getLogger()
        .info("Testing heartbeat for " + player.getName() + " at simulated distance " + distance);

    double veryCloseDistance =
        config.getDouble("hunt.heartbeat.proximity.very-close-distance", 3.0);
    double closeDistance = config.getDouble("hunt.heartbeat.proximity.close-distance", 7.0);
    double mediumDistance = config.getDouble("hunt.heartbeat.proximity.medium-distance", 12.0);
    float volume = (float) config.getDouble("hunt.heartbeat.proximity.volume", 0.7);
    float pitch = (float) config.getDouble("hunt.heartbeat.proximity.pitch", 0.8);
    boolean actionBarEnabled =
        config.getBoolean("hunt.heartbeat.proximity.action-bar-enabled", true);
    boolean titleFlashEnabled =
        config.getBoolean("hunt.heartbeat.proximity.title-flash-enabled", true);

    playHeartbeatEffect(
        player,
        distance,
        veryCloseDistance,
        closeDistance,
        mediumDistance,
        volume,
        pitch,
        actionBarEnabled,
        titleFlashEnabled);
  }
}
