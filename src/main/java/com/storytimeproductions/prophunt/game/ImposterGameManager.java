package com.storytimeproductions.prophunt.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

/**
 * Manages the Imposter Hunt gamemode end to end: assigns murderer/sheriff/innocent roles at round
 * start, tracks round state and the game timer, and evaluates win conditions (murderer eliminated,
 * murderer wins by attrition, etc.). Works alongside {@link ImposterCoinManager} for the coin
 * economy and {@link ImposterListener} for in-round event handling.
 */
public class ImposterGameManager {
  private final JavaPlugin plugin;
  private final FileConfiguration config;
  private final HuntLobbyManager lobbyManager;

  // Game state
  private boolean gameActive;
  private final Map<UUID, ImposterPlayerData> playerData;
  private final List<Location> gravestones;
  private final Map<Location, Long> gravestoneTimestamps;
  private final Map<Location, String> gravestonePlayerNames;

  // Game timer
  private BukkitTask gameTimer;
  private BossBar gameTimerBar;

  // Configuration values
  private static final int COINS_FOR_ZAPPER = 10;
  private static final int THROWABLE_COOLDOWN = 10; // seconds
  private static final int INVESTIGATION_COOLDOWN = 30; // seconds

  /**
   * Creates a new ImposterGameManager instance.
   *
   * @param plugin The plugin instance
   * @param config The configuration file
   * @param lobbyManager The hunt lobby manager
   */
  public ImposterGameManager(
      JavaPlugin plugin, FileConfiguration config, HuntLobbyManager lobbyManager) {
    this.plugin = plugin;
    this.config = config;
    this.lobbyManager = lobbyManager;
    this.gameActive = false;
    this.playerData = new HashMap<>();
    this.gravestones = new ArrayList<>();
    this.gravestoneTimestamps = new HashMap<>();
    this.gravestonePlayerNames = new HashMap<>();
  }

  /**
   * Starts a new Imposter Hunt game.
   *
   * @param players The list of players participating in the game
   */
  public void startGame(List<Player> players) {
    if (gameActive) {
      plugin.getLogger().warning("Attempted to start Imposter game while one is already active");
      return;
    }

    if (players.size() < 3) {
      broadcastMessage("Need at least 3 players to start Imposter Hunt!", NamedTextColor.RED);
      return;
    }

    gameActive = true;
    resetGame();

    // Initialize player data
    for (Player player : players) {
      playerData.put(player.getUniqueId(), new ImposterPlayerData(player.getUniqueId()));
    }

    // Assign roles
    assignRoles(players);

    // Give initial items
    giveInitialItems(players);

    // Start game timer
    startGameTimer();

    // Notify players
    broadcastMessage("Imposter Hunt has begun!", NamedTextColor.GOLD);

    plugin.getLogger().info("Started Imposter Hunt game with " + players.size() + " players");
  }

  /**
   * Ends the current Imposter Hunt game.
   *
   * @param winningRole The role that won the game (null for timeout)
   */
  public void endGame(ImposterRole winningRole) {
    if (!gameActive) {
      return;
    }

    gameActive = false;

    // Cancel game timer
    if (gameTimer != null) {
      gameTimer.cancel();
      gameTimer = null;
    }

    // Remove boss bar
    if (gameTimerBar != null) {
      gameTimerBar.removeAll();
      gameTimerBar = null;
    }

    // Announce winner
    if (winningRole != null) {
      broadcastMessage(winningRole.getDisplayName() + "s Win!", NamedTextColor.GOLD);
      showWinTitle(winningRole);
    } else {
      broadcastMessage("Game ended - Time's up!", NamedTextColor.YELLOW);
    }

    // Teleport players back to lobby after delay
    Bukkit.getScheduler().runTaskLater(plugin, this::teleportPlayersToLobby, 100L); // 5 seconds

    plugin
        .getLogger()
        .info(
            "Ended Imposter Hunt game. Winner: "
                + (winningRole != null ? winningRole.getDisplayName() : "Timeout"));
  }

