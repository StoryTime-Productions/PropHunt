package com.storytimeproductions.prophunt.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Manages holograms for the Hunt game system using Text Display entities. */
public class HuntHologramManager {

  private final JavaPlugin plugin;
  private final NamespacedKey hologramKey;
  private final NamespacedKey hologramIdKey;
  private final Map<String, TextDisplay> displays = new HashMap<>();
  private final Map<String, Interaction> interactions = new HashMap<>();
  private final Map<String, List<String>> hologramPlayers = new HashMap<>();
  private final Map<UUID, String> playerClassSelections = new HashMap<>();
  private final Map<UUID, String> playerMapVotes = new HashMap<>();
  private final Map<UUID, Boolean> playerClassFlags = new HashMap<>();
  private final Map<UUID, Boolean> playerMapFlags = new HashMap<>();
  private final Map<UUID, String> playerNames = new HashMap<>();
  private HuntDisguiseManager disguiseManager;

  /**
   * Constructs a new HuntHologramManager.
   *
   * @param plugin The JavaPlugin instance
   */
  public HuntHologramManager(JavaPlugin plugin) {
    this.plugin = plugin;
    this.hologramKey = new NamespacedKey(plugin, "hunt_hologram");
    this.hologramIdKey = new NamespacedKey(plugin, "hunt_hologram_id");
  }

  /**
   * Spawns all Text Display entities at configured locations and sets initial titles. Must be
   * called after the target world is loaded.
   *
   * @param huntConfig The hunt.yml configuration
   */
  public void initialize(FileConfiguration huntConfig) {
    String worldName = huntConfig.getString("hunt.world", "hunt");
    World world = Bukkit.getWorld(worldName);
    if (world == null) {
      plugin.getLogger().warning("Hunt world '" + worldName + "' not found — holograms skipped");
      return;
    }

    // Wipe ALL text displays and hologram-tagged interaction entities before respawning
    world.getEntitiesByClass(TextDisplay.class).forEach(Entity::remove);
    world.getEntitiesByClass(Interaction.class).stream()
        .filter(e -> e.getPersistentDataContainer().has(hologramKey, PersistentDataType.BYTE))
        .forEach(Entity::remove);
    displays.clear();
    interactions.clear();

    for (HunterClass c : HunterClass.values()) {
      String id = "hunter_" + c.name().toLowerCase();
      spawnFromConfig(
          id, world, huntConfig, "hunt.holograms.hunter-classes." + c.name().toLowerCase());
    }

    for (HiderClass c : HiderClass.values()) {
      String id = "hider_" + c.name().toLowerCase();
      spawnFromConfig(
          id, world, huntConfig, "hunt.holograms.hider-classes." + c.name().toLowerCase());
    }

    for (HuntMap m : HuntMap.values()) {
      String id = "map_" + m.name().toLowerCase();
      spawnFromConfig(
          id, world, huntConfig, "hunt.prep-phase.map-vote-holograms." + m.name().toLowerCase());
    }

    spawnFromConfig("start_game", world, huntConfig, "hunt.prep-phase.start-hologram");
    spawnFromConfig("ready_status", world, huntConfig, "hunt.prep-phase.ready-status");
    spawnFromConfig("gamemode_selection", world, huntConfig, "hunt.holograms.gamemode-selection");

    // Delay title initialization so metadata packets are sent after the spawn packets
    Bukkit.getScheduler().runTaskLater(plugin, this::initializeClassHologramTitles, 2L);
    plugin.getLogger().info("Hunt holograms initialized");
  }

