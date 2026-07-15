package com.storytimeproductions.prophunt.game;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import io.papermc.paper.profile.MutablePropertyMap;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import me.libraryaddict.disguise.DisguiseAPI;
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;
import me.libraryaddict.disguise.disguisetypes.watchers.PlayerWatcher;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Manages disguise functionality for the Hunt game. Interaction with disguise NPCs is handled by
 * HuntDisguiseNpcManager (packet-based fake players); this class owns the logic for applying and
 * removing LibsDisguises disguises on the clicking player.
 */
public class HuntDisguiseManager {
  private final JavaPlugin plugin;
  private final Map<String, String> standSkins = new HashMap<>();
  private final Map<String, String> standDisplayNames = new HashMap<>();
  private final Map<String, Double> standScales = new HashMap<>();
  private final Map<String, String> standSkinValues = new HashMap<>();
  private final Map<String, String> standSkinSignatures = new HashMap<>();
  private final Map<UUID, Long> playerCooldowns = new HashMap<>();
  private final Map<UUID, Long> playerScreechCooldowns = new HashMap<>();
  private final Map<UUID, BukkitTask> disguiseTasks = new HashMap<>();
  private final Map<UUID, String> playerDisguiseStands = new HashMap<>();
  private final Map<UUID, String> playerDisguiseTypes = new HashMap<>();
  private FileConfiguration huntConfig;
  private HuntPrepPhaseManager prepPhaseManager;
  private final HuntGameModeManager gameModeManager;
  private HuntLobbyManager lobbyManager;

  /**
   * Constructs a new HuntDisguiseManager.
   *
   * @param plugin The JavaPlugin instance
   * @param gameModeManager The HuntGameModeManager instance
   */
  public HuntDisguiseManager(JavaPlugin plugin, HuntGameModeManager gameModeManager) {
    this.plugin = plugin;
    this.gameModeManager = gameModeManager;
    loadConfiguration();
  }

  /**
   * Sets the lobby manager reference for player data lookups.
   *
   * @param lobbyManager The lobby manager instance
   */
  public void setLobbyManager(HuntLobbyManager lobbyManager) {
    this.lobbyManager = lobbyManager;
  }

  /** Loads the hunt configuration from hunt.yml. */
  private void loadConfiguration() {
    try {
      java.io.File huntConfigFile = new java.io.File(plugin.getDataFolder(), "hunt.yml");
      if (!huntConfigFile.exists()) {
        plugin.saveResource("hunt.yml", false);
      }
      huntConfig = YamlConfiguration.loadConfiguration(huntConfigFile);
      plugin.getLogger().info("Loaded hunt configuration");
    } catch (Exception e) {
      plugin.getLogger().severe("Failed to load hunt configuration: " + e.getMessage());
      huntConfig = new YamlConfiguration();
    }
  }

  /**
   * Registers a disguise location so interaction logic can look up skin/display name by ID. Called
   * by HuntDisguiseNpcManager when it loads each NPC.
   *
   * @param locationId The config key (e.g. "location1")
   * @param skinName The Minecraft username whose skin to use
   * @param displayName The character display name
   * @param scale The visual scale (unused directly here, stored for reference)
   */
  public void registerDisguiseLocation(
      String locationId,
      String skinName,
      String displayName,
      double scale,
      String skinValue,
      String skinSignature) {
    standSkins.put(locationId, skinName);
    standDisplayNames.put(locationId, displayName);
    standScales.put(locationId, scale);
    if (skinValue != null) {
      standSkinValues.put(locationId, skinValue);
    }
    if (skinSignature != null) {
      standSkinSignatures.put(locationId, skinSignature);
    }
  }

  /** Clears all registered disguise location data. Called by HuntDisguiseNpcManager on reload. */
  public void clearDisguiseLocations() {
    standSkins.clear();
    standDisplayNames.clear();
    standScales.clear();
    standSkinValues.clear();
    standSkinSignatures.clear();
  }