  /**
   * Assigns roles to players randomly.
   *
   * @param players The list of players to assign roles to
   */
  private void assignRoles(List<Player> players) {
    List<Player> shuffledPlayers = new ArrayList<>(players);
    Collections.shuffle(shuffledPlayers);

    int playerCount = players.size();
    int murdererCount = Math.max(1, playerCount / 6); // 1 murderer per 6 players, minimum 1
    int sheriffCount = Math.max(1, playerCount / 8); // 1 sheriff per 8 players, minimum 1

    // Ensure we don't have more special roles than players
    if (murdererCount + sheriffCount >= playerCount) {
      murdererCount = 1;
      sheriffCount = Math.min(1, playerCount - 1);
    }

    int assignedRoles = 0;

    // Assign murderers
    for (int i = 0; i < murdererCount && assignedRoles < playerCount; i++) {
      Player player = shuffledPlayers.get(assignedRoles);
      ImposterPlayerData data = playerData.get(player.getUniqueId());
      data.setRole(ImposterRole.MURDERER);
      notifyPlayerRole(player, ImposterRole.MURDERER);
      assignedRoles++;
    }

    // Assign sheriffs
    for (int i = 0; i < sheriffCount && assignedRoles < playerCount; i++) {
      Player player = shuffledPlayers.get(assignedRoles);
      ImposterPlayerData data = playerData.get(player.getUniqueId());
      data.setRole(ImposterRole.SHERIFF);
      notifyPlayerRole(player, ImposterRole.SHERIFF);
      assignedRoles++;
    }

    // Assign innocents to remaining players
    for (int i = assignedRoles; i < playerCount; i++) {
      Player player = shuffledPlayers.get(i);
      ImposterPlayerData data = playerData.get(player.getUniqueId());
      data.setRole(ImposterRole.INNOCENT);
      notifyPlayerRole(player, ImposterRole.INNOCENT);
    }

    plugin
        .getLogger()
        .info(
            "Assigned roles: "
                + murdererCount
                + " murderers, "
                + sheriffCount
                + " sheriffs, "
                + (playerCount - murdererCount - sheriffCount)
                + " innocents");
  }

  /**
   * Notifies a player of their assigned role.
   *
   * @param player The player to notify
   * @param role The role they were assigned
   */
  private void notifyPlayerRole(Player player, ImposterRole role) {
    NamedTextColor color;
    switch (role) {
      case MURDERER:
        color = NamedTextColor.RED;
        break;
      case SHERIFF:
        color = NamedTextColor.BLUE;
        break;
      case INNOCENT:
        color = NamedTextColor.GREEN;
        break;
      default:
        color = NamedTextColor.WHITE;
        break;
    }

    Title roleTitle =
        Title.title(
            Component.text("You are: " + role.getDisplayName(), color),
            Component.text(role.getDescription(), NamedTextColor.GRAY));

    player.showTitle(roleTitle);
    player.sendMessage(
        Component.text("Role: ", NamedTextColor.GRAY)
            .append(Component.text(role.getDisplayName(), color))
            .append(Component.text(" — " + role.getDescription(), NamedTextColor.WHITE)));
  }

  /**
   * Gives initial items to players based on their roles.
   *
   * @param players The list of players to give items to
   */
  private void giveInitialItems(List<Player> players) {
    for (Player player : players) {
      ImposterPlayerData data = playerData.get(player.getUniqueId());
      if (data == null) {
        continue;
      }

      player.getInventory().clear();

      switch (data.getRole()) {
        case MURDERER:
          giveMurdererItems(player);
          break;
        case SHERIFF:
          giveSheriffItems(player);
          break;
        case INNOCENT:
          giveInnocentItems(player);
          break;
        default:
          plugin
              .getLogger()
              .warning("Unknown role for player " + player.getName() + ": " + data.getRole());
          break;
      }
    }
  }

  /**
   * Gives items to a murderer.
   *
   * @param player The murderer player
   */
  private void giveMurdererItems(Player player) {
    // Throwable weapon (Nether Star)
    ItemStack throwable = new ItemStack(Material.NETHER_STAR);
    ItemMeta meta = throwable.getItemMeta();
    meta.displayName(Component.text("Throwable Weapon", NamedTextColor.RED));
    meta.lore(
        List.of(
            Component.text("Left or right-click to throw", NamedTextColor.GRAY),
            Component.text("Instantly kills on hit", NamedTextColor.RED),
            Component.text("10 second cooldown", NamedTextColor.YELLOW)));
    throwable.setItemMeta(meta);

    player.getInventory().setItem(0, throwable);
  }