  private void spawnFromConfig(String id, World world, FileConfiguration config, String path) {
    ConfigurationSection section = config.getConfigurationSection(path);
    if (section == null) {
      plugin.getLogger().warning("No config at '" + path + "' — skipping hologram: " + id);
      return;
    }
    Location loc =
        new Location(world, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"));

    // Force the chunk to stay loaded so the entities are never cleaned up server-side. Relying on
    // setPersistent(true) alone is not enough on this Paper build - EDEN's CorridorDisplayManager
    // hit the identical issue with its own Display entities and needed this same chunk ticket.
    loc.getChunk().addPluginChunkTicket(plugin);

    TextDisplay display =
        world.spawn(
            loc,
            TextDisplay.class,
            d -> {
              d.setBillboard(Display.Billboard.VERTICAL);
              d.setAlignment(TextDisplay.TextAlignment.CENTER);
              d.setDefaultBackground(false);
              d.setSeeThrough(false);
              // Persistent so chunk unload/reload doesn't silently discard the hologram before a
              // player ever sees it - initialize() already wipes and respawns every hologram on
              // enable, so persistence can't create stale duplicates.
              d.setPersistent(true);
              d.getPersistentDataContainer().set(hologramKey, PersistentDataType.BYTE, (byte) 1);
            });
    displays.put(id, display);

    Interaction interaction =
        world.spawn(
            loc,
            Interaction.class,
            i -> {
              i.setInteractionWidth(1.0f);
              i.setInteractionHeight(0.5f);
              i.setPersistent(true);
              i.getPersistentDataContainer().set(hologramKey, PersistentDataType.BYTE, (byte) 1);
              i.getPersistentDataContainer().set(hologramIdKey, PersistentDataType.STRING, id);
            });
    interactions.put(id, interaction);
  }

  /**
   * Returns the hologram ID stored in an Interaction entity's PDC, or null if not a hologram.
   *
   * @param entity The entity that was interacted with
   * @return The hologram ID string, or null
   */
  public String getHologramIdForInteraction(Entity entity) {
    if (!(entity instanceof Interaction)) {
      return null;
    }
    return entity.getPersistentDataContainer().get(hologramIdKey, PersistentDataType.STRING);
  }

  /** Resets all player tracking and refreshes every class/map hologram to show just its title. */
  public void initializeClassHologramTitles() {
    hologramPlayers.clear();
    playerClassSelections.clear();
    playerMapVotes.clear();
    playerClassFlags.clear();
    playerMapFlags.clear();
    playerNames.clear();

    for (HunterClass c : HunterClass.values()) {
      refreshDisplay("hunter_" + c.name().toLowerCase());
    }
    for (HiderClass c : HiderClass.values()) {
      refreshDisplay("hider_" + c.name().toLowerCase());
    }
    for (HuntMap m : HuntMap.values()) {
      refreshDisplay("map_" + m.name().toLowerCase());
    }

    plugin.getLogger().info("Hunt hologram titles initialized");
  }

  /**
   * Adds a player to a class hologram, removing them from any prior class first.
   *
   * @param playerId The UUID of the player
   * @param playerName The display name of the player
   * @param className The class name (e.g. "brute", "trickster")
   * @param isHunter True for hunter classes, false for hider classes
   */
  public void addPlayerToClass(
      UUID playerId, String playerName, String className, boolean isHunter) {
    Boolean flag = playerClassFlags.get(playerId);
    if (Boolean.TRUE.equals(flag)) {
      playerClassFlags.put(playerId, false);
      return;
    }
    playerClassFlags.put(playerId, true);

    String hologramId = (isHunter ? "hunter_" : "hider_") + className.toLowerCase();
    String currentClass = playerClassSelections.get(playerId);

    if (hologramId.equals(currentClass)) {
      removePlayerFromCurrentClass(playerId);
      if (disguiseManager != null) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
          disguiseManager.handleClassChange(player, currentClass, null);
        }
      }
      playerClassFlags.put(playerId, false);
      return;
    }

    removePlayerFromCurrentClass(playerId);
    if (disguiseManager != null) {
      Player player = Bukkit.getPlayer(playerId);
      if (player != null) {
        disguiseManager.handleClassChange(player, currentClass, hologramId);
      }
    }

