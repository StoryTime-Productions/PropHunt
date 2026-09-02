package com.storytimeproductions.prophunt.commands;

import com.storytimeproductions.prophunt.game.HiderClass;
import com.storytimeproductions.prophunt.game.HiderUtilityListener;
import com.storytimeproductions.prophunt.game.HuntDisguiseManager;
import com.storytimeproductions.prophunt.game.HuntGameMode;
import com.storytimeproductions.prophunt.game.HuntGameModeManager;
import com.storytimeproductions.prophunt.game.HuntGameModeStrategy;
import com.storytimeproductions.prophunt.game.HuntGameModeStrategyFactory;
import com.storytimeproductions.prophunt.game.HuntHologramManager;
import com.storytimeproductions.prophunt.game.HuntKitManager;
import com.storytimeproductions.prophunt.game.HuntLobbyManager;
import com.storytimeproductions.prophunt.game.HuntMap;
import com.storytimeproductions.prophunt.game.HuntPlayerData;
import com.storytimeproductions.prophunt.game.HuntPrepPhaseManager;
import com.storytimeproductions.prophunt.game.HuntTeam;
import com.storytimeproductions.prophunt.game.HunterClass;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import me.libraryaddict.disguise.DisguiseAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;

/**
 * Implements {@code /hunt} and its subcommands: opening the lobby, team/class/map selection
 * routing, ready-up, admin round control (start/end), and teleporting into the Hunt world. The
 * largest command in the plugin - it's the main entry point players interact with, and delegates to
 * {@link HuntLobbyManager}, {@link HuntKitManager}, {@link HuntDisguiseManager}, and {@link
 * HuntPrepPhaseManager} for the actual state changes.
 */
public class HuntCommand implements CommandExecutor {

  private final HuntLobbyManager lobbyManager;
  private final HuntHologramManager hologramManager;
  private final HuntKitManager kitManager;
  private final HuntDisguiseManager disguiseManager;
  private final HuntPrepPhaseManager prepPhaseManager;
  private final HiderUtilityListener hiderUtilityListener;
  private final HuntGameModeManager gameModeManager;
  private final JavaPlugin plugin;

  // Cooldown tracking for class switching (500ms cooldown)
  private final Map<UUID, Long> classJoinCooldowns = new HashMap<>();
  private static final long CLASS_JOIN_COOLDOWN_MS = 500;

  // Cooldown tracking for map voting (500ms cooldown)
  private final Map<UUID, Long> voteJoinCooldowns = new HashMap<>();
  private static final long VOTE_COOLDOWN_MS = 500;

  /**
   * Constructs a new HuntCommand with the given managers.
   *
   * @param plugin The JavaPlugin instance for accessing configuration
   * @param lobbyManager The HuntLobbyManager instance to use for lobby operations
   * @param hologramManager The HuntHologramManager instance for hologram operations
   * @param kitManager The HuntKitManager instance for kit operations
   * @param disguiseManager The HuntDisguiseManager instance for disguise operations
   * @param prepPhaseManager The HuntPrepPhaseManager instance for prep phase operations
   * @param hiderUtilityListener The HiderUtilityListener instance for clearing hider data
   * @param gameModeManager The HuntGameModeManager instance for game mode checks
   */
  public HuntCommand(
      JavaPlugin plugin,
      HuntLobbyManager lobbyManager,
      HuntHologramManager hologramManager,
      HuntKitManager kitManager,
      HuntDisguiseManager disguiseManager,
      HuntPrepPhaseManager prepPhaseManager,
      HiderUtilityListener hiderUtilityListener,
      HuntGameModeManager gameModeManager) {
    this.plugin = plugin;
    this.lobbyManager = lobbyManager;
    this.hologramManager = hologramManager;
    this.kitManager = kitManager;
    this.disguiseManager = disguiseManager;
    this.prepPhaseManager = prepPhaseManager;
    this.hiderUtilityListener = hiderUtilityListener;
    this.gameModeManager = gameModeManager;
  }