  /**
   * Returns the skin value and signature for the disguise a player currently has active, or null if
   * not available.
   *
   * @param playerId The player's UUID
   * @return String array [value, signature], or null
   */
  public String[] getSkinDataForPlayer(UUID playerId) {
    String locationId = playerDisguiseStands.get(playerId);
    if (locationId == null) {
      return null;
    }
    String value = standSkinValues.get(locationId);
    String signature = standSkinSignatures.get(locationId);
    if (value == null || signature == null) {
      return null;
    }
    return new String[] {value, signature};
  }

  /**
   * Handles a player clicking on a disguise NPC.
   *
   * @param player The player who clicked
   * @param locationId The config key of the disguise location that was clicked
   * @param hologramManager The hologram manager to check if player is a hunter
   */
  public void handleDisguiseInteraction(
      Player player, String locationId, HuntHologramManager hologramManager) {

    if (gameModeManager.getCurrentGameMode() == HuntGameMode.IMPOSTER_HUNT) {
      player.sendMessage(
          Component.text("Disguises are not available in Imposter Hunt mode!", NamedTextColor.RED));
      return;
    }

    String playerClass = hologramManager.getPlayerClassSelection(player.getUniqueId());
    if (playerClass == null || !playerClass.startsWith("hunter_")) {
      return;
    }

    long currentTime = System.currentTimeMillis();
    Long lastUse = playerCooldowns.get(player.getUniqueId());
    int cooldownSeconds = huntConfig.getInt("hunt.disguise.cooldown", 30);

    if (lastUse != null && (currentTime - lastUse) < (cooldownSeconds * 1000L)) {
      return;
    }

    String skinName = standSkins.get(locationId);
    String displayName = standDisplayNames.get(locationId);
    if (skinName == null || displayName == null) {
      player.sendMessage(
          Component.text("Failed to get disguise data!", NamedTextColor.RED, TextDecoration.BOLD));
      return;
    }

    String currentDisguiseStand = playerDisguiseStands.get(player.getUniqueId());
    if (locationId.equals(currentDisguiseStand)) {
      removeDisguise(player);
      playerCooldowns.put(player.getUniqueId(), currentTime);
      return;
    }

    if (DisguiseAPI.isDisguised(player)) {
      DisguiseAPI.undisguiseToAll(player);
    }

    BukkitTask existingTask = disguiseTasks.get(player.getUniqueId());
    if (existingTask != null) {
      existingTask.cancel();
      disguiseTasks.remove(player.getUniqueId());
    }

    try {
      PlayerDisguise disguise = new PlayerDisguise(skinName);
      disguise.setReplaceSounds(false);
      disguise.setModifyBoundingBox(false);

      PlayerWatcher watcher = (PlayerWatcher) disguise.getWatcher();
      watcher.setNameVisible(false);
      watcher.setCapeEnabled(false);

      // Apply explicit skin value/signature from config if available, so the
      // LibsDisguises disguise matches the NPC's skin instead of doing a Mojang lookup.
      String skinValue = standSkinValues.get(locationId);
      String skinSignature = standSkinSignatures.get(locationId);
      if (skinValue != null && skinSignature != null) {
        try {
          MutablePropertyMap props = new MutablePropertyMap();
          props.put("textures", new Property("textures", skinValue, skinSignature));
          GameProfile profile =
              new GameProfile(
                  java.util.UUID.nameUUIDFromBytes(
                      ("hunt_skin_" + skinName).getBytes(StandardCharsets.UTF_8)),
                  skinName,
                  props);
          disguise.setGameProfile(profile);
        } catch (Exception skinEx) {
          plugin
              .getLogger()
              .warning("Could not apply explicit skin to disguise: " + skinEx.getMessage());
        }
      }

      DisguiseAPI.disguiseToAll(player, disguise);

      double playerScale = huntConfig.getDouble("hunt.disguise.player-scale", 1.3);
      org.bukkit.attribute.AttributeInstance scaleAttr =
          player.getAttribute(org.bukkit.attribute.Attribute.SCALE);
      if (scaleAttr != null) {
        scaleAttr.setBaseValue(playerScale);
      }

      player.playSound(player.getLocation(), Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1.0f, 0.5f);
      player.playSound(player.getLocation(), Sound.BLOCK_BONE_BLOCK_BREAK, 1.0f, 0.8f);

      playerCooldowns.put(player.getUniqueId(), currentTime);
      playerDisguiseStands.put(player.getUniqueId(), locationId);
      playerDisguiseTypes.put(player.getUniqueId(), displayName);

      if (lobbyManager != null) {
        HuntPlayerData playerData = lobbyManager.getPlayerData(player.getUniqueId());
        if (playerData != null) {
          playerData.setSelectedDisguise(displayName);
          playerData.setSelectedDisguiseSkin(skinName);
        }
      }

      int duration = huntConfig.getInt("hunt.disguise.duration", 0);
      if (duration > 0) {
        BukkitTask task =
            Bukkit.getScheduler()
                .runTaskLater(
                    plugin,
                    () -> {
                      removeDisguise(player);
                      disguiseTasks.remove(player.getUniqueId());
                    },
                    duration * 20L);
        disguiseTasks.put(player.getUniqueId(), task);
        player.sendMessage(
            Component.text("Disguised as ", NamedTextColor.GREEN)
                .append(Component.text(displayName, NamedTextColor.WHITE))
                .append(Component.text(" for " + duration + " seconds!", NamedTextColor.GREEN)));
      } else {
        player.sendMessage(
            Component.text("Disguised as ", NamedTextColor.GREEN)
                .append(Component.text(displayName, NamedTextColor.WHITE))
                .append(Component.text("!", NamedTextColor.GREEN)));
      }
      HunterDisguiseType disguiseType = HunterDisguiseType.fromDisplayName(displayName);
      if (disguiseType != null) {
        player.sendMessage(
            Component.text("  Passive: ", NamedTextColor.GRAY)
                .append(
                    Component.text(
                        "[" + disguiseType.getDisplayName() + "]", NamedTextColor.LIGHT_PURPLE))
                .append(
                    Component.text(
                        " — " + disguiseType.getPassiveDescription(), NamedTextColor.WHITE)));
      }

      plugin.getLogger().info(player.getName() + " disguised as " + displayName);

      if (prepPhaseManager != null) {
        prepPhaseManager.updateDisguiseSelections();
      }

    } catch (Exception e) {
      player.sendMessage(
          Component.text("Failed to apply disguise!", NamedTextColor.RED, TextDecoration.BOLD));
      plugin
          .getLogger()
          .warning("Failed to disguise player " + player.getName() + ": " + e.getMessage());
    }
  }