  /**
   * Gives items to a sheriff.
   *
   * @param player The sheriff player
   */
  private void giveSheriffItems(Player player) {
    // Zapper weapon
    ItemStack zapper = new ItemStack(Material.IRON_SWORD);
    ItemMeta meta = zapper.getItemMeta();
    meta.displayName(Component.text("Sheriff Zapper", NamedTextColor.BLUE));
    meta.lore(
        List.of(
            Component.text("Right-click to zap a player", NamedTextColor.GRAY),
            Component.text("Instantly kills the target", NamedTextColor.RED),
            Component.text(
                "Be careful - killing innocents kills you too!", NamedTextColor.YELLOW)));
    zapper.setItemMeta(meta);

    // Investigation tool
    ItemStack magnifyingGlass = new ItemStack(Material.CLOCK);
    ItemMeta glassMeta = magnifyingGlass.getItemMeta();
    glassMeta.displayName(Component.text("Magnifying Glass", NamedTextColor.AQUA));
    glassMeta.lore(
        List.of(
            Component.text("Right-click gravestones to investigate", NamedTextColor.GRAY),
            Component.text("Reveals time of death", NamedTextColor.YELLOW),
            Component.text("30 second cooldown", NamedTextColor.YELLOW)));
    magnifyingGlass.setItemMeta(glassMeta);

    player.getInventory().setItem(0, zapper);
    player.getInventory().setItem(1, magnifyingGlass);
  }

  /**
   * Gives items to an innocent.
   *
   * @param player The innocent player
   */
  private void giveInnocentItems(Player player) {
    // Innocent players start with no special items
    // They must collect coins to buy zappers
  }

  /** Starts the game timer. */
  private void startGameTimer() {
    int gameDuration = config.getInt("hunt.imposter.game-duration", 300); // 5 minutes default

    // Create boss bar for game timer
    gameTimerBar =
        Bukkit.createBossBar(
            "Game Time: " + formatTime(gameDuration), BarColor.GREEN, BarStyle.SOLID);

    // Add all players to boss bar
    for (UUID playerId : playerData.keySet()) {
      Player player = Bukkit.getPlayer(playerId);
      if (player != null && player.isOnline()) {
        gameTimerBar.addPlayer(player);
      }
    }

    gameTimerBar.setVisible(true);

    // Start countdown timer
    gameTimer =
        new BukkitRunnable() {
          int timeLeft = gameDuration;

          @Override
          public void run() {
            if (!gameActive) {
              cancel();
              return;
            }

            if (timeLeft <= 0) {
              // Time's up - innocents and sheriff win if murderer hasn't won
              endGame(ImposterRole.INNOCENT);
              cancel();
              return;
            }

            // Update boss bar
            gameTimerBar.setTitle("Game Time: " + formatTime(timeLeft));
            gameTimerBar.setProgress((double) timeLeft / gameDuration);

            // Change color based on time remaining
            if (timeLeft <= 60) {
              gameTimerBar.setColor(BarColor.RED);
            } else if (timeLeft <= 120) {
              gameTimerBar.setColor(BarColor.YELLOW);
            }

            timeLeft--;
          }
        }.runTaskTimer(plugin, 0L, 20L);
  }

  /**
   * Formats time in seconds to MM:SS format.
   *
   * @param seconds The time in seconds
   * @return Formatted time string
   */
  private String formatTime(int seconds) {
    int minutes = seconds / 60;
    int remainingSeconds = seconds % 60;
    return String.format("%d:%02d", minutes, remainingSeconds);
  }

  /**
   * Shows the win title to all players.
   *
   * @param winningRole The role that won
   */
  private void showWinTitle(ImposterRole winningRole) {
    NamedTextColor color;
    switch (winningRole) {
      case MURDERER:
        color = NamedTextColor.RED;
        break;
      case SHERIFF:
      case INNOCENT:
        color = NamedTextColor.GREEN;
        break;
      default:
        color = NamedTextColor.WHITE;
        break;
    }

    Title winTitle =
        Title.title(
            Component.text(winningRole.getDisplayName().toUpperCase() + "S WIN!", color),
            Component.text("Game Over", NamedTextColor.YELLOW));

    for (UUID playerId : playerData.keySet()) {
      Player player = Bukkit.getPlayer(playerId);
      if (player != null && player.isOnline()) {
        player.showTitle(winTitle);
      }
    }
  }