    playerNames.put(playerId, playerName);
    hologramPlayers.computeIfAbsent(hologramId, k -> new ArrayList<>()).add(playerName);
    playerClassSelections.put(playerId, hologramId);
    refreshDisplay(hologramId);
    playerClassFlags.put(playerId, false);
  }

  /**
   * Adds a player's map vote to the appropriate map hologram.
   *
   * @param playerId The UUID of the player
   * @param playerName The display name of the player
   * @param mapName The map name (e.g. "castle", "backrooms")
   */
  public void addPlayerToMap(UUID playerId, String playerName, String mapName) {
    Boolean flag = playerMapFlags.get(playerId);
    if (Boolean.TRUE.equals(flag)) {
      playerMapFlags.put(playerId, false);
      return;
    }
    playerMapFlags.put(playerId, true);

    String hologramId = "map_" + mapName.toLowerCase();
    String currentMap = playerMapVotes.get(playerId);

    if (hologramId.equals(currentMap)) {
      removePlayerFromCurrentMap(playerId);
      playerMapFlags.put(playerId, false);
      return;
    }

    removePlayerFromCurrentMap(playerId);
    playerNames.put(playerId, playerName);
    hologramPlayers.computeIfAbsent(hologramId, k -> new ArrayList<>()).add(playerName);
    playerMapVotes.put(playerId, hologramId);
    refreshDisplay(hologramId);
    playerMapFlags.put(playerId, false);
  }

  private void removePlayerFromCurrentClass(UUID playerId) {
    String currentClass = playerClassSelections.remove(playerId);
    if (currentClass != null) {
      String name = playerNames.get(playerId);
      if (name != null) {
        List<String> players = hologramPlayers.get(currentClass);
        if (players != null) {
          players.remove(name);
        }
      }
      refreshDisplay(currentClass);
    }
  }

  private void removePlayerFromCurrentMap(UUID playerId) {
    String currentMap = playerMapVotes.remove(playerId);
    if (currentMap != null) {
      String name = playerNames.get(playerId);
      if (name != null) {
        List<String> players = hologramPlayers.get(currentMap);
        if (players != null) {
          players.remove(name);
        }
      }
      refreshDisplay(currentMap);
    }
  }

  private void refreshDisplay(String hologramId) {
    TextDisplay display = displays.get(hologramId);
    if (display == null || !display.isValid()) {
      return;
    }

    Component text = buildTitle(hologramId);
    List<String> players = hologramPlayers.getOrDefault(hologramId, List.of());
    for (String name : players) {
      // CHECKSTYLE:OFF: AvoidEscapedUnicodeCharacters
      text =
          text.append(Component.newline())
              .append(Component.text("• " + name, NamedTextColor.WHITE));
      // CHECKSTYLE:ON: AvoidEscapedUnicodeCharacters
    }
    display.text(text);
  }

  private Component buildTitle(String hologramId) {
    if (hologramId.startsWith("hunter_")) {
      String key = hologramId.substring("hunter_".length());
      for (HunterClass c : HunterClass.values()) {
        if (c.name().toLowerCase().equals(key)) {
          return Component.text(c.getDisplayName(), NamedTextColor.RED, TextDecoration.BOLD);
        }
      }
    } else if (hologramId.startsWith("hider_")) {
      String key = hologramId.substring("hider_".length());
      for (HiderClass c : HiderClass.values()) {
        if (c.name().toLowerCase().equals(key)) {
          return Component.text(c.getDisplayName(), NamedTextColor.BLUE, TextDecoration.BOLD);
        }
      }
    } else if (hologramId.startsWith("map_")) {
      String key = hologramId.substring("map_".length());
      for (HuntMap m : HuntMap.values()) {
        if (m.name().toLowerCase().equals(key)) {
          return Component.text(m.getDisplayName(), NamedTextColor.YELLOW, TextDecoration.BOLD);
        }
      }
    }
    return Component.empty();
  }

  /**
   * Updates the start_game display based on whether the game can start.
   *
   * @param canStart True if all conditions are met to start
   */
  public void updateStartGameDisplay(boolean canStart) {
    TextDisplay display = displays.get("start_game");
    if (display == null || !display.isValid()) {
      return;
    }
    Component title =
        canStart
            ? Component.text("START GAME", NamedTextColor.GREEN, TextDecoration.BOLD)
            : Component.text("START GAME", NamedTextColor.RED, TextDecoration.BOLD);
    Component subtitle =
        canStart
            ? Component.text("Click to start the hunt!", NamedTextColor.GRAY)
            : Component.text("Wait for all players to be ready", NamedTextColor.GRAY);
    display.text(title.append(Component.newline()).append(subtitle));
  }

  /**
   * Sets the content of the ready_status display.
   *
   * @param content The fully-built Component to display
   */
  public void updateReadyStatusDisplay(Component content) {
    TextDisplay display = displays.get("ready_status");
    if (display == null || !display.isValid()) {
      return;
    }
    display.text(content);
  }

  /**
   * Updates the gamemode_selection display with the current game mode.
   *
   * @param gameMode The current game mode
   */
  public void updateGameModeDisplay(HuntGameMode gameMode) {
    TextDisplay display = displays.get("gamemode_selection");
    if (display == null || !display.isValid()) {
      return;
    }
    NamedTextColor modeColor = getGameModeColor(gameMode);
    Component text =
        Component.text("CURRENT GAMEMODE", NamedTextColor.GOLD, TextDecoration.BOLD)
            .append(Component.newline())
            .append(Component.text(gameMode.getDisplayName(), modeColor, TextDecoration.BOLD));
    display.text(text);
  }

  private NamedTextColor getGameModeColor(HuntGameMode gameMode) {
    switch (gameMode) {
      case PROP_HUNT:
        return NamedTextColor.GREEN;
      case IMPOSTER_HUNT:
        return NamedTextColor.RED;
      case NEXTBOT_HUNT:
        return NamedTextColor.LIGHT_PURPLE;
      default:
        return NamedTextColor.WHITE;
    }
  }

  /**
   * Gets the current class selection hologram ID for a player.
   *
   * @param playerId The UUID of the player
   * @return The hologram ID, or null if no selection
   */
  public String getPlayerClassSelection(UUID playerId) {
    return playerClassSelections.get(playerId);
  }

  /**
   * Gets the current map vote hologram ID for a player.
   *
   * @param playerId The UUID of the player
   * @return The hologram ID, or null if no vote
   */
  public String getPlayerMapVote(UUID playerId) {
    return playerMapVotes.get(playerId);
  }

  /**
   * Removes a player from all holograms. Call on disconnect or world change.
   *
   * @param playerId The UUID of the player
   */
  public void removePlayerFromAllHolograms(UUID playerId) {
    removePlayerFromCurrentClass(playerId);
    removePlayerFromCurrentMap(playerId);
    playerClassFlags.remove(playerId);
    playerMapFlags.remove(playerId);
    playerNames.remove(playerId);
  }

  /** Clears all player entries from every hologram and resets displays to title-only. */
  public void removeAllPlayersFromHolograms() {
    hologramPlayers.clear();
    playerClassSelections.clear();
    playerMapVotes.clear();
    playerClassFlags.clear();
    playerMapFlags.clear();
    playerNames.clear();

    for (HunterClass c : HunterClass.values()) {
      refreshDisplay("hunter_" + c.name().toLowerCase());
    }
    for (HiderClass c : HiderClass.values()) {
      refreshDisplay("hider_" + c.name().toLowerCase());
    }
    for (HuntMap m : HuntMap.values()) {
      refreshDisplay("map_" + m.name().toLowerCase());
    }
    plugin.getLogger().info("Cleared all Hunt hologram player entries");
  }

  /**
   * Removes a player from their current class selection.
   *
   * @param playerId The UUID of the player
   * @return True if the player was in a class and was removed
   */
  public boolean removePlayerFromClass(UUID playerId) {
    if (playerClassSelections.containsKey(playerId)) {
      removePlayerFromCurrentClass(playerId);
      return true;
    }
    return false;
  }

  /**
   * Removes a player from their current map vote.
   *
   * @param playerId The UUID of the player
   * @return True if the player had a vote and it was removed
   */
  public boolean removePlayerFromMapVote(UUID playerId) {
    if (playerMapVotes.containsKey(playerId)) {
      removePlayerFromCurrentMap(playerId);
      return true;
    }
    return false;
  }

  /**
   * Sets the disguise manager reference for handling class-change side effects.
   *
   * @param disguiseManager The HuntDisguiseManager instance
   */
  public void setDisguiseManager(HuntDisguiseManager disguiseManager) {
    this.disguiseManager = disguiseManager;
  }

  /**
   * Removes all spawned Text Display and Interaction entities from the world. Call on plugin
   * disable.
   */
  public void despawnAllDisplays() {
    for (TextDisplay display : displays.values()) {
      if (display != null && display.isValid()) {
        display.remove();
      }
    }
    displays.clear();
    for (Interaction interaction : interactions.values()) {
      if (interaction != null && interaction.isValid()) {
        interaction.remove();
      }
    }
    interactions.clear();
    hologramPlayers.clear();
    playerClassSelections.clear();
    playerMapVotes.clear();
    playerClassFlags.clear();
    playerMapFlags.clear();
    playerNames.clear();
    plugin.getLogger().info("Despawned all Hunt hologram displays");
  }
}