  /**
   * Removes a player's disguise and resets their size.
   *
   * @param player The player to remove disguise from
   */
  public void removeDisguise(Player player) {
    if (DisguiseAPI.isDisguised(player)) {
      DisguiseAPI.undisguiseToAll(player);

      org.bukkit.attribute.AttributeInstance scaleAttr =
          player.getAttribute(org.bukkit.attribute.Attribute.SCALE);
      if (scaleAttr != null) {
        scaleAttr.setBaseValue(1.0);
      }

      if (lobbyManager != null) {
        HuntPlayerData playerData = lobbyManager.getPlayerData(player.getUniqueId());
        if (playerData != null) {
          playerData.setSelectedDisguise(null);
          playerData.setSelectedDisguiseSkin(null);
        }
      }

      player.sendMessage(Component.text("Disguise removed!", NamedTextColor.YELLOW));
    }

    BukkitTask task = disguiseTasks.get(player.getUniqueId());
    if (task != null) {
      task.cancel();
      disguiseTasks.remove(player.getUniqueId());
    }

    playerDisguiseStands.remove(player.getUniqueId());
    playerDisguiseTypes.remove(player.getUniqueId());
    playerScreechCooldowns.remove(player.getUniqueId());

    if (prepPhaseManager != null) {
      prepPhaseManager.updateDisguiseSelections();
    }
  }