  /** Teleports all players back to the hunt lobby. */
  private void teleportPlayersToLobby() {
    // Get lobby location from config
    String worldName = config.getString("lobby.world", "world");
    double x = config.getDouble("lobby.x", 0);
    double y = config.getDouble("lobby.y", 64);
    double z = config.getDouble("lobby.z", 0);
    float yaw = (float) config.getDouble("lobby.yaw", 0);
    float pitch = (float) config.getDouble("lobby.pitch", 0);

    Location lobbyLocation = new Location(Bukkit.getWorld(worldName), x, y, z, yaw, pitch);

    // If lobby world doesn't exist, use the server's default world
    if (lobbyLocation.getWorld() == null) {
      lobbyLocation = Bukkit.getWorlds().get(0).getSpawnLocation();
      plugin.getLogger().warning("Lobby world not found. Using default world spawn instead.");
    }

    for (UUID playerId : playerData.keySet()) {
      Player player = Bukkit.getPlayer(playerId);
      if (player != null && player.isOnline()) {
        player.setGameMode(GameMode.ADVENTURE);
        player.getInventory().clear();
        player.teleport(lobbyLocation);

        // Auto-run hunt command to bring them back to Hunt lobby
        Bukkit.getScheduler()
            .runTaskLater(
                plugin,
                () -> {
                  if (player.isOnline()) {
                    player.performCommand("hunt");
                  }
                },
                20L); // 1 second delay
      }
    }
  }

  /**
   * Broadcasts a message to all players in the game.
   *
   * @param message The message to broadcast
   * @param color The color of the message
   */
  private void broadcastMessage(String message, NamedTextColor color) {
    for (UUID playerId : playerData.keySet()) {
      Player player = Bukkit.getPlayer(playerId);
      if (player != null && player.isOnline()) {
        player.sendMessage(Component.text(message, color));
      }
    }
  }

  /** Resets the game state for a new game. */
  private void resetGame() {
    playerData.clear();
    gravestones.clear();
    gravestoneTimestamps.clear();
    gravestonePlayerNames.clear();

    if (gameTimer != null) {
      gameTimer.cancel();
      gameTimer = null;
    }

    if (gameTimerBar != null) {
      gameTimerBar.removeAll();
      gameTimerBar = null;
    }
  }

  // Getter methods for other classes to access game state

  /**
   * Checks if the game is currently active.
   *
   * @return true if the game is active
   */
  public boolean isGameActive() {
    return gameActive;
  }

  /**
   * Gets the player data for a specific player.
   *
   * @param playerId The UUID of the player
   * @return The player data, or null if not found
   */
  public ImposterPlayerData getPlayerData(UUID playerId) {
    return playerData.get(playerId);
  }

  public Map<UUID, ImposterPlayerData> getAllPlayerData() {
    return new HashMap<>(playerData);
  }

  /**
   * Handles player death in the Imposter game.
   *
   * @param deadPlayer The player who died
   * @param killer The player who killed them (can be null)
   */
  public void handlePlayerDeath(Player deadPlayer, Player killer) {
    ImposterPlayerData deadData = playerData.get(deadPlayer.getUniqueId());
    if (deadData == null || deadData.isDead()) {
      return;
    }

    deadData.setDead(true);

    // Create gravestone
    createGravestone(deadPlayer.getLocation(), deadPlayer.getName());

    // Handle special death logic
    if (killer != null) {
      ImposterPlayerData killerData = playerData.get(killer.getUniqueId());
      if (killerData != null) {
        handleKillLogic(deadData, killerData, deadPlayer, killer);
      }
    }

    // Set to spectator mode
    deadPlayer.setGameMode(GameMode.SPECTATOR);
    deadPlayer.sendMessage(Component.text("You have been eliminated!", NamedTextColor.RED));

    // Check win conditions
    checkWinConditions();
  }

