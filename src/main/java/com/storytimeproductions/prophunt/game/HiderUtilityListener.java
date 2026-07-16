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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

/**
 * Listener that handles hider utility item abilities and block transformation. Each hider class has
 * a unique utility item with special abilities.
 */
public class HiderUtilityListener implements Listener {

  // Max distance (blocks) to ray trace forward looking for a wall to phase through.
  private static final int PHASE_MAX_DISTANCE = 5;
  // Max wall thickness (blocks) to search through beyond the point of impact.
  private static final int PHASE_MAX_WALL_THICKNESS = 4;

  private final JavaPlugin plugin;
  private final FileConfiguration config;
  private final Map<UUID, Long> tricksterCooldowns;
  private final Map<UUID, Long> phaserCooldowns;
  private final Map<UUID, Long> cloakerCooldowns;
  private final Map<UUID, Long> blockDisguiseCooldowns;
  private final Map<UUID, BossBar> playerBossBars;
  private final Map<UUID, BukkitTask> bossBarTasks;
  private final Map<UUID, BlockData> playerBlockData;

  private final HuntLobbyManager lobbyManager;

  /**
   * Constructs a new HiderUtilityListener.
   *
   * @param plugin The plugin instance
   * @param lobbyManager The lobby manager for checking player states
   */
  public HiderUtilityListener(JavaPlugin plugin, HuntLobbyManager lobbyManager) {
    this.plugin = plugin;
    this.lobbyManager = lobbyManager;
    this.config = loadHuntConfiguration();
    this.tricksterCooldowns = new HashMap<>();
    this.phaserCooldowns = new HashMap<>();
    this.cloakerCooldowns = new HashMap<>();
    this.blockDisguiseCooldowns = new HashMap<>();
    this.playerBossBars = new HashMap<>();
    this.bossBarTasks = new HashMap<>();
    this.playerBlockData = new HashMap<>();
  }

  /** Loads the hunt configuration from hunt.yml. */
  private FileConfiguration loadHuntConfiguration() {
    try {
      java.io.File huntConfigFile = new java.io.File(plugin.getDataFolder(), "hunt.yml");
      if (!huntConfigFile.exists()) {
        plugin.saveResource("hunt.yml", false);
      }

      // Load the configuration
      FileConfiguration huntConfig = YamlConfiguration.loadConfiguration(huntConfigFile);

      plugin.getLogger().info("Loaded hunt configuration for HiderUtilityListener");
      return huntConfig;
    } catch (Exception e) {
      plugin.getLogger().severe("Failed to load hunt configuration: " + e.getMessage());
      // Create a default configuration
      return new YamlConfiguration();
    }
  }

  /** Handles player interactions with utility items and block transformation. */
  @EventHandler
  public void onPlayerInteract(PlayerInteractEvent event) {
    Player player = event.getPlayer();
    boolean debug = plugin.getConfig().getBoolean("debug", false);

    // Check if player is in hunt world
    boolean inHuntWorld = isPlayerInHuntWorld(player);
    if (!inHuntWorld) {
      if (debug && player.getWorld().getName().toLowerCase().contains("hunt")) {
        plugin
            .getLogger()
            .info(
                "Player "
                    + player.getName()
                    + " interaction in world "
                    + player.getWorld().getName()
                    + " not recognized as hunt world");
      }
      return;
    }

    // Check if player is a hider
    boolean isHider = isPlayerHider(player);
    if (!isHider) {
      if (debug) {
        plugin
            .getLogger()
            .info(
                "Player "
                    + player.getName()
                    + " not recognized as hider in hunt world "
                    + player.getWorld().getName());
      }
      return;
    }

    if (debug) {
      plugin
          .getLogger()
          .info(
              "Valid hider interaction detected for "
                  + player.getName()
                  + " in hunt world "
                  + player.getWorld().getName()
                  + " with action "
                  + event.getAction());
    }

    // Block hiders from opening any interactive block UI (chests, anvils, trapdoors, etc.)
    if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
      if (isInteractiveBlock(event.getClickedBlock().getType())) {
        event.setCancelled(true);
        return;
      }
    }

    // Handle left-click air for block rotation
    if (event.getAction() == Action.LEFT_CLICK_AIR
        || event.getAction() == Action.LEFT_CLICK_BLOCK) {
      handleBlockRotation(player, event);
      return;
    }

    // Only handle right-click interactions for abilities
    if (!event.getAction().toString().contains("RIGHT_CLICK")) {
      return;
    }

    HiderClass hiderClass = getPlayerHiderClass(player);
    if (hiderClass == null) {
      return;
    }

    final ItemStack item = event.getItem();

