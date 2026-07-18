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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rotatable;
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

/**
 * Listener that handles hider utility item abilities and block transformation. Each hider class has
 * a unique utility item with special abilities.
 */
public class HiderUtilityListener implements Listener {

  // Max distance (blocks) to ray trace forward looking for a wall to phase through.
  private static final int PHASE_MAX_DISTANCE = 4;
  // Default max wall thickness (blocks) to search through beyond the point of impact, used if
  // hider-abilities.phaser-max-wall-thickness isn't set in hunt.yml.
  private static final int PHASE_MAX_WALL_THICKNESS = 64;
  // Hard ceiling on the configured wall thickness, regardless of hunt.yml, so a misconfigured
  // value can't turn this into an unbounded per-block search.
  private static final int PHASE_MAX_WALL_THICKNESS_HARD_LIMIT = 256;

  private final JavaPlugin plugin;
  private final FileConfiguration config;
  private final Map<UUID, Long> tricksterCooldowns;
  private final Map<UUID, Long> phaserCooldowns;
  private final Map<UUID, Long> cloakerCooldowns;
  private final Map<UUID, Long> blockDisguiseCooldowns;
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
        // No right-click ability - Trickster's stun now triggers on landing a hit on a hunter,
        // handled by tryTricksterStrike() from HuntDeathHandler's PvP rule enforcement instead.
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
    int cooldownSeconds = config.getInt("hunt.hider-abilities.block-disguise-cooldown", 30);

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

  /**
   * Attempts Trickster's stun-on-hit ability: if the attacker is a Trickster and not on cooldown,
   * applies the same "locked in place and blinded" effect stack used for the round-start hunter
   * lock-in (see specs/trickster-stun-on-hit.md), scaled down to a few seconds, instead of the hit
   * dealing any real damage. Replaces the previous tripwire-trap ability entirely.
   *
   * @param attacker The hider attempting the strike
   * @param victim The hunter being struck
   * @return true if the stun was applied (caller should skip the generic "can't attack" message)
   */
  public boolean tryTricksterStrike(Player attacker, Player victim) {
    if (getPlayerHiderClass(attacker) != HiderClass.TRICKSTER) {
      return false;
    }

    UUID attackerId = attacker.getUniqueId();
    int cooldownSeconds = config.getInt("hunt.hider-abilities.trickster-strike-cooldown", 12);
    if (isOnCooldown(attackerId, tricksterCooldowns, cooldownSeconds)) {
      return false;
    }
    tricksterCooldowns.put(attackerId, System.currentTimeMillis());

    applyTricksterStunEffect(victim);

    attacker.playSound(attacker.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_ON, 1.0f, 1.0f);
    attacker.sendMessage(
        Component.text("You've stunned " + victim.getName() + "!").color(NamedTextColor.GREEN));
    victim.sendMessage(Component.text("A Trickster stunned you!").color(NamedTextColor.RED));
    return true;
  }

  /**
   * Applies the round-start hunter-lock-in effect stack, scaled to a short stun duration, plus a
   * full position-and-look freeze. Potion effects alone (even max-level slowness) stop movement but
   * not camera rotation, so a hunter could still look around freely during the stun - the repeating
   * re-teleport-to-frozen-location task below locks both, matching Nirav's "can't move nor look
   * anywhere" request 2026-07-17.
   */
  private void applyTricksterStunEffect(Player hunter) {
    int durationTicks =
        config.getInt("hunt.hider-abilities.trickster-stun-duration-seconds", 5) * 20;

    for (PotionEffectType effectType :
        new PotionEffectType[] {
          PotionEffectType.BLINDNESS,
          PotionEffectType.DARKNESS,
          PotionEffectType.SLOWNESS,
          PotionEffectType.WEAKNESS,
          PotionEffectType.MINING_FATIGUE
        }) {
      hunter.removePotionEffect(effectType);
    }

    hunter.addPotionEffect(
        new PotionEffect(PotionEffectType.BLINDNESS, durationTicks, 1, false, false, true));
    hunter.addPotionEffect(
        new PotionEffect(PotionEffectType.DARKNESS, durationTicks, 1, false, false, true));
    hunter.addPotionEffect(
        new PotionEffect(PotionEffectType.SLOWNESS, durationTicks, 255, false, false, true));
    hunter.addPotionEffect(
        new PotionEffect(PotionEffectType.WEAKNESS, durationTicks, 100, false, false, true));
    hunter.addPotionEffect(
        new PotionEffect(PotionEffectType.MINING_FATIGUE, durationTicks, 100, false, false, true));

    hunter.playSound(hunter.getLocation(), Sound.ENTITY_WITCH_CELEBRATE, 1.0f, 0.8f);

    Location frozenLocation = hunter.getLocation().clone();
    new BukkitRunnable() {
      int ticksRemaining = durationTicks;

      @Override
      public void run() {
        if (!hunter.isOnline() || ticksRemaining <= 0) {
          cancel();
          return;
        }
        hunter.teleport(frozenLocation);
        ticksRemaining--;
      }
    }.runTaskTimer(plugin, 0L, 1L);
  }