  /**
   * Re-applies a player's active disguise after a world change. Only works if the player had a
   * disguise tracked via playerDisguiseStands (i.e., cleanup did not wipe it).
   *
   * @param player The player to re-disguise
   */
  public void reapplyDisguise(Player player) {
    String locationId = playerDisguiseStands.get(player.getUniqueId());
    if (locationId == null) {
      return;
    }

    String skinName = standSkins.get(locationId);
    String displayName = standDisplayNames.get(locationId);
    if (skinName == null || displayName == null) {
      return;
    }

    try {
      if (DisguiseAPI.isDisguised(player)) {
        DisguiseAPI.undisguiseToAll(player);
      }

      PlayerDisguise disguise = new PlayerDisguise(skinName);
      disguise.setReplaceSounds(false);
      disguise.setModifyBoundingBox(false);

      PlayerWatcher watcher = (PlayerWatcher) disguise.getWatcher();
      watcher.setNameVisible(false);
      watcher.setCapeEnabled(false);

      String skinValue = standSkinValues.get(locationId);
      String skinSignature = standSkinSignatures.get(locationId);
      if (skinValue != null && skinSignature != null) {
        try {
          MutablePropertyMap props = new MutablePropertyMap();
          props.put("textures", new Property("textures", skinValue, skinSignature));
          GameProfile profile =
              new GameProfile(
                  java.util.UUID.nameUUIDFromBytes(
                      ("hunt_skin_" + skinName).getBytes(StandardCharsets.UTF_8)),
                  skinName,
                  props);
          disguise.setGameProfile(profile);
        } catch (Exception skinEx) {
          plugin
              .getLogger()
              .warning("Could not apply skin to re-applied disguise: " + skinEx.getMessage());
        }
      }

      DisguiseAPI.disguiseToAll(player, disguise);

      double playerScale = huntConfig.getDouble("hunt.disguise.player-scale", 1.3);
      org.bukkit.attribute.AttributeInstance scaleAttr =
          player.getAttribute(org.bukkit.attribute.Attribute.SCALE);
      if (scaleAttr != null) {
        scaleAttr.setBaseValue(playerScale);
      }

      plugin.getLogger().info("Re-applied disguise '" + displayName + "' for " + player.getName());
    } catch (Exception e) {
      plugin
          .getLogger()
          .warning("Failed to re-apply disguise for " + player.getName() + ": " + e.getMessage());
    }
  }