  /**
   * Handles the logic when one player kills another.
   *
   * @param deadData The data of the dead player
   * @param killerData The data of the killer
   * @param deadPlayer The dead player
   * @param killer The killer player
   */
  private void handleKillLogic(
      ImposterPlayerData deadData,
      ImposterPlayerData killerData,
      Player deadPlayer,
      Player killer) {
    ImposterRole deadRole = deadData.getRole();
    ImposterRole killerRole = killerData.getRole();

    if (killerRole == ImposterRole.SHERIFF) {
      if (deadRole == ImposterRole.INNOCENT) {
        // Sheriff killed innocent - sheriff dies too
        killerData.setDead(true);
        killer.setGameMode(GameMode.SPECTATOR);
        killer.sendMessage(
            Component.text("You killed an innocent and have been eliminated!", NamedTextColor.RED));

        // Drop sheriff magnifying glass for innocents to pick up
        dropSheriffMagnifyingGlass(killer.getLocation());

        broadcastMessage(
            killer.getName()
                + " (Sheriff) killed "
                + deadPlayer.getName()
                + " (Innocent) and was eliminated!",
            NamedTextColor.YELLOW);
      } else if (deadRole == ImposterRole.MURDERER) {
        broadcastMessage(
            killer.getName() + " (Sheriff) eliminated " + deadPlayer.getName() + " (Murderer)!",
            NamedTextColor.GREEN);
      }
    } else if (killerRole == ImposterRole.INNOCENT) {
      if (deadRole == ImposterRole.INNOCENT || deadRole == ImposterRole.SHERIFF) {
        // Innocent killed another innocent or sheriff - innocent dies too
        killerData.setDead(true);
        killer.setGameMode(GameMode.SPECTATOR);
        killer.sendMessage(
            Component.text(
                "You killed a fellow innocent/sheriff and have been eliminated!",
                NamedTextColor.RED));

        broadcastMessage(
            killer.getName()
                + " (Innocent) accidentally killed "
                + deadPlayer.getName()
                + " and was eliminated!",
            NamedTextColor.YELLOW);
      } else if (deadRole == ImposterRole.MURDERER) {
        broadcastMessage(
            killer.getName() + " (Innocent) eliminated " + deadPlayer.getName() + " (Murderer)!",
            NamedTextColor.GREEN);
      }
    } else if (killerRole == ImposterRole.MURDERER) {
      broadcastMessage(
          deadPlayer.getName() + " was eliminated by the murderer!", NamedTextColor.RED);
    }
  }

  /**
   * Creates a gravestone at the specified location.
   *
   * @param location The location to create the gravestone
   * @param playerName The name of the deceased player
   */
  private void createGravestone(Location location, String playerName) {
    // Use a skull or similar block as gravestone
    Block block = location.getBlock();
    block.setType(Material.PLAYER_HEAD);

    // Store gravestone data
    gravestones.add(location);
    gravestoneTimestamps.put(location, System.currentTimeMillis());
    gravestonePlayerNames.put(location, playerName);

    plugin.getLogger().info("Created gravestone for " + playerName + " at " + location);
  }

  /**
   * Drops a sheriff magnifying glass item for innocents to pick up.
   *
   * @param location The location to drop the item
   */
  private void dropSheriffMagnifyingGlass(Location location) {
    ItemStack magnifyingGlass = new ItemStack(Material.CLOCK);
    ItemMeta meta = magnifyingGlass.getItemMeta();
    meta.displayName(Component.text("Sheriff Magnifying Glass", NamedTextColor.GOLD));
    meta.lore(
        List.of(
            Component.text("Pick up to become the new Sheriff!", NamedTextColor.YELLOW),
            Component.text("Right-click gravestones to investigate", NamedTextColor.GRAY)));
    magnifyingGlass.setItemMeta(meta);

    location.getWorld().dropItem(location, magnifyingGlass);
  }

  /** Checks if any team has won the game. */
  private void checkWinConditions() {
    if (!gameActive) {
      return;
    }

    // Count alive players by role
    long aliveMurderers =
        playerData.values().stream()
            .filter(data -> !data.isDead() && data.getRole() == ImposterRole.MURDERER)
            .count();

    long aliveInnocents =
        playerData.values().stream()
            .filter(
                data ->
                    !data.isDead()
                        && (data.getRole() == ImposterRole.INNOCENT
                            || data.getRole() == ImposterRole.SHERIFF))
            .count();

    if (aliveMurderers == 0) {
      // All murderers eliminated - innocents/sheriff win
      endGame(ImposterRole.INNOCENT);
    } else if (aliveInnocents <= aliveMurderers) {
      // Murderers equal or outnumber innocents - murderers win
      endGame(ImposterRole.MURDERER);
    }
  }
}