  /** Handles the Phaser's phase ability. */
  private void handlePhaserPhase(Player player, UUID playerId, PlayerInteractEvent event) {
    int cooldownSeconds = config.getInt("hunt.hider-abilities.phaser-cooldown", 30);

    if (isOnCooldown(playerId, phaserCooldowns, cooldownSeconds)) {
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
      player.playSound(startLocation, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);

      // Particle trail along the straight-line path through the wall, so the phase reads as
      // passing through it rather than two disconnected particle bursts.
      spawnPhaseTrail(startLocation, teleportLocation);

      // Teleport the player
      player.teleport(teleportLocation);

      // Visual and audio effects at destination
      player.getWorld().spawnParticle(Particle.PORTAL, teleportLocation, 30, 0.5, 1, 0.5, 1);
      player.playSound(teleportLocation, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);

      phaserCooldowns.put(playerId, System.currentTimeMillis());
    }
    // No valid phase location found - the ability status action bar square already shows
    // yellow (ready but not usable) instead of green when not looking straight at a wall, so
    // no separate failure message is needed here.

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

    // Step through along the wall's true perpendicular normal, not the player's raw look
    // direction - at an oblique angle, continuing along the look vector travels a longer
    // diagonal path than the wall's actual thickness, overshooting well past its far side. The
    // hit face's direction points outward from the wall (back toward the player), so negate it
    // to get the straight-through direction.
    org.bukkit.util.Vector wallNormal = hit.getHitBlockFace().getDirection().multiply(-1);

    // Only allow phasing when looking close to straight-on at the wall, not diagonally - the
    // dot product of two unit vectors is the cosine of the angle between them, so requiring it
    // stay above cos(maxAngle) rejects glancing/diagonal aims.
    double maxAngleDegrees =
        config.getDouble("hunt.hider-abilities.phaser-max-angle-degrees", 15.0);
    double angleCos = direction.dot(wallNormal);
    if (angleCos < Math.cos(Math.toRadians(maxAngleDegrees))) {
      return null; // Not looking straight-on at the wall
    }

    int maxWallThickness =
        Math.min(
            config.getInt(
                "hunt.hider-abilities.phaser-max-wall-thickness", PHASE_MAX_WALL_THICKNESS),
            PHASE_MAX_WALL_THICKNESS_HARD_LIMIT);
    // Small vertical search at each forward step, closest-to-original-height first. The wall may
    // open into a room whose floor isn't at exactly the same height as where the ray struck the
    // wall - without this, a real, close, obviously-usable floor gets skipped and the search
    // keeps walking forward looking for ground at the exact original height, landing far past
    // clearly good open space right behind the wall.
    int[] verticalOffsets = {0, -1, -2, -3, 1};
    for (int step = 0; step <= maxWallThickness; step++) {
      Location forward = wallEntry.clone().add(wallNormal.clone().multiply(step + 0.5));
      for (int verticalOffset : verticalOffsets) {
        Location candidate = forward.clone().add(0, verticalOffset, 0);
        if (isSafeLocation(candidate)) {
          return createTeleportLocation(candidate, playerLocation);
        }
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
   * Builds the final teleport location, preserving the player's original orientation.
   *
   * @param location The raw, already-validated location to teleport to
   * @param playerLocation The player's original location for yaw/pitch reference
   * @return A properly formatted teleport location
   */
  private Location createTeleportLocation(Location location, Location playerLocation) {
    Location teleportLocation = location.clone();
    // Keep the exact ray-computed X/Z - isSafeLocation() already validated this position's
    // block is safe, so re-centering here would only discard precision and drift the
    // destination off the player's exact facing direction (see specs/phaser-redesign-2.md).
    // Y does need snapping though: the ray hit position (and therefore this candidate) carries
    // whatever fractional height the wall was struck at, which floats the player above the
    // ground block's surface by that same fraction instead of standing flush on it.
    teleportLocation.setY(Math.floor(teleportLocation.getY()));
    // Preserve the player's original orientation
    teleportLocation.setYaw(playerLocation.getYaw());
    teleportLocation.setPitch(playerLocation.getPitch());
    return teleportLocation;
  }

  /**
   * Spawns a line of portal particles along the straight-line path from the phase start location to
   * the destination, so the ability visually reads as passing through the wall.
   *
   * @param from The phase start location
   * @param to The phase destination location
   */
  private void spawnPhaseTrail(Location from, Location to) {
    org.bukkit.util.Vector delta = to.toVector().subtract(from.toVector());
    double distance = delta.length();
    if (distance < 0.01) {
      return;
    }
    org.bukkit.util.Vector step = delta.clone().multiply(1.0 / (distance * 4.0));
    Location cursor = from.clone();
    int points = (int) Math.ceil(distance * 4.0);
    for (int i = 0; i <= points; i++) {
      from.getWorld().spawnParticle(Particle.PORTAL, cursor, 3, 0.1, 0.1, 0.1, 0.02);
      cursor.add(step);
    }
  }

  /** Handles the Cloaker's invisibility ability. */
  private void handleCloakerInvisibility(Player player, UUID playerId, PlayerInteractEvent event) {
    int cooldownSeconds = config.getInt("hunt.hider-abilities.cloaker-cooldown", 35);

    if (isOnCooldown(playerId, cloakerCooldowns, cooldownSeconds)) {
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

      if (plugin.getConfig().getBoolean("debug", false)) {
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
      }

      // Completely undisguise the player instead of setting to air block
      DisguiseAPI.undisguiseToAll(player);
    } else if (plugin.getConfig().getBoolean("debug", false)) {
      plugin
          .getLogger()
          .info("Cloaker " + player.getName() + " going invisible - not currently disguised");
    }

    // Apply invisibility - duration is deliberately shorter relative to its cooldown than it
    // used to be (was 10s/20s, ~50% uptime with no counterplay - see
    // specs/cloaker-balance.md), to bring its uptime ratio in line with the other hider
    // abilities.
    int durationSeconds = config.getInt("hunt.hider-abilities.cloaker-duration-seconds", 6);
    player.addPotionEffect(
        new PotionEffect(PotionEffectType.INVISIBILITY, durationSeconds * 20, 0));

    // Visual and audio effects
    player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 20, 0.5, 1, 0.5, 0.1);
    player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.5f);

    player.sendMessage(
        Component.text("You are now invisible for " + durationSeconds + " seconds!")
            .color(NamedTextColor.GRAY));

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

  /**
   * Returns this player's current hider ability statuses for the persistent action bar: their class
   * ability first, then block-disguise. Empty if they aren't a valid hider.
   *
   * @param player The player to check
   * @return The player's ability statuses, in display order
   */
  public List<AbilityStatus> getHiderAbilityStatuses(Player player) {
    if (!isPlayerHider(player)) {
      return List.of();
    }
    HiderClass hiderClass = getPlayerHiderClass(player);
    if (hiderClass == null) {
      return List.of();
    }

    List<AbilityStatus> statuses = new ArrayList<>();
    UUID playerId = player.getUniqueId();

    switch (hiderClass) {
      case TRICKSTER ->
          statuses.add(
              buildStatus(
                  "STUN", playerId, tricksterCooldowns, "trickster-strike-cooldown", 12, true));
      case PHASER ->
          statuses.add(
              buildStatus(
                  "PHASE",
                  playerId,
                  phaserCooldowns,
                  "phaser-cooldown",
                  30,
                  findPhaseLocation(player) != null));
      case CLOAKER ->
          statuses.add(
              buildStatus("CLOAK", playerId, cloakerCooldowns, "cloaker-cooldown", 20, true));
      default -> {
        // No action needed for unknown hider classes
      }
    }

    statuses.add(
        buildStatus(
            "DISGUISE", playerId, blockDisguiseCooldowns, "block-disguise-cooldown", 30, true));

    // Ambient victim-side status for hunter-applied debuffs (e.g. Springtrap's Vengeful Echo) -
    // gives the hider a persistent indicator of what's currently affecting them instead of
    // relying solely on a one-off chat message. See specs/ambient-tracking-layer.md.
    PotionEffect slowness = player.getPotionEffect(PotionEffectType.SLOWNESS);
    if (slowness != null) {
      int remainingSeconds = (int) Math.ceil(slowness.getDuration() / 20.0);
      statuses.add(new AbilityStatus("SLOWED", true, remainingSeconds, 3, true));
    }

    return statuses;
  }

  private AbilityStatus buildStatus(
      String label,
      UUID playerId,
      Map<UUID, Long> cooldownMap,
      String configKey,
      int defaultCooldown,
      boolean conditionMet) {
    int totalSeconds = config.getInt("hunt.hider-abilities." + configKey, defaultCooldown);
    boolean onCooldown = isOnCooldown(playerId, cooldownMap, totalSeconds);
    long remaining = onCooldown ? getRemainingCooldown(playerId, cooldownMap, totalSeconds) : 0;
    return new AbilityStatus(label, onCooldown, remaining, totalSeconds, conditionMet);
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
    // Round up, not down - flooring meant the last <1s of a cooldown displayed "0s" while the
    // ability was still genuinely on cooldown (isOnCooldown() checks remainingMillis > 0, not
    // remainingMillis >= 1000), which read as a bug: "ready" showing red/counting.
    return Math.max(0, (long) Math.ceil(remainingMillis / 1000.0));
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

  /**
   * Temporarily removes a player's block disguise (and any invisibility) without clearing the
   * stored block data, so {@link #reapplyStoredBlockDisguise(Player)} can restore the same disguise
   * afterward. Used by the idle-spotlight reveal (see specs/idle-spotlight.md) - does nothing if
   * the player isn't currently block-disguised.
   *
   * @param player The player to temporarily reveal
   */
  public void temporarilyUndisguise(Player player) {
    if (!playerBlockData.containsKey(player.getUniqueId())) {
      return;
    }
    if (DisguiseAPI.isDisguised(player)) {
      DisguiseAPI.undisguiseToAll(player);
    }
    if (player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
      player.removePotionEffect(PotionEffectType.INVISIBILITY);
    }
  }

  /**
   * Reapplies a player's previously stored block disguise, e.g. after {@link
   * #temporarilyUndisguise(Player)}. Does nothing if no block data is stored for them.
   *
   * @param player The player to re-disguise
   */
  public void reapplyStoredBlockDisguise(Player player) {
    BlockData blockData = playerBlockData.get(player.getUniqueId());
    if (blockData == null) {
      return;
    }
    if (DisguiseAPI.isDisguised(player)) {
      DisguiseAPI.undisguiseToAll(player);
    }
    MiscDisguise disguise = new MiscDisguise(DisguiseType.FALLING_BLOCK);
    disguise.setReplaceSounds(true);
    FallingBlockWatcher watcher = (FallingBlockWatcher) disguise.getWatcher();
    watcher.setBlockData(blockData);
    DisguiseAPI.disguiseToAll(player, disguise);
  }

  /** Clears all cooldowns for a specific player. */
  public void clearPlayerCooldowns(UUID playerId) {
    tricksterCooldowns.remove(playerId);
    phaserCooldowns.remove(playerId);
    cloakerCooldowns.remove(playerId);
    blockDisguiseCooldowns.remove(playerId);
    playerBlockData.remove(playerId);
  }

  /** Clears all cooldowns for all players. */
  public void clearAllCooldowns() {
    tricksterCooldowns.clear();
    phaserCooldowns.clear();
    cloakerCooldowns.clear();
    blockDisguiseCooldowns.clear();
    playerBlockData.clear();
  }
}