    // Handle block transformation (empty hand + right-click block)
    if (item == null || item.getType() == Material.AIR) {
      if (plugin.getConfig().getBoolean("debug", false)) {
        plugin
            .getLogger()
            .info(
                "Player " + player.getName() + " has empty hand, attempting block transformation");
      }
      handleBlockTransformation(player, event);
      return;
    } else if (plugin.getConfig().getBoolean("debug", false)) {
      plugin
          .getLogger()
          .info("Player " + player.getName() + " has item in hand: " + item.getType().name());
    }

    // Handle utility item abilities
    Material material = item.getType();
    UUID playerId = player.getUniqueId();

    switch (hiderClass) {
      case TRICKSTER:
        if (material == Material.TRIPWIRE_HOOK) {
          handleTricksterTrap(player, playerId, event);
        }
        break;
      case PHASER:
        if (material == Material.ENDER_PEARL) {
          handlePhaserPhase(player, playerId, event);
        }
        break;
      case CLOAKER:
        if (material == Material.POTION) {
          handleCloakerInvisibility(player, playerId, event);
        }
        break;
      default:
        // No action needed for unknown hider classes
        break;
    }
  }

  /**
   * Handles block transformation ability for all hiders. Players can right-click a block with empty
   * hand to transform into it.
   */
  private void handleBlockTransformation(Player player, PlayerInteractEvent event) {
    UUID playerId = player.getUniqueId();
    int cooldownSeconds = config.getInt("hider-abilities.block-disguise-cooldown", 5);

    // Debug logging
    boolean debug = plugin.getConfig().getBoolean("debug", false);
    if (debug) {
      plugin
          .getLogger()
          .info(
              "Player "
                  + player.getName()
                  + " attempting block transformation in world: "
                  + player.getWorld().getName());
    }

    Block clickedBlock = event.getClickedBlock();
    if (clickedBlock != null) {
      Material blockType = clickedBlock.getType();

      if (debug) {
        plugin
            .getLogger()
            .info("Player " + player.getName() + " clicked on block: " + blockType.name());
      }
    }

    // Only check cooldown for actual block disguise attempts (not door interactions)
    if (isOnCooldown(playerId, blockDisguiseCooldowns, cooldownSeconds)) {
      if (debug) {
        long remainingTime =
            getRemainingCooldown(playerId, blockDisguiseCooldowns, cooldownSeconds);
        plugin
            .getLogger()
            .info(
                "Block disguise on cooldown for "
                    + player.getName()
                    + ": "
                    + remainingTime
                    + " ms remaining");
      }
      event.setCancelled(true);
      return;
    }

    if (clickedBlock != null) {
      Material blockType = clickedBlock.getType();

      // Only allow transformation into solid blocks (not air, water, etc.)
      if (blockType.isSolid() && !isBlacklisted(blockType)) {
        transformIntoBlock(player, blockType);
        blockDisguiseCooldowns.put(playerId, System.currentTimeMillis());
        startBossBarCooldown(player, cooldownSeconds);
        event.setCancelled(true);
      } else if (debug) {
        plugin
            .getLogger()
            .info(
                "Block "
                    + blockType.name()
                    + " not valid for disguise. Solid: "
                    + blockType.isSolid()
                    + ", Blacklisted: "
                    + isBlacklisted(blockType));
      }
    } else if (debug) {
      plugin.getLogger().info("No clicked block found for player " + player.getName());
    }
  }

  /** Transforms a player into a falling block disguise. */
  private void transformIntoBlock(Player player, Material blockType) {
    // Debug info
    if (plugin.getConfig().getBoolean("debug", false)) {
      plugin
          .getLogger()
          .info(
              "Transforming player "
                  + player.getName()
                  + " into block: "
                  + blockType.name()
                  + " in world: "
                  + player.getWorld().getName());
    }

    // Preserve invisibility before swapping disguise so cloakers don't reveal themselves
    boolean wasInvisible = player.hasPotionEffect(PotionEffectType.INVISIBILITY);
    final PotionEffect invisEffect =
        wasInvisible ? player.getPotionEffect(PotionEffectType.INVISIBILITY) : null;

    if (DisguiseAPI.isDisguised(player)) {
      DisguiseAPI.undisguiseToAll(player);
    }

    // Create falling block disguise
    MiscDisguise disguise = new MiscDisguise(DisguiseType.FALLING_BLOCK);
    disguise.setReplaceSounds(true);

    // Create block data and store it for rotation
    BlockData blockData = blockType.createBlockData();
    playerBlockData.put(player.getUniqueId(), blockData);

    // Set the block data to the specific block material
    FallingBlockWatcher watcher = (FallingBlockWatcher) disguise.getWatcher();
    watcher.setBlockData(blockData);

    // Apply the disguise
    DisguiseAPI.disguiseToAll(player, disguise);

    // Re-apply invisibility if it was active (undisguise may have cleared it)
    if (wasInvisible && invisEffect != null) {
      player.addPotionEffect(invisEffect);
    }

    if (plugin.getConfig().getBoolean("debug", false)) {
      plugin.getLogger().info("Successfully applied block disguise to player " + player.getName());
    }

    // Visual and audio effects
    Location location = player.getLocation();
    if (location != null) {
      player
          .getWorld()
          .spawnParticle(Particle.CLOUD, location.add(0, 1, 0), 20, 0.5, 0.5, 0.5, 0.1);
      player.playSound(location, Sound.BLOCK_STONE_BREAK, 1.0f, 1.0f);
    }

    // Check if the block can be rotated with more thorough validation
    boolean canRotate = false;

    if (blockData instanceof Directional) {
      Directional directional = (Directional) blockData;
      // Check if it has multiple valid faces
      canRotate = directional.getFaces().size() > 1;
    } else if (blockData instanceof Rotatable) {
      // Most rotatable blocks can be rotated
      canRotate = true;
    } else if (blockData instanceof Orientable) {
      // Most orientable blocks can be oriented
      canRotate = true;
    }

    String message =
        "You have transformed into a " + blockType.name().toLowerCase().replace("_", " ") + "!";
    if (canRotate) {
      message += " Left-click air to rotate.";
    }

    // Debug logging to help identify problematic blocks
    if (plugin.getConfig().getBoolean("debug", false)) {
      String facesInfo = "";
      if (blockData instanceof Directional) {
        Directional directional = (Directional) blockData;
        facesInfo = " - Valid faces: " + directional.getFaces().size();
      }

      plugin
          .getLogger()
          .info(
              "Block transformation: "
                  + blockType.name()
                  + " - Directional: "
                  + (blockData instanceof Directional)
                  + " - Rotatable: "
                  + (blockData instanceof Rotatable)
                  + " - Orientable: "
                  + (blockData instanceof Orientable)
                  + facesInfo
                  + " - Can rotate: "
                  + canRotate);
    }

    player.sendMessage(Component.text(message).color(NamedTextColor.GREEN));
  }

  /** Handles the Trickster's trap ability. */
  private void handleTricksterTrap(Player player, UUID playerId, PlayerInteractEvent event) {
    int cooldownSeconds = config.getInt("hider-abilities.trickster-cooldown", 20);

    if (isOnCooldown(playerId, tricksterCooldowns, cooldownSeconds)) {
      long remainingTime = getRemainingCooldown(playerId, tricksterCooldowns, cooldownSeconds);
      startUtilityCooldownBossBar(player, "Trap", remainingTime);
      event.setCancelled(true);
      return;
    }

    // Create a temporary trap that slows hunters who walk over it
    Block targetBlock = event.getClickedBlock();
    if (targetBlock != null) {
      // Mark this location as a trap for 30 seconds
      createTricksterTrap(player, targetBlock.getLocation().add(0, 1, 0));
      tricksterCooldowns.put(playerId, System.currentTimeMillis());

      player.sendMessage(
          Component.text("Trap placed! Hunters who walk here will be slowed.")
              .color(NamedTextColor.GOLD));
      event.setCancelled(true);
    }
  }

  /** Creates a temporary trap that affects hunters. */
  private void createTricksterTrap(Player trickster, org.bukkit.Location trapLocation) {
    // Visual effect for the trap
    trapLocation.getWorld().spawnParticle(Particle.FLAME, trapLocation, 10, 0.5, 0.1, 0.5, 0.1);

    // Create a repeating task that checks for hunters in the area
    new BukkitRunnable() {
      private int duration = 30 * 20; // 30 seconds in ticks

      @Override
      public void run() {
        duration -= 20; // Decrease by 1 second

        if (duration <= 0) {
          cancel();
          return;
        }

        // Check for hunters within 2 blocks of the trap
        for (Player nearbyPlayer : trapLocation.getWorld().getPlayers()) {
          if (isPlayerHunter(nearbyPlayer)
              && nearbyPlayer.getLocation().distance(trapLocation) <= 2.0) {

            // Apply slowness effect
            nearbyPlayer.addPotionEffect(
                new PotionEffect(PotionEffectType.SLOWNESS, 60, 1)); // 3 seconds
            nearbyPlayer.sendMessage(
                Component.text("You've been trapped by a Trickster!").color(NamedTextColor.RED));
            nearbyPlayer.playSound(
                nearbyPlayer.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_ON, 1.0f, 1.0f);

            // Highlight the trapped hunter for the trickster (glowing effect)
            if (trickster.isOnline()) {
              // Apply glowing effect visible only to the trickster
              nearbyPlayer.addPotionEffect(
                  new PotionEffect(PotionEffectType.GLOWING, 100, 0)); // 5 seconds

              // Notify the trickster with enhanced message
              trickster.sendMessage(
                  Component.text(
                          "Your trap caught "
                              + nearbyPlayer.getName()
                              + "! They are now highlighted.")
                      .color(NamedTextColor.GREEN));

              // Remove the glowing effect after 5 seconds
              new BukkitRunnable() {
                @Override
                public void run() {
                  if (nearbyPlayer.isOnline()) {
                    nearbyPlayer.removePotionEffect(PotionEffectType.GLOWING);
                  }
                }
              }.runTaskLater(plugin, 100L); // 5 seconds
            }

            // Remove the trap after triggering
            cancel();
            return;
          }
        }

        // Show trap particles every second
        if (duration % 20 == 0) {
          trapLocation
              .getWorld()
              .spawnParticle(Particle.FLAME, trapLocation, 5, 0.3, 0.1, 0.3, 0.1);
        }
      }
    }.runTaskTimer(plugin, 0L, 20L);
  }

  /** Handles the Phaser's phase ability. */
  private void handlePhaserPhase(Player player, UUID playerId, PlayerInteractEvent event) {
    int cooldownSeconds = config.getInt("hider-abilities.phaser-cooldown", 7);

    if (isOnCooldown(playerId, phaserCooldowns, cooldownSeconds)) {
      long remainingTime = getRemainingCooldown(playerId, phaserCooldowns, cooldownSeconds);
      startUtilityCooldownBossBar(player, "Phase", remainingTime);
      event.setCancelled(true);
      return;
    }

    // Attempt to phase through a wall
    Location teleportLocation = findPhaseLocation(player);

    if (teleportLocation != null) {
      // Successfully found a location to phase to

      // Visual and audio effects at starting location
      Location startLocation = player.getLocation();
      player.getWorld().spawnParticle(Particle.PORTAL, startLocation, 30, 0.5, 1, 0.5, 1);

      // Teleport the player
      player.teleport(teleportLocation);

      // Visual and audio effects at destination
      player.getWorld().spawnParticle(Particle.PORTAL, teleportLocation, 30, 0.5, 1, 0.5, 1);

      player.sendMessage(
          Component.text("You phased through the wall!").color(NamedTextColor.LIGHT_PURPLE));

      phaserCooldowns.put(playerId, System.currentTimeMillis());
    } else {
      // No valid phase location found
      player.sendMessage(
          Component.text("No wall to phase through! Face a wall with space behind it.")
              .color(NamedTextColor.RED));
    }

    event.setCancelled(true);
  }

  /**
   * Finds a safe location to teleport the player through the wall they're looking at. Ray traces
   * along the player's exact look direction to find the wall, then keeps stepping along that same
   * direction (never sideways/up/down) until it finds an open, safe pocket - so the destination
   * always stays true to the wall the player was actually facing.
   *
   * @param player The player attempting to phase
   * @return A safe teleport location, or null if no valid location exists
   */
  private Location findPhaseLocation(Player player) {
    Location eye = player.getEyeLocation();
    org.bukkit.util.Vector direction = eye.getDirection().normalize();

    org.bukkit.util.RayTraceResult hit =
        player
            .getWorld()
            .rayTraceBlocks(
                eye, direction, PHASE_MAX_DISTANCE, org.bukkit.FluidCollisionMode.NEVER, true);
    if (hit == null || hit.getHitBlock() == null) {
      return null; // No wall in range
    }

    Location wallEntry = hit.getHitPosition().toLocation(player.getWorld());
    Location playerLocation = player.getLocation();
    for (int step = 0; step <= PHASE_MAX_WALL_THICKNESS; step++) {
      Location candidate = wallEntry.clone().add(direction.clone().multiply(step + 0.5));
      if (isSafeLocation(candidate)) {
        return createTeleportLocation(candidate, playerLocation);
      }
    }

    return null; // Wall too thick to phase through
  }

  /**
   * Checks if a location is safe for teleportation. A safe location has air blocks for the player's
   * head and feet, and solid ground below.
   *
   * @param location The location to check
   * @return true if the location is safe for teleportation
   */
  private boolean isSafeLocation(Location location) {
    Block feetBlock = location.getBlock();
    Block headBlock = location.clone().add(0, 1, 0).getBlock();
    Block groundBlock = location.clone().add(0, -1, 0).getBlock();

    // Check if there's enough space for the player (air blocks for head and feet)
    boolean hasSpace = !feetBlock.getType().isSolid() && !headBlock.getType().isSolid();

    // Check if there's solid ground below (or water/lava for edge cases)
    boolean hasSolidGround =
        groundBlock.getType().isSolid()
            || groundBlock.getType() == Material.WATER
            || groundBlock.getType() == Material.LAVA;

    // Additional safety: make sure we're not teleporting into dangerous blocks
    boolean isDangerous =
        feetBlock.getType() == Material.LAVA
            || feetBlock.getType() == Material.FIRE
            || headBlock.getType() == Material.LAVA
            || headBlock.getType() == Material.FIRE;

    return hasSpace && hasSolidGround && !isDangerous;
  }

  /**
   * Creates a properly centered teleport location with the player's original orientation.
   *
   * @param location The raw location to teleport to
   * @param playerLocation The player's original location for yaw/pitch reference
   * @return A properly formatted teleport location
   */
  private Location createTeleportLocation(Location location, Location playerLocation) {
    Location teleportLocation = location.clone();
    // Center the player in the block
    teleportLocation.setX(teleportLocation.getBlockX() + 0.5);
    teleportLocation.setZ(teleportLocation.getBlockZ() + 0.5);
    // Preserve the player's original orientation
    teleportLocation.setYaw(playerLocation.getYaw());
    teleportLocation.setPitch(playerLocation.getPitch());
    return teleportLocation;
  }

  /** Handles the Cloaker's invisibility ability. */
  private void handleCloakerInvisibility(Player player, UUID playerId, PlayerInteractEvent event) {
    int cooldownSeconds = config.getInt("hider-abilities.cloaker-cooldown", 20);

    if (isOnCooldown(playerId, cloakerCooldowns, cooldownSeconds)) {
      long remainingTime = getRemainingCooldown(playerId, cloakerCooldowns, cooldownSeconds);
      startUtilityCooldownBossBar(player, "Cloak", remainingTime);
      event.setCancelled(true);
      return;
    }

    // Store original block data if player is disguised and completely undisguise
    // them
    BlockData originalBlockData = null;
    boolean wasDisguised = DisguiseAPI.isDisguised(player);
    if (wasDisguised) {
      // Try to get block data from our map first, then from the disguise directly
      if (playerBlockData.containsKey(playerId)) {
        originalBlockData = playerBlockData.get(playerId);
      } else {
        // Fallback: extract block data from the current disguise
        MiscDisguise currentDisguise = (MiscDisguise) DisguiseAPI.getDisguise(player);
        if (currentDisguise != null) {
          FallingBlockWatcher watcher = (FallingBlockWatcher) currentDisguise.getWatcher();
          originalBlockData = watcher.getBlockData();
        }
      }

      // Debug logging
      if (originalBlockData != null) {
        plugin
            .getLogger()
            .info(
                "Cloaker "
                    + player.getName()
                    + " going invisible - storing block data: "
                    + originalBlockData.getMaterial().name());
      } else {
        plugin
            .getLogger()
            .warning("Cloaker " + player.getName() + " going invisible - no block data found!");
      }

      // Completely undisguise the player instead of setting to air block
      DisguiseAPI.undisguiseToAll(player);
    } else {
      // Debug logging for when no disguise is active
      plugin
          .getLogger()
          .info("Cloaker " + player.getName() + " going invisible - not currently disguised");
    }

    // Apply invisibility for 10 seconds
    player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 200, 0)); // 10 seconds

    // Visual and audio effects
    player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 20, 0.5, 1, 0.5, 0.1);
    player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.5f);

    player.sendMessage(
        Component.text("You are now invisible for 10 seconds!").color(NamedTextColor.GRAY));

    cloakerCooldowns.put(playerId, System.currentTimeMillis());

    // Store the original block data for restoration
    final BlockData finalOriginalBlockData = originalBlockData;
    final boolean finalWasDisguised = wasDisguised;

    // Restore block disguise when invisibility ends
    new BukkitRunnable() {
      @Override
      public void run() {
        if (player.isOnline() && finalWasDisguised && finalOriginalBlockData != null) {
          // Debug logging
          plugin
              .getLogger()
              .info(
                  "Restoring disguise for "
                      + player.getName()
                      + " with block data: "
                      + finalOriginalBlockData.getMaterial().name());

          // Re-create the block disguise completely
          MiscDisguise disguise = new MiscDisguise(DisguiseType.FALLING_BLOCK);
          disguise.setReplaceSounds(true);

          FallingBlockWatcher watcher = (FallingBlockWatcher) disguise.getWatcher();
          watcher.setBlockData(finalOriginalBlockData);

          // Apply the restored disguise
          DisguiseAPI.disguiseToAll(player, disguise);

          player.sendMessage(
              Component.text("Your block disguise has been restored.").color(NamedTextColor.GREEN));

          plugin.getLogger().info("Successfully restored disguise for " + player.getName());
        } else {
          // Debug why restoration didn't happen
          plugin
              .getLogger()
              .info(
                  "Disguise restoration skipped for "
                      + player.getName()
                      + " - Online: "
                      + player.isOnline()
                      + " - Was disguised: "
                      + finalWasDisguised
                      + " - Has block data: "
                      + (finalOriginalBlockData != null));
        }
      }
    }.runTaskLater(plugin, 200L); // 10 seconds

    event.setCancelled(true);
  }

  /** Handles block rotation when player left-clicks air while disguised as a block. */
  private void handleBlockRotation(Player player, PlayerInteractEvent event) {
    UUID playerId = player.getUniqueId();

    // Check if player is disguised
    if (!DisguiseAPI.isDisguised(player)) {
      return;
    }

    // Check if player has block data
    if (!playerBlockData.containsKey(playerId)) {
      return;
    }

    BlockData blockData = playerBlockData.get(playerId);

    boolean rotated = false;

    // Try different rotation interfaces
    if (blockData instanceof Directional) {
      // For stairs, slabs, doors, etc.
      Directional directional = (Directional) blockData;

      // Check if this block actually has multiple valid faces
      if (directional.getFaces().size() <= 1) {
        event.setCancelled(true);
        return;
      }

      org.bukkit.block.BlockFace currentFacing = directional.getFacing();

      org.bukkit.block.BlockFace[] faces = {
        org.bukkit.block.BlockFace.NORTH,
        org.bukkit.block.BlockFace.EAST,
        org.bukkit.block.BlockFace.SOUTH,
        org.bukkit.block.BlockFace.WEST
      };

      // Find current index and rotate to next
      int currentIndex = 0;
      for (int i = 0; i < faces.length; i++) {
        if (faces[i] == currentFacing) {
          currentIndex = i;
          break;
        }
      }

      int nextIndex = (currentIndex + 1) % faces.length;
      directional.setFacing(faces[nextIndex]);
      rotated = true;

      player.sendMessage(
          Component.text("Block rotated to face " + faces[nextIndex].name().toLowerCase())
              .color(NamedTextColor.GRAY));

    } else if (blockData instanceof Rotatable) {
      // For signs, banners, etc.
      Rotatable rotatable = (Rotatable) blockData;
      org.bukkit.block.BlockFace currentFacing = rotatable.getRotation();

      org.bukkit.block.BlockFace[] faces = {
        org.bukkit.block.BlockFace.NORTH,
        org.bukkit.block.BlockFace.EAST,
        org.bukkit.block.BlockFace.SOUTH,
        org.bukkit.block.BlockFace.WEST
      };

      // Find current index and rotate to next
      int currentIndex = 0;
      for (int i = 0; i < faces.length; i++) {
        if (faces[i] == currentFacing) {
          currentIndex = i;
          break;
        }
      }

      int nextIndex = (currentIndex + 1) % faces.length;
      rotatable.setRotation(faces[nextIndex]);
      rotated = true;

      player.sendMessage(
          Component.text("Block rotated to face " + faces[nextIndex].name().toLowerCase())
              .color(NamedTextColor.GRAY));

    } else if (blockData instanceof Orientable) {
      // For logs, hay bales, etc.
      Orientable orientable = (Orientable) blockData;
      org.bukkit.Axis currentAxis = orientable.getAxis();

      org.bukkit.Axis[] axes = {org.bukkit.Axis.X, org.bukkit.Axis.Y, org.bukkit.Axis.Z};

      // Find current index and rotate to next
      int currentIndex = 0;
      for (int i = 0; i < axes.length; i++) {
        if (axes[i] == currentAxis) {
          currentIndex = i;
          break;
        }
      }

      int nextIndex = (currentIndex + 1) % axes.length;
      orientable.setAxis(axes[nextIndex]);
      rotated = true;

      player.sendMessage(
          Component.text("Block oriented along " + axes[nextIndex].name().toLowerCase() + " axis")
              .color(NamedTextColor.GRAY));
    }

    // Update the disguise with new rotation if rotation was successful
    if (rotated && DisguiseAPI.isDisguised(player)) {
      MiscDisguise disguise = (MiscDisguise) DisguiseAPI.getDisguise(player);
      if (disguise != null) {
        FallingBlockWatcher watcher = (FallingBlockWatcher) disguise.getWatcher();
        watcher.setBlockData(blockData);

        // Visual and audio feedback
        player.playSound(player.getLocation(), Sound.BLOCK_WOOD_STEP, 0.5f, 1.5f);
      }
    }

    event.setCancelled(true);
  }

  /** Starts a boss bar showing the block disguise cooldown. */
  private void startBossBarCooldown(Player player, int cooldownSeconds) {
    // Remove any existing boss bar
    removeBossBar(player);

    // Create new boss bar
    BossBar bossBar = Bukkit.createBossBar("Block Disguise Cooldown", BarColor.RED, BarStyle.SOLID);

    bossBar.setProgress(0.0); // Start empty
    bossBar.addPlayer(player);

    UUID playerId = player.getUniqueId();
    playerBossBars.put(playerId, bossBar);

    // Create a task to update the boss bar
    BukkitTask task =
        new BukkitRunnable() {
          private double elapsed = 0;
          private final double totalTime = cooldownSeconds;

          @Override
          public void run() {
            if (!player.isOnline() || !isPlayerHider(player)) {
              removeBossBar(player);
              cancel();
              return;
            }

            elapsed += 0.1; // Update every 2 ticks (0.1 seconds)
            double progress = Math.min(elapsed / totalTime, 1.0);

            bossBar.setProgress(progress);

            if (progress >= 1.0) {
              // Cooldown complete
              bossBar.setTitle("Block Disguise Ready!");
              bossBar.setColor(BarColor.GREEN);

              // Remove boss bar after 2 seconds
              Bukkit.getScheduler().runTaskLater(plugin, () -> removeBossBar(player), 40L);
              cancel();
            } else {
              // Update title with remaining time
              long remainingSeconds = (long) Math.ceil(totalTime - elapsed);
              bossBar.setTitle("Block Disguise Cooldown: " + remainingSeconds + "s");
            }
          }
        }.runTaskTimer(plugin, 0L, 2L); // Update every 2 ticks

    bossBarTasks.put(playerId, task);
  }

  /** Starts a boss bar cooldown for utility items (shows remaining time from current cooldown). */
  private void startUtilityCooldownBossBar(
      Player player, String abilityName, long remainingSeconds) {
    // Remove any existing boss bar
    removeBossBar(player);

    // Create new boss bar
    BossBar bossBar =
        Bukkit.createBossBar(
            abilityName + " Cooldown: " + remainingSeconds + "s", BarColor.RED, BarStyle.SOLID);

    // Start with full progress (cooldown remaining) and decrease over time
    bossBar.setProgress(1.0);
    bossBar.addPlayer(player);

    UUID playerId = player.getUniqueId();
    playerBossBars.put(playerId, bossBar);

    // Store the initial remaining time for progress calculation
    final long initialTime = remainingSeconds;

    // Create a task to update the boss bar
    BukkitTask task =
        new BukkitRunnable() {
          private long timeLeft = remainingSeconds;

          @Override
          public void run() {
            if (!player.isOnline() || !isPlayerHider(player)) {
              removeBossBar(player);
              cancel();
              return;
            }

            timeLeft--;

            if (timeLeft <= 0) {
              // Cooldown complete
              bossBar.setTitle(abilityName + " Ready!");
              bossBar.setColor(BarColor.GREEN);
              bossBar.setProgress(1.0);

              // Remove boss bar after 2 seconds
              Bukkit.getScheduler().runTaskLater(plugin, () -> removeBossBar(player), 40L);
              cancel();
            } else {
              // Update title and progress with remaining time
              double progressValue = Math.max(0.0, (double) timeLeft / initialTime);
              bossBar.setProgress(progressValue);
              bossBar.setTitle(abilityName + " Cooldown: " + timeLeft + "s");
            }
          }
        }.runTaskTimer(plugin, 20L, 20L); // Update every second

    bossBarTasks.put(playerId, task);
  }

  /** Removes a player's boss bar and associated task. */
  private void removeBossBar(Player player) {
    UUID playerId = player.getUniqueId();

    // Remove boss bar
    BossBar bossBar = playerBossBars.remove(playerId);
    if (bossBar != null) {
      bossBar.removePlayer(player);
      bossBar.removeAll();
    }

    // Cancel task
    BukkitTask task = bossBarTasks.remove(playerId);
    if (task != null) {
      task.cancel();
    }
  }

  /** Returns true if this block type opens a UI or toggles state (hiders cannot use these). */
  private boolean isInteractiveBlock(Material material) {
    String name = material.name();
    if (name.contains("TRAPDOOR")
        || name.contains("_GATE")
        || name.contains("_BUTTON")
        || name.contains("PRESSURE_PLATE")
        || name.contains("SHULKER_BOX")) {
      return true;
    }
    switch (material) {
      case CHEST:
      case TRAPPED_CHEST:
      case BARREL:
      case FURNACE:
      case BLAST_FURNACE:
      case SMOKER:
      case CRAFTING_TABLE:
      case ANVIL:
      case CHIPPED_ANVIL:
      case DAMAGED_ANVIL:
      case ENCHANTING_TABLE:
      case SMITHING_TABLE:
      case GRINDSTONE:
      case LOOM:
      case CARTOGRAPHY_TABLE:
      case STONECUTTER:
      case LECTERN:
      case LEVER:
      case HOPPER:
      case DISPENSER:
      case DROPPER:
      case ENDER_CHEST:
      case BEACON:
        return true;
      default:
        return false;
    }
  }

  /** Checks if a block type is blacklisted for transformation. */
  private boolean isBlacklisted(Material material) {
    // Blacklist certain blocks that would be too powerful or cause issues
    return material == Material.BEDROCK
        || material == Material.BARRIER
        || material == Material.COMMAND_BLOCK
        || material == Material.STRUCTURE_BLOCK
        || material.name().contains("SHULKER_BOX")
        || material == Material.TNT
        || material == Material.SPAWNER
        // Blacklist all door types to prevent disguising as doors while allowing normal interaction
        || material.name().contains("_DOOR")
        // Also blacklist gates and similar interactive blocks that should work normally
        || material.name().contains("_GATE")
        || material.name().contains("TRAPDOOR");
  }

  /** Checks if a player is on cooldown for a specific ability. */
  private boolean isOnCooldown(UUID playerId, Map<UUID, Long> cooldownMap, int cooldownSeconds) {
    Long lastUsed = cooldownMap.get(playerId);
    if (lastUsed == null) {
      return false;
    }
    long currentTime = System.currentTimeMillis();
    long cooldownMillis = cooldownSeconds * 1000L;
    return (currentTime - lastUsed) < cooldownMillis;
  }

  /** Gets the remaining cooldown time for a player's ability. */
  private long getRemainingCooldown(
      UUID playerId, Map<UUID, Long> cooldownMap, int cooldownSeconds) {
    Long lastUsed = cooldownMap.get(playerId);
    if (lastUsed == null) {
      return 0;
    }
    long currentTime = System.currentTimeMillis();
    long cooldownMillis = cooldownSeconds * 1000L;
    long elapsedMillis = currentTime - lastUsed;
    long remainingMillis = cooldownMillis - elapsedMillis;
    return Math.max(0, remainingMillis / 1000);
  }

  /** Checks if a player is in any hunt world. */
  private boolean isPlayerInHuntWorld(Player player) {
    String playerWorldName = player.getWorld().getName();

    // Get the lobby world name from config
    String lobbyWorldName = config.getString("hunt.world", "hunt");

    // Allow disguising in the lobby world for testing/practice
    if (playerWorldName.equals(lobbyWorldName)) {
      return true;
    }

    // Check if the player is in any of the configured map worlds
    if (config.getConfigurationSection("hunt.maps") != null) {
      for (String mapKey : config.getConfigurationSection("hunt.maps").getKeys(false)) {
        String mapWorldName = config.getString("hunt.maps." + mapKey + ".world");
        if (mapWorldName != null && playerWorldName.equals(mapWorldName)) {
          if (plugin.getConfig().getBoolean("debug", false)) {
            plugin
                .getLogger()
                .info(
                    "Found player " + player.getName() + " in hunt game world: " + playerWorldName);
          }
          return true;
        }
      }
    }

    // Fallback: check if it contains "hunt" (for any hunt-related worlds)
    if (playerWorldName.toLowerCase().contains("hunt")) {
      if (plugin.getConfig().getBoolean("debug", false)) {
        plugin
            .getLogger()
            .info(
                "Found player "
                    + player.getName()
                    + " in hunt world (fallback): "
                    + playerWorldName);
      }
      return true;
    }

    return false;
  }

  /** Checks if a player is a hider. */
  private boolean isPlayerHider(Player player) {
    // Don't allow spectators to use abilities
    if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
      return false;
    }

    HuntPlayerData playerData = lobbyManager.getPlayerData(player.getUniqueId());
    if (playerData == null || playerData.getSelectedTeam() != HuntTeam.HIDERS) {
      return false;
    }
    return playerData.getSelectedHiderClass() != null;
  }

  /** Checks if a player is a hunter. */
  private boolean isPlayerHunter(Player player) {
    HuntPlayerData playerData = lobbyManager.getPlayerData(player.getUniqueId());
    if (playerData == null || playerData.getSelectedTeam() != HuntTeam.HUNTERS) {
      return false;
    }
    return playerData.getSelectedHunterClass() != null;
  }

  /** Gets the hider class for a player. */
  private HiderClass getPlayerHiderClass(Player player) {
    HuntPlayerData playerData = lobbyManager.getPlayerData(player.getUniqueId());
    return playerData != null ? playerData.getSelectedHiderClass() : null;
  }

  /** Returns the stored block data for a player's current disguise, or null if none. */
  public BlockData getPlayerBlockData(UUID playerId) {
    return playerBlockData.get(playerId);
  }

  /** Clears all cooldowns for a specific player. */
  public void clearPlayerCooldowns(UUID playerId) {
    tricksterCooldowns.remove(playerId);
    phaserCooldowns.remove(playerId);
    cloakerCooldowns.remove(playerId);
    blockDisguiseCooldowns.remove(playerId);

    // Clean up boss bar and block data
    Player player = Bukkit.getPlayer(playerId);
    if (player != null) {
      removeBossBar(player);
    }
    playerBlockData.remove(playerId);
  }

  /** Clears all cooldowns for all players. */
  public void clearAllCooldowns() {
    tricksterCooldowns.clear();
    phaserCooldowns.clear();
    cloakerCooldowns.clear();
    blockDisguiseCooldowns.clear();

    // Clean up all boss bars and block data
    for (BossBar bossBar : playerBossBars.values()) {
      bossBar.removeAll();
    }
    for (BukkitTask task : bossBarTasks.values()) {
      task.cancel();
    }
    playerBossBars.clear();
    bossBarTasks.clear();
    playerBlockData.clear();
  }
}