  /** Removes all disguises from all players and cleans up. */
  public void removeAllDisguises() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (DisguiseAPI.isDisguised(player)) {
        removeDisguise(player);
      }
    }

    for (BukkitTask task : disguiseTasks.values()) {
      task.cancel();
    }
    disguiseTasks.clear();
    playerCooldowns.clear();
    playerDisguiseStands.clear();
    playerDisguiseTypes.clear();
    playerScreechCooldowns.clear();

    plugin.getLogger().info("Removed all hunt disguises");
  }

  /**
   * Gets the display name of the disguise a player currently has active.
   *
   * @param playerId The UUID of the player
   * @return The display name, or null if not disguised
   */
  public String getPlayerDisguiseSkin(UUID playerId) {
    if (!DisguiseAPI.isDisguised(Bukkit.getPlayer(playerId))) {
      return null;
    }
    return playerDisguiseTypes.get(playerId);
  }

  /**
   * Checks if a player is currently disguised.
   *
   * @param playerId The UUID of the player
   * @return true if the player is disguised
   */
  public boolean isPlayerDisguised(UUID playerId) {
    Player player = Bukkit.getPlayer(playerId);
    if (player == null) {
      return false;
    }
    return DisguiseAPI.isDisguised(player) && playerDisguiseTypes.containsKey(playerId);
  }

  /**
   * Gets all currently disguised players.
   *
   * @return A set of UUIDs of all currently disguised players
   */
  public Set<UUID> getDisguisedPlayers() {
    return new HashSet<>(playerDisguiseTypes.keySet());
  }

  /**
   * Plays a character-specific screech sound for a disguised hunter.
   *
   * @param player The disguised player
   */
  public void playCharacterScreech(Player player) {
    String disguiseType = playerDisguiseTypes.get(player.getUniqueId());
    if (disguiseType == null) {
      return;
    }

    UUID playerId = player.getUniqueId();
    long currentTime = System.currentTimeMillis();

    Long lastScreech = playerScreechCooldowns.get(playerId);
    if (lastScreech != null && (currentTime - lastScreech) < 3000) {
      return;
    }

    playerScreechCooldowns.put(playerId, currentTime);

    Location playerLoc = player.getLocation();
    switch (disguiseType) {
      case "Springtrap" -> {
        player.getWorld().playSound(playerLoc, Sound.BLOCK_METAL_BREAK, 2.0f, 0.3f);
        player.getWorld().playSound(playerLoc, Sound.ENTITY_IRON_GOLEM_HURT, 1.5f, 1.8f);
      }
      case "Herobrine" -> {
        player.getWorld().playSound(playerLoc, Sound.ENTITY_GHAST_SCREAM, 1.5f, 1.2f);
        player.getWorld().playSound(playerLoc, Sound.AMBIENT_CAVE, 1.2f, 2.0f);
      }
      case "Slenderman" -> {
        player.getWorld().playSound(playerLoc, Sound.BLOCK_BEACON_DEACTIVATE, 1.8f, 0.1f);
        player.getWorld().playSound(playerLoc, Sound.ENTITY_ENDERMAN_SCREAM, 1.5f, 0.7f);
      }
      case "Cryptid" -> {
        player.getWorld().playSound(playerLoc, "entity.wolf.howl", 1.8f, 0.8f);
        player.getWorld().playSound(playerLoc, Sound.ENTITY_RAVAGER_ROAR, 1.4f, 1.4f);
      }
      case "Jigsaw" -> {
        player.getWorld().playSound(playerLoc, Sound.BLOCK_GRINDSTONE_USE, 1.7f, 0.5f);
        player.getWorld().playSound(playerLoc, Sound.ENTITY_VEX_CHARGE, 1.5f, 0.9f);
      }
      case "Scarecrow" -> {
        player.getWorld().playSound(playerLoc, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1.7f, 0.6f);
        player.getWorld().playSound(playerLoc, Sound.BLOCK_GRASS_BREAK, 1.4f, 0.8f);
        player.getWorld().playSound(playerLoc, Sound.ENTITY_PHANTOM_AMBIENT, 1.6f, 1.3f);
      }
      default -> player.getWorld().playSound(playerLoc, Sound.ENTITY_ZOMBIE_AMBIENT, 1.5f, 0.5f);
    }
  }

  /**
   * Resets a player's entity size to normal.
   *
   * @param player The player whose size should be reset
   */
  public void resetPlayerSize(Player player) {
    org.bukkit.attribute.AttributeInstance scaleAttr =
        player.getAttribute(org.bukkit.attribute.Attribute.SCALE);
    if (scaleAttr != null) {
      scaleAttr.setBaseValue(1.0);
    }
  }

  /**
   * Handles player class changes — removes disguise if switching away from hunter.
   *
   * @param player The player whose class changed
   * @param oldClass The old class hologram ID, or null
   * @param newClass The new class hologram ID, or null
   */
  public void handleClassChange(Player player, String oldClass, String newClass) {
    if (!DisguiseAPI.isDisguised(player)) {
      return;
    }

    boolean wasHunter = oldClass != null && oldClass.startsWith("hunter_");
    boolean wasHider = oldClass != null && oldClass.startsWith("hider_");
    boolean isNowHider = newClass != null && newClass.startsWith("hider_");
    boolean leftAllClasses = newClass == null;

    if (wasHunter && (isNowHider || leftAllClasses)) {
      removeDisguise(player);
    } else if (wasHider && leftAllClasses) {
      removeDisguise(player);
      plugin
          .getLogger()
          .info(
              "Removed disguise from "
                  + player.getName()
                  + " due to class change (was: "
                  + oldClass
                  + ", now: "
                  + newClass
                  + ")");
    }
  }

  /**
   * Sets the prep phase manager reference for hologram updates.
   *
   * @param prepPhaseManager The prep phase manager instance
   */
  public void setPrepPhaseManager(HuntPrepPhaseManager prepPhaseManager) {
    this.prepPhaseManager = prepPhaseManager;
  }
}