  /**
   * Handles the /hunt command and its subcommands.
   *
   * @param sender The command sender
   * @param command The command
   * @param label The command label
   * @param args The command arguments
   * @return true if the command was handled, false otherwise
   */
  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!(sender instanceof Player player)) {
      return true;
    }

    if (args.length == 0) {
      // Auto-start prep phase if not already active
      if (!prepPhaseManager.isPrepPhaseActive() && !prepPhaseManager.isGameStarting()) {
        prepPhaseManager.startPrepPhase();
        player.sendMessage(Component.text("Started Hunt prep phase!", NamedTextColor.GREEN));
      }

      teleportToHuntSpawn(player);
      return true;
    }

    switch (args[0].toLowerCase()) {
      case "lobby" -> {
        lobbyManager.openMainMenu(player);
        player.sendMessage(Component.text("Opening Hunt game lobby...", NamedTextColor.GREEN));
      }
      case "join" -> {
        if (args.length < 2) {
          player.sendMessage(Component.text("Usage: /hunt join <className>", NamedTextColor.RED));
          player.sendMessage(Component.text("Available classes:", NamedTextColor.YELLOW));
          player.sendMessage(
              Component.text("Hunters: brute, nimble, saboteur", NamedTextColor.WHITE));
          player.sendMessage(
              Component.text("Hiders: trickster, phaser, cloaker", NamedTextColor.WHITE));
          return true;
        }

        String className = args[1].toLowerCase();
        handleClassJoin(player, className);
      }
      case "map" -> {
        if (args.length < 2) {
          player.sendMessage(Component.text("Usage: /hunt map <mapName>", NamedTextColor.RED));
          player.sendMessage(Component.text("Available maps:", NamedTextColor.YELLOW));
          for (HuntMap map : HuntMap.values()) {
            player.sendMessage(
                Component.text("• " + map.name().toLowerCase(), NamedTextColor.WHITE));
          }
          return true;
        }

        String mapName = args[1].toLowerCase();
        handleMapVote(player, mapName);
      }
      case "prep" -> {
        if (args.length < 2) {
          player.sendMessage(Component.text("Usage:", NamedTextColor.YELLOW));
          player.sendMessage(
              Component.text("/hunt prep start - Start prep phase", NamedTextColor.WHITE));
          player.sendMessage(
              Component.text("/hunt prep end - End prep phase", NamedTextColor.WHITE));
          return true;
        }

        switch (args[1].toLowerCase()) {
          case "start" -> {
            prepPhaseManager.startPrepPhase();
            player.sendMessage(Component.text("Started Hunt prep phase!", NamedTextColor.GREEN));
          }
          case "end" -> {
            prepPhaseManager.endPrepPhase();
            player.sendMessage(Component.text("Ended Hunt prep phase!", NamedTextColor.YELLOW));
          }
          default -> {
            player.sendMessage(Component.text("Invalid prep command!", NamedTextColor.RED));
            player.sendMessage(Component.text("Use /hunt prep for help", NamedTextColor.YELLOW));
          }
        }
      }
      case "ready" -> {
        if (!prepPhaseManager.isPrepPhaseActive()) {
          player.sendMessage(
              Component.text("No prep phase is currently active!", NamedTextColor.RED));
          return true;
        }

        boolean ready = true;
        if (args.length > 1 && "false".equalsIgnoreCase(args[1])) {
          ready = false;
        }

        prepPhaseManager.setPlayerReady(player, ready);
      }
      case "vote" -> {
        if (!prepPhaseManager.isPrepPhaseActive()) {
          player.sendMessage(
              Component.text("No prep phase is currently active!", NamedTextColor.RED));
          return true;
        }

        if (args.length < 2) {
          player.sendMessage(Component.text("Usage: /hunt vote <mapName>", NamedTextColor.RED));
          return true;
        }

        String mapName = args[1].toLowerCase();
        for (HuntMap map : HuntMap.values()) {
          if (map.name().toLowerCase().equals(mapName)) {
            prepPhaseManager.handleMapVote(player, map);
            return true;
          }
        }

        player.sendMessage(Component.text("Unknown map: " + mapName, NamedTextColor.RED));
      }
      case "start" -> {
        if (!prepPhaseManager.isPrepPhaseActive()) {
          sender.sendMessage(
              Component.text("No prep phase is currently active!", NamedTextColor.RED));
          return true;
        }

        // Check if the game can start based on current game mode requirements
        if (!canGameStart(player)) {
          return true; // Error message already sent by canGameStart
        }

        prepPhaseManager.attemptGameStart();
      }
      case "end" -> {
        if (!sender.hasPermission("hunt.admin")) {
          sender.sendMessage(
              Component.text("You don't have permission to end the hunt.", NamedTextColor.RED));
          return true;
        }
        prepPhaseManager.forceEndGame(sender);
      }
      case "leave" -> {
        if (args.length < 2) {
          player.sendMessage(Component.text("Usage:", NamedTextColor.YELLOW));
          player.sendMessage(
              Component.text("/hunt leave lobby - Leave the Hunt lobby", NamedTextColor.WHITE));
          player.sendMessage(
              Component.text("/hunt leave class - Leave your current class", NamedTextColor.WHITE));
          player.sendMessage(
              Component.text("/hunt leave map - Remove your map vote", NamedTextColor.WHITE));
          return true;
        }

        switch (args[1].toLowerCase()) {
          case "lobby" -> {
            lobbyManager.removePlayer(player.getUniqueId());
            player.closeInventory();
            // Clean up cooldowns
            classJoinCooldowns.remove(player.getUniqueId());
            voteJoinCooldowns.remove(player.getUniqueId());
            player.sendMessage(Component.text("Left the Hunt game lobby.", NamedTextColor.YELLOW));
          }
          case "class" -> {
            handleLeaveClass(player);
          }
          case "map" -> {
            handleLeaveMap(player);
          }
          default -> {
            player.sendMessage(Component.text("Invalid leave command!", NamedTextColor.RED));
            player.sendMessage(Component.text("Use /hunt leave for help", NamedTextColor.YELLOW));
          }
        }
      }
      case "status" -> {
        var data = lobbyManager.getPlayerData(player.getUniqueId());
        if (data == null) {
          player.sendMessage(Component.text("You are not in the Hunt lobby.", NamedTextColor.RED));
        } else {
          player.sendMessage(Component.text("=== Hunt Lobby Status ===", NamedTextColor.GOLD));
          player.sendMessage(
              Component.text(
                  "Team: "
                      + (data.getSelectedTeam() != null
                          ? data.getSelectedTeam().getDisplayName()
                          : "None"),
                  NamedTextColor.WHITE));

          if (data.getSelectedTeam() != null) {
            String className = "None";
            if (data.getSelectedTeam().name().equals("HUNTERS")
                && data.getSelectedHunterClass() != null) {
              className = data.getSelectedHunterClass().getDisplayName();
            } else if (data.getSelectedTeam().name().equals("HIDERS")
                && data.getSelectedHiderClass() != null) {
              className = data.getSelectedHiderClass().getDisplayName();
            }
            player.sendMessage(Component.text("Class: " + className, NamedTextColor.WHITE));
          }

          player.sendMessage(
              Component.text(
                  "Map: "
                      + (data.getPreferredMap() != null
                          ? data.getPreferredMap().getDisplayName()
                          : "None"),
                  NamedTextColor.WHITE));
          player.sendMessage(
              Component.text(
                  "Game Mode: "
                      + (data.getPreferredGameMode() != null
                          ? data.getPreferredGameMode().getDisplayName()
                          : "None"),
                  NamedTextColor.WHITE));
          player.sendMessage(
              Component.text(
                  "Ready: " + (data.isReady() ? "Yes" : "No"),
                  data.isReady() ? NamedTextColor.GREEN : NamedTextColor.RED));
        }
      }
      case "wipetext" -> {
        try {
          File huntConfigFile = new File(plugin.getDataFolder(), "hunt.yml");
          FileConfiguration huntConfig = YamlConfiguration.loadConfiguration(huntConfigFile);
          String worldName = huntConfig.getString("hunt.world", "hunt");
          org.bukkit.World huntWorld = Bukkit.getWorld(worldName);
          if (huntWorld == null) {
            player.sendMessage(
                Component.text("Hunt world '" + worldName + "' not found!", NamedTextColor.RED));
            return true;
          }
          huntWorld
              .getEntitiesByClass(org.bukkit.entity.TextDisplay.class)
              .forEach(org.bukkit.entity.Entity::remove);
          hologramManager.initialize(huntConfig);
          player.sendMessage(
              Component.text(
                  "Wiped all text entities and re-spawned holograms.", NamedTextColor.GREEN));
        } catch (Exception e) {
          player.sendMessage(
              Component.text(
                  "Failed to wipe text entities: " + e.getMessage(), NamedTextColor.RED));
        }
      }
      case "disguise" -> {
        if (args.length < 2) {
          player.sendMessage(Component.text("Usage:", NamedTextColor.YELLOW));
          player.sendMessage(
              Component.text("/hunt disguise remove - Remove your disguise", NamedTextColor.WHITE));
          return true;
        }

        switch (args[1].toLowerCase()) {
          case "remove" -> {
            // Block disguise removal in Imposter Hunt mode
            if (gameModeManager.getCurrentGameMode() == HuntGameMode.IMPOSTER_HUNT) {
              player.sendMessage(
                  Component.text(
                      "Disguise commands are not available in Imposter Hunt mode!",
                      NamedTextColor.RED));
              return true;
            }
            disguiseManager.removeDisguise(player);
            player.sendMessage(Component.text("Removed your disguise!", NamedTextColor.YELLOW));
          }
          case "reload" -> {
            player.sendMessage(
                Component.text(
                    "Disguise NPCs reload automatically on world join.", NamedTextColor.YELLOW));
          }
          default -> {
            player.sendMessage(Component.text("Invalid disguise command!", NamedTextColor.RED));
            player.sendMessage(
                Component.text("Use /hunt disguise for help", NamedTextColor.YELLOW));
          }
        }
      }
      default -> {
        player.sendMessage(Component.text("Usage:", NamedTextColor.YELLOW));
        player.sendMessage(
            Component.text("/hunt - Teleport to hunt world spawn", NamedTextColor.WHITE));
        player.sendMessage(
            Component.text("/hunt lobby - Open the Hunt lobby", NamedTextColor.WHITE));
        player.sendMessage(
            Component.text("/hunt join <className> - Join a class", NamedTextColor.WHITE));
        player.sendMessage(
            Component.text("/hunt map <mapName> - Vote for a map", NamedTextColor.WHITE));
        player.sendMessage(
            Component.text("/hunt prep start/end - Manage prep phase", NamedTextColor.WHITE));
        player.sendMessage(
            Component.text("/hunt ready [true/false] - Set ready status", NamedTextColor.WHITE));
        player.sendMessage(
            Component.text(
                "/hunt vote <mapName> - Vote for map (prep phase)", NamedTextColor.WHITE));
        player.sendMessage(
            Component.text("/hunt start - Start game (prep phase)", NamedTextColor.WHITE));
        player.sendMessage(
            Component.text("/hunt leave lobby - Leave the Hunt lobby", NamedTextColor.WHITE));
        player.sendMessage(
            Component.text("/hunt leave class - Leave your current class", NamedTextColor.WHITE));
        player.sendMessage(
            Component.text("/hunt leave map - Remove your map vote", NamedTextColor.WHITE));
        player.sendMessage(
            Component.text("/hunt status - Check your lobby status", NamedTextColor.WHITE));
        player.sendMessage(
            Component.text("/hunt disguise - Manage disguise system", NamedTextColor.WHITE));
        player.sendMessage(
            Component.text(
                "/hunt wipetext - Wipe all text displays and re-spawn holograms",
                NamedTextColor.WHITE));
      }
    }

    return true;
  }

  /**
   * Handles a player joining a specific class.
   *
   * @param player The player joining the class
   * @param className The name of the class to join
   */
  public void handleClassJoin(Player player, String className) {
    // Block class joining in Imposter Hunt mode
    if (gameModeManager.getCurrentGameMode() == HuntGameMode.IMPOSTER_HUNT) {
      player.sendMessage(
          Component.text(
              "Class selection is not available in Imposter Hunt mode!", NamedTextColor.RED));
      return;
    }

    // Check cooldown first
    UUID playerId = player.getUniqueId();
    long currentTime = System.currentTimeMillis();

    if (classJoinCooldowns.containsKey(playerId)) {
      long lastJoinTime = classJoinCooldowns.get(playerId);
      long timeSinceLastJoin = currentTime - lastJoinTime;

      if (timeSinceLastJoin < CLASS_JOIN_COOLDOWN_MS) {
        return;
      }
    }

    // Try to match hunter classes first
    for (HunterClass hunterClass : HunterClass.values()) {
      if (hunterClass.name().toLowerCase().equals(className)) {
        String hologramId = "hunter_" + className.toLowerCase();

        // Store the current state before calling the hologram manager
        String classBefore = hologramManager.getPlayerClassSelection(player.getUniqueId());

        // Add to class (or remove if already in it) - hologram manager handles the
        // logic
        hologramManager.addPlayerToClass(player.getUniqueId(), player.getName(), className, true);

        // Check if the state actually changed to determine what message to send
        String classAfter = hologramManager.getPlayerClassSelection(player.getUniqueId());

        // Only send a message if the state actually changed
        if (!java.util.Objects.equals(classBefore, classAfter)) {
          // Update cooldown since the state changed
          classJoinCooldowns.put(playerId, currentTime);

          if (hologramId.equals(classAfter)) {
            // Player joined the hunter class - update lobby manager and give kit
            HuntPlayerData playerData = lobbyManager.getOrCreatePlayerData(player.getUniqueId());

            // Check if player was previously a hider and remove any block disguises
            if (playerData.getSelectedTeam() == HuntTeam.HIDERS) {
              // Remove LibsDisguises block disguise if present
              if (DisguiseAPI.isDisguised(player)) {
                DisguiseAPI.undisguiseToAll(player);
                player.sendMessage(
                    Component.text("Removed your block disguise!", NamedTextColor.YELLOW));
              }

              // Clear hider cooldowns and stored data
              hiderUtilityListener.clearPlayerCooldowns(player.getUniqueId());
            }

            playerData.setSelectedTeam(HuntTeam.HUNTERS);
            playerData.setSelectedHunterClass(hunterClass);

            // Remove any existing kit first, then give new kit
            kitManager.removePlayerKit(player);
            kitManager.giveHunterKit(player, hunterClass);
            player.sendMessage(
                Component.text(
                    "Joined " + hunterClass.getDisplayName() + " (Hunter)", NamedTextColor.GREEN));

            // Update prep phase holograms if prep phase is active
            prepPhaseManager.updatePlayerClassSelection(
                player.getUniqueId(), HuntTeam.HUNTERS, hunterClass);
          } else {
            // Player left the class - update lobby manager and remove kit
            HuntPlayerData playerData = lobbyManager.getPlayerData(player.getUniqueId());
            if (playerData != null) {
              playerData.setSelectedTeam(null);
              playerData.setSelectedHunterClass(null);
            }

            kitManager.removePlayerKit(player);
            player.sendMessage(
                Component.text(
                    "Left " + hunterClass.getDisplayName() + " (Hunter)", NamedTextColor.YELLOW));

            // Update prep phase holograms if prep phase is active
            prepPhaseManager.updatePlayerClassSelection(player.getUniqueId(), null, null);
          }
        }
        return;
      }
    }

    // Try to match hider classes
    for (HiderClass hiderClass : HiderClass.values()) {
      if (hiderClass.name().toLowerCase().equals(className)) {
        String hologramId = "hider_" + className.toLowerCase();

        // Store the current state before calling the hologram manager
        String classBefore = hologramManager.getPlayerClassSelection(player.getUniqueId());

        // Add to class (or remove if already in it) - hologram manager handles the
        // logic
        hologramManager.addPlayerToClass(player.getUniqueId(), player.getName(), className, false);

        // Check if the state actually changed to determine what message to send
        String classAfter = hologramManager.getPlayerClassSelection(player.getUniqueId());

        // Only send a message if the state actually changed
        if (!java.util.Objects.equals(classBefore, classAfter)) {
          // Update cooldown since the state changed
          classJoinCooldowns.put(playerId, currentTime);

          if (hologramId.equals(classAfter)) {
            // Player joined the hider class - update lobby manager and give kit
            HuntPlayerData playerData = lobbyManager.getOrCreatePlayerData(player.getUniqueId());

            // Check if player was previously a hunter and remove any hunter disguises
            if (playerData.getSelectedTeam() == HuntTeam.HUNTERS) {
              // Remove hunter disguise (using disguise manager)
              disguiseManager.removeDisguise(player);
              player.sendMessage(
                  Component.text("Removed your hunter disguise!", NamedTextColor.YELLOW));
            }

            playerData.setSelectedTeam(HuntTeam.HIDERS);
            playerData.setSelectedHiderClass(hiderClass);

            // Remove any existing kit first, then give new kit
            kitManager.removePlayerKit(player);
            kitManager.giveHiderKit(player, hiderClass);
            player.sendMessage(
                Component.text(
                    "Joined " + hiderClass.getDisplayName() + " (Hider)", NamedTextColor.GREEN));

            // Update prep phase holograms if prep phase is active
            prepPhaseManager.updatePlayerClassSelection(
                player.getUniqueId(), HuntTeam.HIDERS, hiderClass);
          } else {
            // Player left the class - update lobby manager and remove kit
            HuntPlayerData playerData = lobbyManager.getPlayerData(player.getUniqueId());
            if (playerData != null) {
              playerData.setSelectedTeam(null);
              playerData.setSelectedHiderClass(null);
            }

            kitManager.removePlayerKit(player);
            player.sendMessage(
                Component.text(
                    "Left " + hiderClass.getDisplayName() + " (Hider)", NamedTextColor.YELLOW));

            // Update prep phase holograms if prep phase is active
            prepPhaseManager.updatePlayerClassSelection(player.getUniqueId(), null, null);
          }
        }
        return;
      }
    }

    // Class not found
    player.sendMessage(Component.text("Unknown class: " + className, NamedTextColor.RED));
    player.sendMessage(Component.text("Available classes:", NamedTextColor.YELLOW));
    player.sendMessage(Component.text("Hunters: brute, nimble, saboteur", NamedTextColor.WHITE));
    player.sendMessage(Component.text("Hiders: trickster, phaser, cloaker", NamedTextColor.WHITE));
  }

  /**
   * Handles a player voting for a map.
   *
   * @param player The player voting
   * @param mapName The name of the map to vote for
   */
  public void handleMapVote(Player player, String mapName) {
    // Check vote cooldown
    UUID playerId = player.getUniqueId();
    long currentTime = System.currentTimeMillis();

    if (voteJoinCooldowns.containsKey(playerId)) {
      long lastVoteTime = voteJoinCooldowns.get(playerId);
      if (currentTime - lastVoteTime < VOTE_COOLDOWN_MS) {
        // Still on cooldown, ignore the vote
        return;
      }
    }

    // Update cooldown
    voteJoinCooldowns.put(playerId, currentTime);

    // Try to match the map
    for (HuntMap map : HuntMap.values()) {
      if (map.name().toLowerCase().equals(mapName)) {
        String hologramId = "map_" + mapName.toLowerCase();

        // Store the current state before calling the hologram manager
        String mapBefore = hologramManager.getPlayerMapVote(player.getUniqueId());

        // Always call the hologram manager - it will handle the toggle logic internally
        hologramManager.addPlayerToMap(player.getUniqueId(), player.getName(), mapName);

        // Check if the state actually changed to determine what message to send
        String mapAfter = hologramManager.getPlayerMapVote(player.getUniqueId());

        // Update prep phase manager as well
        if (hologramId.equals(mapAfter)) {
          // Player voted for the map - notify prep phase manager
          prepPhaseManager.handleMapVote(player, map);
        } else if (mapBefore != null && mapBefore.equals(hologramId)) {
          // Player removed their vote for this map - notify prep phase manager
          prepPhaseManager.removeMapVote(player);
        }

        // Only send a message if the state actually changed
        if (!java.util.Objects.equals(mapBefore, mapAfter)) {
          if (hologramId.equals(mapAfter)) {
            // Player voted for the map (message already sent by prep phase manager)
            // player.sendMessage already called by prepPhaseManager.handleMapVote
          } else {
            // Player removed their vote for the map (message already sent by prep phase
            // manager)
            // player.sendMessage already called by prepPhaseManager.removeMapVote
          }
        }
        return;
      }
    }

    // Map not found
    player.sendMessage(Component.text("Unknown map: " + mapName, NamedTextColor.RED));
    player.sendMessage(Component.text("Available maps:", NamedTextColor.YELLOW));
    for (HuntMap map : HuntMap.values()) {
      player.sendMessage(Component.text("• " + map.name().toLowerCase(), NamedTextColor.WHITE));
    }
  }

  /**
   * Handles a player leaving their current class.
   *
   * @param player The player leaving their class
   */
  private void handleLeaveClass(Player player) {
    String currentClass = hologramManager.getPlayerClassSelection(player.getUniqueId());
    if (currentClass == null) {
      player.sendMessage(Component.text("You are not in any class!", NamedTextColor.RED));
      return;
    }

    // Remove player from their current class
    boolean removed = hologramManager.removePlayerFromClass(player.getUniqueId());
    if (removed) {
      // Remove their kit and handle disguise removal
      kitManager.removePlayerKit(player);

      // Handle disguise removal if player was in a hunter class
      if (disguiseManager != null && currentClass.startsWith("hunter_")) {
        disguiseManager.handleClassChange(player, currentClass, null);
      }

      // Determine class name for message
      String className = "Unknown";
      String teamType = "Unknown";
      if (currentClass.startsWith("hunter_")) {
        String classKey = currentClass.substring("hunter_".length()).toUpperCase();
        try {
          HunterClass hunterClass = HunterClass.valueOf(classKey);
          className = hunterClass.getDisplayName();
          teamType = "Hunter";
        } catch (IllegalArgumentException e) {
          // Fallback
        }
      } else if (currentClass.startsWith("hider_")) {
        String classKey = currentClass.substring("hider_".length()).toUpperCase();
        try {
          HiderClass hiderClass = HiderClass.valueOf(classKey);
          className = hiderClass.getDisplayName();
          teamType = "Hider";
        } catch (IllegalArgumentException e) {
          // Fallback
        }
      }

      player.sendMessage(
          Component.text("Left " + className + " (" + teamType + ")", NamedTextColor.YELLOW));
    } else {
      player.sendMessage(Component.text("Failed to leave class!", NamedTextColor.RED));
    }
  }

  /**
   * Handles a player removing their map vote.
   *
   * @param player The player removing their map vote
   */
  private void handleLeaveMap(Player player) {
    String currentMap = hologramManager.getPlayerMapVote(player.getUniqueId());
    if (currentMap == null) {
      player.sendMessage(Component.text("You haven't voted for any map!", NamedTextColor.RED));
      return;
    }

    // Remove player from their current map vote (hologram system)
    boolean removed = hologramManager.removePlayerFromMapVote(player.getUniqueId());

    // Also remove from prep phase manager
    prepPhaseManager.removeMapVote(player);

    if (!removed) {
      player.sendMessage(Component.text("Failed to remove map vote!", NamedTextColor.RED));
    }
  }

  /**
   * Teleports a player to the hunt world spawn location as defined in hunt.yml.
   *
   * @param player The player to teleport
   */
  private void teleportToHuntSpawn(Player player) {
    try {
      File huntConfigFile = new File(plugin.getDataFolder(), "hunt.yml");
      if (!huntConfigFile.exists()) {
        player.sendMessage(Component.text("Hunt configuration not found!", NamedTextColor.RED));
        return;
      }

      FileConfiguration huntConfig = YamlConfiguration.loadConfiguration(huntConfigFile);

      String worldName = huntConfig.getString("hunt.world", "world");
      World world = Bukkit.getWorld(worldName);
      if (world == null) {
        player.sendMessage(
            Component.text("Hunt world '" + worldName + "' not found!", NamedTextColor.RED));
        return;
      }

      double x = huntConfig.getDouble("hunt.spawn.x", 0.0);
      double y = huntConfig.getDouble("hunt.spawn.y", 65.0);
      double z = huntConfig.getDouble("hunt.spawn.z", 0.0);
      float yaw = (float) huntConfig.getDouble("hunt.spawn.yaw", 0.0);
      float pitch = (float) huntConfig.getDouble("hunt.spawn.pitch", 0.0);

      clearPlayerForHuntLobby(player);
      Location spawnLocation = new Location(world, x, y, z, yaw, pitch);
      // Adventure mode was never enforced on lobby entry - a player joining fresh (or still
      // in whatever mode they were in before) would stay in Survival/Creative in the lobby.
      player.setGameMode(GameMode.ADVENTURE);
      player.teleport(spawnLocation);
      // Delay to override any world manager (e.g. Multiverse) position restoration on world change
      Bukkit.getScheduler()
          .runTaskLater(
              plugin,
              () -> {
                if (player.isOnline()) {
                  player.teleport(spawnLocation);
                  // Clear kit again after world-change handlers (e.g. Multiverse-Inventories)
                  // have had a chance to restore the per-world inventory
                  kitManager.removePlayerKit(player);
                }
              },
              2L);
      player.sendMessage(Component.text("Welcome to the Hunt!", NamedTextColor.GREEN));

    } catch (Exception e) {
      player.sendMessage(Component.text("Failed to teleport to hunt spawn!", NamedTextColor.RED));
      plugin.getLogger().warning("Failed to teleport player to hunt spawn: " + e.getMessage());
    }
  }

  /**
   * Clears all kits, disguises, potion effects, and resets player state for entering Hunt lobby.
   *
   * @param player The player to clean up
   */
  private void clearPlayerForHuntLobby(Player player) {
    try {
      // Remove any active disguises
      if (DisguiseAPI.isDisguised(player)) {
        DisguiseAPI.undisguiseToAll(player);
      }

      // Remove all potion effects
      for (PotionEffect effect : player.getActivePotionEffects()) {
        player.removePotionEffect(effect.getType());
      }

      org.bukkit.attribute.AttributeInstance scaleAttr =
          player.getAttribute(org.bukkit.attribute.Attribute.SCALE);
      if (scaleAttr != null) {
        scaleAttr.setBaseValue(1.0);
      }

      // Clear inventory items from other games (keep player's normal items)
      // Note: We don't clear the entire inventory as players should keep their
      // personal items
      kitManager.removePlayerKit(player);

      // Clear any hider utility data if the player had hider abilities active
      if (hiderUtilityListener != null) {
        hiderUtilityListener.clearPlayerCooldowns(player.getUniqueId());
      }

      plugin.getLogger().info("Cleared " + player.getName() + " for Hunt lobby entry");

    } catch (Exception e) {
      plugin
          .getLogger()
          .warning(
              "Failed to clear player " + player.getName() + " for Hunt lobby: " + e.getMessage());
    }
  }

  /**
   * Checks if the game can start based on current game mode requirements and provides detailed
   * feedback to the player.
   *
   * @param player The player attempting to start the game
   * @return true if the game can start, false otherwise
   */
  private boolean canGameStart(Player player) {
    HuntGameMode currentMode = gameModeManager.getCurrentGameMode();
    HuntGameModeStrategy strategy = HuntGameModeStrategyFactory.getStrategy(currentMode);

    // Count ready players who meet the requirements for this game mode
    int readyPlayers = 0;
    int totalPlayersWithRequirements = 0;
    java.util.List<String> unreadyPlayers = new java.util.ArrayList<>();

    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
      UUID playerId = onlinePlayer.getUniqueId();
      HuntPlayerData data = lobbyManager.getPlayerData(playerId);

      if (data != null) {
        // Check if player meets the requirements for this gamemode
        HuntGameModeStrategy.ReadyResult readyResult =
            strategy.canPlayerReady(
                onlinePlayer, data, prepPhaseManager.getPlayerMapVotes(), hologramManager);

        if (readyResult.canReady()) {
          totalPlayersWithRequirements++;

          // Check if they're actually ready using the prep phase ready status
          if (Boolean.TRUE.equals(prepPhaseManager.getPlayerReadyStatus(playerId))) {
            readyPlayers++;
          } else {
            unreadyPlayers.add(onlinePlayer.getName());
          }
        }
      }
    }

    // Check minimum players requirement
    int minimumPlayers = strategy.getMinimumPlayers();
    if (totalPlayersWithRequirements < minimumPlayers) {
      player.sendMessage(
          Component.text(
              "Not enough players meet the requirements for " + currentMode.getDisplayName() + "!",
              NamedTextColor.RED));
      player.sendMessage(
          Component.text(
              "Need "
                  + minimumPlayers
                  + " players, but only "
                  + totalPlayersWithRequirements
                  + " meet the requirements.",
              NamedTextColor.YELLOW));
      return false;
    }

    // Check if all eligible players are ready
    if (readyPlayers < totalPlayersWithRequirements) {
      player.sendMessage(
          Component.text(
              "Not all players are ready! ("
                  + readyPlayers
                  + "/"
                  + totalPlayersWithRequirements
                  + ")",
              NamedTextColor.RED));

      if (!unreadyPlayers.isEmpty()) {
        player.sendMessage(
            Component.text(
                "Unready players: " + String.join(", ", unreadyPlayers), NamedTextColor.YELLOW));
      }
      return false;
    }

    return true;
  }
}
