package com.storytimeproductions.prophunt.game;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import me.libraryaddict.disguise.DisguiseAPI;
import me.libraryaddict.disguise.disguisetypes.DisguiseType;
import me.libraryaddict.disguise.disguisetypes.MiscDisguise;
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;
import me.libraryaddict.disguise.disguisetypes.watchers.FallingBlockWatcher;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/** New implementation of passive abilities for hunter disguises with advanced mechanics. */
public class HuntDisguisePassiveListener implements Listener {

  // Delay (ticks) between Springtrap's aura triggering and the slow actually landing, giving
  // hiders in range a brief window to move away (see specs/ambient-tracking-layer.md).
  private static final long SPRINGTRAP_TELEGRAPH_DELAY_TICKS = 12L;
  // Same idea for Cryptid's blink - a warning cue before it actually teleports.
  private static final long CRYPTID_TELEGRAPH_DELAY_TICKS = 12L;

  private final JavaPlugin plugin;
  private final HuntDisguiseManager disguiseManager;
  private final HuntLobbyManager lobbyManager;
  private HiderUtilityListener hiderUtilityListener;
  private final Map<UUID, Location> lastPlayerLocations;
  private final Map<UUID, Long> playerStillTimes;
  private final Map<UUID, BukkitTask> passiveEffectTasks;

  // New tracking maps for advanced passive abilities
  private final Map<UUID, Entity> scarecrowClones; // Track Scarecrow clones
  private final Map<UUID, Map<UUID, Long>>
      slendermanLookTimes; // Track how long hiders look at Slenderman
  private final Map<UUID, Long> jigsawVanishTimes; // Track Jigsaw vanish start times
  private final Map<UUID, BukkitTask> springtrapAuraTasks; // Track Springtrap aura tasks
  private final Map<UUID, BukkitTask> herobrineGlowTasks; // Track Herobrine glow tasks
  private final Map<UUID, BukkitTask>
      slendermanParanoiaBarTasks; // Track Slenderman paranoia bar countdown tasks
  private final Map<UUID, Map<UUID, Long>>
      slendermanParanoiaCooldowns; // Track when hiders can be affected by paranoia
  // again

  // Wall-clock end time (millis) for an active idle ability's duration, so the unified action
  // bar can compute remaining time without needing per-tick sync with the countdown task.
  private final Map<UUID, Long> activeAbilityEndTimes;

  // Hider title tracking for "RUN" warnings
  private final Map<UUID, BukkitTask> hiderRunTitleTasks; // Track "RUN" title countdown tasks
  private final Map<UUID, Long> hiderSeenCooldowns; // Track cooldown for hiders being seen

  // Ability cooldown tracking
  private final Map<UUID, Long> abilityCooldowns; // Track when abilities can be used again

  // Track which players have active abilities to prevent multiple simultaneous
  // passives
  private final Map<UUID, Boolean> activeAbilities; // Track if a player has an active ability

  // Track abilities that are fully charged and ready to use
  private final Map<UUID, Boolean>
      chargedAbilities; // Track if a player has a charged ability ready

  // Slenderman paranoia task
  private BukkitTask slendermanParanoiaTask;

  // Global tick-based update task for all hunters
  private BukkitTask globalTickTask;

  /**
   * Constructs a new HuntDisguisePassiveListenerNew.
   *
   * @param plugin The JavaPlugin instance
   * @param disguiseManager The disguise manager to check player disguises
   * @param lobbyManager The lobby manager to check if players are hunters
   */
  public HuntDisguisePassiveListener(
      JavaPlugin plugin, HuntDisguiseManager disguiseManager, HuntLobbyManager lobbyManager) {
    this.plugin = plugin;
    this.disguiseManager = disguiseManager;
    this.lobbyManager = lobbyManager;
    this.lastPlayerLocations = new HashMap<>();
    this.playerStillTimes = new HashMap<>();
    this.passiveEffectTasks = new HashMap<>();
    this.scarecrowClones = new HashMap<>();
    this.slendermanLookTimes = new HashMap<>();
    this.jigsawVanishTimes = new HashMap<>();
    this.springtrapAuraTasks = new HashMap<>();
    this.herobrineGlowTasks = new HashMap<>();
    this.slendermanParanoiaBarTasks = new HashMap<>();
    this.slendermanParanoiaCooldowns = new HashMap<>();
    this.activeAbilityEndTimes = new HashMap<>();
    this.hiderRunTitleTasks = new HashMap<>();
    this.hiderSeenCooldowns = new HashMap<>();
    this.abilityCooldowns = new HashMap<>();
    this.activeAbilities = new HashMap<>();
    this.chargedAbilities = new HashMap<>();

    // Start Slenderman paranoia detection task (runs independently every second)
    startSlendermanParanoiaTask();

    // Start global tick-based update system for all hunters
    startGlobalTickTask();
  }

  public void setHiderUtilityListener(HiderUtilityListener listener) {
    this.hiderUtilityListener = listener;
  }

  /**
   * Handles player movement to track location changes and clean up non-hunters. Most logic is now
   * handled by the global tick task for better performance.
   *
   * @param event The PlayerMoveEvent
   */
  @EventHandler
  public void onPlayerMove(PlayerMoveEvent event) {
    Player player = event.getPlayer();
    UUID playerId = player.getUniqueId();

    // Log world info for debugging
    String worldName = player.getWorld().getName();
    if (worldName.toLowerCase().contains("hunt")) {
      plugin.getLogger().fine("Player " + player.getName() + " moved in hunt world: " + worldName);
    }

    // Only handle hunters with disguises
    boolean isHunter = isDisguisedHunter(player);
    if (!isHunter) {
      // Clean up tracking data if player is not a disguised hunter
      if (lastPlayerLocations.containsKey(playerId)) {
        plugin
            .getLogger()
            .info("Removing passive effects from " + player.getName() + " in world " + worldName);
        removePassiveEffects(player);
        lastPlayerLocations.remove(playerId);
        playerStillTimes.remove(playerId);
        abilityCooldowns.remove(playerId);
        activeAbilities.remove(playerId);
        activeAbilityEndTimes.remove(playerId);
        chargedAbilities.remove(playerId);
        cancelPassiveTask(playerId);
      }
      return;
    }

    // Simply update the last location - the global tick task handles the rest
    Location currentLocation = player.getLocation();
    lastPlayerLocations.put(playerId, currentLocation.clone());
  }

  /**
   * Applies passive abilities based on the disguise type and how long the player has been still.
   */
  private void applyPassiveAbility(
      Player player, HunterDisguiseType disguiseType, long stillDuration) {
    UUID playerId = player.getUniqueId();

    // Check if player already has an active ability to prevent multiple
    // simultaneous passives
    if (activeAbilities.getOrDefault(playerId, false)) {
      return;
    }

    switch (disguiseType) {
      case SPRINGTRAP:
        // Vengeful Echo: Slight slowness to hiders in a 5-block radius
        if (stillDuration >= 1) {
          applySpringtrapVengefulEcho(player);
        }
        break;

      case HEROBRINE:
        // Glitch Sight: Brief glowing effect on nearest hider within 10 blocks
        if (stillDuration >= 2) {
          applyHerobrineGlitchSight(player);
        }
        break;

      case SLENDERMAN:
        // Paranoia Aura: Handled in movement detection
        // No standing still requirement
        break;

      case CRYPTID:
        // Shadow Phase: Moves silently and leaves no footstep particles
        // Plus conditional long-range teleport when ability is charged and manually
        // activated
        if (stillDuration >= 3) {
          applyCryptidShadowPhase(player);
        }
        break;

      case SCARECROW:
        // Field of Fear: Create clone when standing still for 4+ seconds
        if (stillDuration >= 4) {
          applyScarecrowFieldOfFear(player);
        }
        break;

      case JIGSAW:
        // Puzzle Field: Vanish for 5 seconds when standing still
        if (stillDuration >= 3) {
          applyJigsawPuzzleField(player);
        }
        break;

      default:
        break;
    }

    // Note: Boss bar management is now handled in the global tick system
  }

  /** Springtrap - Vengeful Echo: Apply slowness to hiders in 5-block radius. */
  private void applySpringtrapVengefulEcho(Player springtrap) {
    UUID springtrapId = springtrap.getUniqueId();

    // Cancel existing task if any
    BukkitTask existingTask = springtrapAuraTasks.get(springtrapId);
    if (existingTask != null) {
      return; // Already running
    }

    // Mark player as having an active ability
    activeAbilities.put(springtrapId, true);

    BukkitTask auraTask =
        new BukkitRunnable() {
          @Override
          public void run() {
            if (!springtrap.isOnline() || !isDisguisedHunter(springtrap)) {
              springtrapAuraTasks.remove(springtrapId);
              activeAbilities.remove(springtrapId); // Clear active ability flag
              cancel();
              return;
            }

            List<Player> targetsInRange = new ArrayList<>();
            for (Player nearbyPlayer : springtrap.getWorld().getPlayers()) {
              if (!nearbyPlayer.equals(springtrap) && isPlayerHider(nearbyPlayer)) {
                double distance = nearbyPlayer.getLocation().distance(springtrap.getLocation());
                if (distance <= 5.0) {
                  targetsInRange.add(nearbyPlayer);
                }
              }
            }
            if (!targetsInRange.isEmpty()) {
              // Telegraph: warning sound/particle before the slow actually lands, giving hiders
              // in range a brief window to move out before it applies (see
              // specs/ambient-tracking-layer.md, "telegraphed windups").
              showRadiusParticleEffect(springtrap, 5.0);
              springtrap
                  .getWorld()
                  .playSound(springtrap.getLocation(), Sound.BLOCK_CONDUIT_ACTIVATE, 1.0f, 0.8f);

              springtrapAuraTasks.remove(springtrapId);
              cancel();

              new BukkitRunnable() {
                @Override
                public void run() {
                  for (Player target : targetsInRange) {
                    if (target.isOnline()
                        && isPlayerHider(target)
                        && target.getLocation().distance(springtrap.getLocation()) <= 5.0) {
                      target.addPotionEffect(
                          new PotionEffect(PotionEffectType.SLOWNESS, 60, 0)); // 3 seconds
                      disguiseManager.playCharacterScreech(springtrap);
                    }
                  }
                  // activeAbilities flag will be cleared by showAbilityUseDuration when the
                  // ability ends
                  showAbilityUseDuration(springtrap, HunterDisguiseType.SPRINGTRAP, 60);
                }
              }.runTaskLater(plugin, SPRINGTRAP_TELEGRAPH_DELAY_TICKS);
            }
          }
        }.runTaskTimer(plugin, 0L, 40L); // Every 2 seconds

    springtrapAuraTasks.put(springtrapId, auraTask);
  }

  /** Herobrine - Glitch Sight: Brief glowing effect on nearest hider within 10 blocks. */
  private void applyHerobrineGlitchSight(Player herobrine) {
    UUID herobrineId = herobrine.getUniqueId();

    // Cancel existing task if any
    BukkitTask existingTask = herobrineGlowTasks.get(herobrineId);
    if (existingTask != null) {
      return; // Already running
    }

    // Mark player as having an active ability
    activeAbilities.put(herobrineId, true);

    BukkitTask glowTask =
        new BukkitRunnable() {
          @Override
          public void run() {
            if (!herobrine.isOnline() || !isDisguisedHunter(herobrine)) {
              herobrineGlowTasks.remove(herobrineId);
              activeAbilities.remove(herobrineId); // Clear active ability flag
              cancel();
              return;
            }

            Player nearestHider = null;
            double nearestDistance = 10.0;

            for (Player nearbyPlayer : herobrine.getWorld().getPlayers()) {
              if (!nearbyPlayer.equals(herobrine) && isPlayerHider(nearbyPlayer)) {
                double distance = nearbyPlayer.getLocation().distance(herobrine.getLocation());
                if (distance <= 10.0 && distance < nearestDistance) {
                  nearestHider = nearbyPlayer;
                  nearestDistance = distance;
                }
              }
            }
            if (nearestHider != null) {
              showRadiusParticleEffect(herobrine, 10.0);

              // Strip the hider's block disguise — they must escape and re-disguise
              DisguiseAPI.undisguiseToAll(nearestHider);
              nearestHider.setGlowing(true);
              nearestHider.sendMessage(
                  Component.text("Herobrine sees you! Run and re-disguise!", NamedTextColor.RED));

              final Player exposed = nearestHider;
              new BukkitRunnable() {
                @Override
                public void run() {
                  if (exposed.isOnline()) {
                    exposed.setGlowing(false);
                  }
                }
              }.runTaskLater(plugin, 100L);

              disguiseManager.playCharacterScreech(herobrine);
              showAbilityUseDuration(herobrine, HunterDisguiseType.HEROBRINE, 100);

              herobrineGlowTasks.remove(herobrineId);
              cancel();
            }
          }
        }.runTaskTimer(plugin, 0L, 120L); // Every 6 seconds

    herobrineGlowTasks.put(herobrineId, glowTask);
  }

  /** Slenderman - Paranoia Aura: Detect when Slenderman looks at hiders. */
  private void startSlendermanParanoiaTask() {
    slendermanParanoiaTask =
        new BukkitRunnable() {
          @Override
          public void run() {
            handleSlendermanParanoia();
          }
        }.runTaskTimer(plugin, 20L, 20L); // Run every second
  }

  /** Handles Slenderman paranoia detection - Slenderman looking at hiders causes effects. */
  private void handleSlendermanParanoia() {
    long currentTime = System.currentTimeMillis();

    for (Player slenderman : Bukkit.getOnlinePlayers()) {
      if (!isDisguisedHunter(slenderman)) {
        continue;
      }

      HunterDisguiseType disguiseType = getPlayerDisguiseType(slenderman);
      if (disguiseType != HunterDisguiseType.SLENDERMAN) {
        continue;
      }

      UUID slendermanId = slenderman.getUniqueId();
      Map<UUID, Long> lookTimes =
          slendermanLookTimes.computeIfAbsent(slendermanId, k -> new HashMap<>());
      Map<UUID, Long> paranoiaCooldowns =
          slendermanParanoiaCooldowns.computeIfAbsent(slendermanId, k -> new HashMap<>());

      // Clean up expired cooldowns to prevent memory leaks
      paranoiaCooldowns.entrySet().removeIf(entry -> currentTime >= entry.getValue());

      for (Player hider : slenderman.getWorld().getPlayers()) {
        if (!hider.equals(slenderman) && isPlayerHider(hider)) {
          // Check if SLENDERMAN is looking at the HIDER (reversed logic)
          if (isPlayerLookingAt(slenderman, hider)) {
            UUID hiderId = hider.getUniqueId();

            // Check if hider is still on cooldown from a recent paranoia effect
            Long cooldownEnd = paranoiaCooldowns.get(hiderId);
            if (cooldownEnd != null && currentTime < cooldownEnd) {
              // Hider is still affected by paranoia, skip this hider
              continue;
            }

            // Check if Slenderman is currently showing a paranoia effect (bar is active)
            BukkitTask activeParanoiaBarTask = slendermanParanoiaBarTasks.get(slendermanId);
            if (activeParanoiaBarTask != null && !activeParanoiaBarTask.isCancelled()) {
              // Paranoia effect is already active, don't trigger again
              continue;
            }

            if (!lookTimes.containsKey(hiderId)) {
              lookTimes.put(hiderId, currentTime);
            } else {
              long lookDuration = currentTime - lookTimes.get(hiderId);
              if (lookDuration >= 1000) { // 1 second of Slenderman staring at hider
                // Apply paranoia effects to the hider
                hider.addPotionEffect(
                    new PotionEffect(PotionEffectType.NAUSEA, 100, 0)); // 5 seconds

                // Play hunter screech sound when effect is applied to hider
                disguiseManager.playCharacterScreech(slenderman);

                hider.playSound(hider.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1.0f, 1.0f);

                // Set cooldown for this hider (5 seconds to match effect duration)
                paranoiaCooldowns.put(hiderId, currentTime + 5000L);

                // Show "RUN" title to the hider
                showRunTitleToHider(hider, slenderman);

                // Fill Slenderman's boss bar on successful paranoia effect
                fillSlendermanParanoiaBar(slenderman);

                lookTimes.remove(hiderId); // Reset to prevent spam

                // Only affect one hider per trigger to prevent multiple simultaneous effects
                break;
              }
            }
          } else {
            lookTimes.remove(hider.getUniqueId());
          }
        }
      }
    }
  }

  /**
   * Marks Slenderman's paranoia effect as active for the unified ability status action bar when a
   * paranoia effect is successfully applied to a hider, clearing itself after the effect duration.
   */
  private void fillSlendermanParanoiaBar(Player slenderman) {
    UUID slendermanId = slenderman.getUniqueId();

    // Cancel any existing paranoia bar task to prevent overlap
    BukkitTask existingTask = slendermanParanoiaBarTasks.get(slendermanId);
    if (existingTask != null && !existingTask.isCancelled()) {
      existingTask.cancel();
    }

    // Match the paranoia effect duration (5 seconds / 100 ticks)
    final int totalTicks = 100;

    BukkitTask endTask =
        new BukkitRunnable() {
          @Override
          public void run() {
            slendermanParanoiaBarTasks.remove(slendermanId);
          }
        }.runTaskLater(plugin, totalTicks);

    slendermanParanoiaBarTasks.put(slendermanId, endTask);
  }

  /** Cryptid - Shadow Phase: Silent movement and conditional teleport. */
  private void handleCryptidSilentMovement(Player cryptid) {
    HunterDisguiseType disguiseType = getPlayerDisguiseType(cryptid);
    if (disguiseType == HunterDisguiseType.CRYPTID) {
      // Apply silent movement (no particles, reduced sound)
      cryptid.addPotionEffect(
          new PotionEffect(PotionEffectType.SPEED, 40, 0, true, false)); // Silent speed
    }
  }

  private void applyCryptidShadowPhase(Player cryptid) {
    // Check for nearby hiders and play screech if any are found within 5 blocks
    playScreechIfHidersNearby(cryptid, 5.0);

    // Lock in the target now, at cast time - long-range teleport when ability is activated
    Location currentLoc = cryptid.getLocation();
    Vector direction =
        currentLoc.getDirection().multiply(7); // Increased from 3 to 7 blocks forward
    Location teleportLoc = currentLoc.clone().add(direction);

    // Ensure safe teleport location
    if (teleportLoc.getBlock().getType().isSolid()) {
      teleportLoc = teleportLoc.add(0, 1, 0);
    }

    // Additional safety check - make sure we don't teleport into another solid
    // block
    for (int i = 0; i < 3; i++) {
      if (teleportLoc.clone().add(0, i, 0).getBlock().getType().isSolid()) {
        teleportLoc = teleportLoc.add(0, 1, 0);
      } else {
        break;
      }
    }

    // Telegraph: warning cue at the departure point before actually blinking away, giving
    // nearby hiders a moment to notice (see specs/ambient-tracking-layer.md).
    cryptid.getWorld().playSound(currentLoc, Sound.BLOCK_CONDUIT_ACTIVATE, 0.8f, 1.4f);

    Location finalTeleportLoc = teleportLoc;
    new BukkitRunnable() {
      @Override
      public void run() {
        if (!cryptid.isOnline()) {
          return;
        }
        cryptid.teleport(finalTeleportLoc);
        cryptid.playSound(cryptid.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.0f);
      }
    }.runTaskLater(plugin, CRYPTID_TELEGRAPH_DELAY_TICKS);

    showAbilityUseDuration(cryptid, HunterDisguiseType.CRYPTID, 100);
  }

  /** Scarecrow - Field of Fear: Create clone that detects nearby hiders. */
  private void applyScarecrowFieldOfFear(Player scarecrow) {
    UUID scarecrowId = scarecrow.getUniqueId();

    // Remove existing clone if any
    Entity existingClone = scarecrowClones.get(scarecrowId);
    if (existingClone != null && !existingClone.isDead()) {
      return; // Clone already exists
    }

    // Show radius particle effect when ability activates (10-block detection range)
    showRadiusParticleEffect(scarecrow, 10.0);

    String scarecrowSkin = disguiseManager.getPlayerDisguiseSkin(scarecrowId);
    if (scarecrowSkin != null) {
      try {
        PlayerDisguise disguise = new PlayerDisguise(scarecrowSkin);
        disguise.setReplaceSounds(false);
        disguise.setModifyBoundingBox(false);
        disguise.setNameVisible(false);

        double scarecrowScale = 1.3;
        if (plugin.getConfig().contains("hunt.disguise.player-scale")) {
          scarecrowScale = plugin.getConfig().getDouble("hunt.disguise.player-scale", 1.3);
        }
        disguise.getWatcher().setScale(scarecrowScale);
        disguise.getWatcher().setArmor(new org.bukkit.inventory.ItemStack[4]);
        disguise
            .getWatcher()
            .setItemInMainHand(new org.bukkit.inventory.ItemStack(org.bukkit.Material.AIR));
        disguise
            .getWatcher()
            .setItemInOffHand(new org.bukkit.inventory.ItemStack(org.bukkit.Material.AIR));

        // Apply the exact skin value/signature so the clone matches the NPC skin.
        String[] skinData = disguiseManager.getSkinDataForPlayer(scarecrowId);
        if (skinData != null) {
          try {
            io.papermc.paper.profile.MutablePropertyMap props =
                new io.papermc.paper.profile.MutablePropertyMap();
            props.put(
                "textures",
                new com.mojang.authlib.properties.Property("textures", skinData[0], skinData[1]));
            com.mojang.authlib.GameProfile profile =
                new com.mojang.authlib.GameProfile(
                    java.util.UUID.randomUUID(), "scarecrow_clone", props);
            disguise.setGameProfile(profile);
          } catch (Exception skinEx) {
            System.out.println(
                "[HuntPassive] Could not apply explicit skin to clone: " + skinEx.getMessage());
          }
        }

        DisguiseAPI.disguiseNextEntity(disguise);
      } catch (Exception e) {
        System.out.println("[HuntPassive] Failed to prepare scarecrow disguise: " + e.getMessage());
      }
    }

    Location cloneLocation = scarecrow.getLocation().clone();
    Creeper clone =
        scarecrow
            .getWorld()
            .spawn(
                cloneLocation,
                Creeper.class,
                c -> {
                  c.setAI(false);
                  c.setInvulnerable(true);
                  c.setPowered(false);
                  c.setCustomNameVisible(false);
                  c.setSilent(true);
                });

    scarecrowClones.put(scarecrowId, clone);

    // Schedule clone monitoring and removal
    new BukkitRunnable() {
      int ticks = 0;

      @Override
      public void run() {
        ticks++;

        if (clone.isDead() || ticks >= 200) { // 10 seconds
          if (!clone.isDead()) {
            clone.remove();
          }
          scarecrowClones.remove(scarecrowId);
          cancel();
          return;
        }

        // Move towards nearest hider every 2 ticks (~5.5 blocks/sec, sprint speed)
        if (ticks % 2 == 0) {
          Player nearestHider = null;
          double nearestDistance = Double.MAX_VALUE; // Initialize to max value
          final double detectionRange = 10.0; // Search within 10 blocks

          // Debug: List all players and their status
          for (Player nearbyPlayer : clone.getWorld().getPlayers()) {
            double distance = nearbyPlayer.getLocation().distance(clone.getLocation());
            boolean isHider = isPlayerHider(nearbyPlayer);

            if (isHider) {
              if (distance <= detectionRange && distance < nearestDistance) {
                nearestHider = nearbyPlayer;
                nearestDistance = distance;
              }
            }
          }

          // Move clone towards nearest hider
          if (nearestHider != null) {

            // Safety check - ensure clone is still valid
            if (clone.isDead()) {
              scarecrowClones.remove(scarecrowId);
              cancel();
              return;
            }

            Location cloneLocation = clone.getLocation();
            Location hiderLocation = nearestHider.getLocation();

            // Calculate direction vector (only X and Z, keep Y stable)
            Vector direction = hiderLocation.toVector().subtract(cloneLocation.toVector());
            direction.setY(0); // Remove Y component to prevent flying

            // Safety check for zero vector
            if (direction.lengthSquared() == 0) {
              return;
            }

            direction = direction.normalize();

            // 0.55 blocks every 2 ticks ≈ 5.5 blocks/sec (sprint speed)
            Vector movement = direction.multiply(0.55);
            Location newLocation = cloneLocation.add(movement);

            // Keep clone at roughly the same Y level as before, with minor ground
            // adjustment
            double targetY = cloneLocation.getY();

            // Only adjust Y if the block at the new location is solid
            if (newLocation.getBlock().getType().isSolid()) {
              targetY = newLocation.getY() + 1; // Move up one block if there's a solid block
            } else if (newLocation.clone().subtract(0, 1, 0).getBlock().getType().isAir()) {
              // If there's air below, lower the clone slightly (max 2 blocks down)
              for (int i = 1; i <= 2; i++) {
                Location checkLocation = newLocation.clone().subtract(0, i, 0);
                if (!checkLocation.getBlock().getType().isAir()) {
                  targetY = checkLocation.getY() + 1;
                  break;
                }
              }
            }

            newLocation.setY(targetY);

            // Safety check - make sure new location is in the same world
            if (newLocation.getWorld().equals(cloneLocation.getWorld())) {
              try {
                // Calculate yaw and pitch to look at the hider
                Vector lookDirection =
                    hiderLocation.toVector().subtract(newLocation.toVector()).normalize();
                float yaw =
                    (float) Math.toDegrees(Math.atan2(-lookDirection.getX(), lookDirection.getZ()));
                float pitch = (float) Math.toDegrees(Math.asin(-lookDirection.getY()));

                // Set the clone's rotation to face the hider
                newLocation.setYaw(yaw);
                newLocation.setPitch(pitch);

                clone.teleport(newLocation);

                // Check if clone is now very close to the hider (within 1.5 blocks)
                double newDistance = newLocation.distance(hiderLocation);
                if (newDistance <= 1.5) {

                  // Play dramatic sound and apply effects
                  clone
                      .getWorld()
                      .playSound(clone.getLocation(), Sound.ENTITY_PHANTOM_AMBIENT, 2.0f, 0.5f);

                  // Play faint explosion sound when clone catches hider
                  nearestHider
                      .getWorld()
                      .playSound(
                          nearestHider.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.3f, 0.8f);

                  // Strip the hider's disguise — they must escape and re-disguise
                  DisguiseAPI.undisguiseToAll(nearestHider);
                  nearestHider.setGlowing(true);
                  nearestHider.sendMessage(
                      Component.text(
                          "The Scarecrow's clone caught you! Run and re-disguise!",
                          NamedTextColor.RED));
                  final Player scExposed = nearestHider;
                  new BukkitRunnable() {
                    @Override
                    public void run() {
                      if (scExposed.isOnline()) {
                        scExposed.setGlowing(false);
                      }
                    }
                  }.runTaskLater(plugin, 100L);

                  // Play character-specific screech for the scarecrow
                  disguiseManager.playCharacterScreech(scarecrow);

                  // Flash-glitch "FEAR ME" title effect
                  showFearMeTitleFlash(nearestHider);

                  // Remove the clone
                  clone.remove();
                  scarecrowClones.remove(scarecrowId);
                  cancel();
                  return;
                }
              } catch (Exception e) {
                System.out.println("[HuntPassive] Failed to teleport clone: " + e.getMessage());
              }
            }
          }
        }

        // Check for detection every second
        if (ticks % 20 == 0) {
          for (Player nearbyPlayer : clone.getWorld().getPlayers()) {
            if (isPlayerHider(nearbyPlayer)) {
              double distance = nearbyPlayer.getLocation().distance(clone.getLocation());
              if (distance <= 2.0) {
                // Play screech and highlight hider
                clone
                    .getWorld()
                    .playSound(clone.getLocation(), Sound.ENTITY_PHANTOM_AMBIENT, 2.0f, 0.5f);
                // Screech is enough — no visual needed for proximity detection
                // Play hunter screech sound when effect is applied to hider
                disguiseManager.playCharacterScreech(scarecrow);
              }
            }
          }
        }
      }
    }.runTaskTimer(plugin, 0L, 1L);

    showAbilityUseDuration(scarecrow, HunterDisguiseType.SCARECROW, 200);
  }

  /** Jigsaw - Puzzle Field: Vanish for 5 seconds. */
  private void applyJigsawPuzzleField(Player jigsaw) {
    UUID jigsawId = jigsaw.getUniqueId();

    // Check if already vanished
    if (jigsawVanishTimes.containsKey(jigsawId)) {
      return;
    }

    jigsawVanishTimes.put(jigsawId, System.currentTimeMillis());

    // Check for nearby hiders and play screech if any are found within 5 blocks
    playScreechIfHidersNearby(jigsaw, 5.0);

    // Apply invisibility
    jigsaw.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 100, 0)); // 5 seconds
    jigsaw.playSound(jigsaw.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 0.1f);

    showAbilityUseDuration(jigsaw, HunterDisguiseType.JIGSAW, 100);

    // Schedule removal of vanish state
    Bukkit.getScheduler()
        .runTaskLater(
            plugin,
            () -> {
              jigsawVanishTimes.remove(jigsawId);
              jigsaw.playSound(jigsaw.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 2.0f);
            },
            100L);
  }

  /**
   * Applies armor hiding to hunters to maintain disguise immersion while keeping items in
   * inventory.
   */
  private void applyArmorHiding(Player player) {
    try {
      // Check if player has an active disguise
      if (DisguiseAPI.isDisguised(player)) {
        PlayerDisguise disguise = (PlayerDisguise) DisguiseAPI.getDisguise(player);
        if (disguise != null) {
          // Hide all armor visually while keeping it in inventory
          disguise
              .getWatcher()
              .setArmor(new org.bukkit.inventory.ItemStack[4]); // Empty armor slots
          disguise
              .getWatcher()
              .setItemInMainHand(new org.bukkit.inventory.ItemStack(org.bukkit.Material.AIR));
          disguise
              .getWatcher()
              .setItemInOffHand(new org.bukkit.inventory.ItemStack(org.bukkit.Material.AIR));
        }
      }
    } catch (Exception e) {
      System.out.println(
          "[HuntPassive] Failed to hide armor for " + player.getName() + ": " + e.getMessage());
    }
  }

  /** Uses the permanent idle hotbar to show ability duration (consumption). */
  private void showAbilityUseDuration(
      Player player, HunterDisguiseType disguiseType, int durationTicks) {
    UUID playerId = player.getUniqueId();

    // Mark player as having an active ability, and record when it ends so the unified ability
    // status action bar can compute remaining time on demand.
    activeAbilities.put(playerId, true);
    activeAbilityEndTimes.put(playerId, System.currentTimeMillis() + (durationTicks * 50L));

    new BukkitRunnable() {
      @Override
      public void run() {
        // Ability finished - clear active/charged flags so the next idle-charge cycle can start
        activeAbilities.remove(playerId);
        chargedAbilities.remove(playerId);
      }
    }.runTaskLater(plugin, durationTicks);
  }

  /**
   * Updates idle-charge state for a disguise's passive ability. Slenderman doesn't charge from idle
   * time (see {@link #fillSlendermanParanoiaBar}), so this is a no-op for it.
   */
  private void updateIdleProgress(
      Player player, HunterDisguiseType disguiseType, long currentIdleTime) {
    if (disguiseType == HunterDisguiseType.SLENDERMAN) {
      return;
    }

    UUID playerId = player.getUniqueId();
    long requiredIdleTime = getRequiredIdleTime(disguiseType);
    double progress = Math.min(1.0, (double) currentIdleTime / requiredIdleTime);

    if (progress >= 1.0 && !chargedAbilities.getOrDefault(playerId, false)) {
      chargedAbilities.put(playerId, true);
    }
  }

  /** Checks if a disguise type has idle-time related abilities. */
  private boolean hasIdleTimeAbility(HunterDisguiseType disguiseType) {
    return disguiseType == HunterDisguiseType.SPRINGTRAP
        || disguiseType == HunterDisguiseType.HEROBRINE
        || disguiseType == HunterDisguiseType.SLENDERMAN
        || disguiseType == HunterDisguiseType.CRYPTID
        || disguiseType == HunterDisguiseType.SCARECROW
        || disguiseType == HunterDisguiseType.JIGSAW;
  }

  /** Gets the required idle time for a disguise type's ability (in seconds). */
  private long getRequiredIdleTime(HunterDisguiseType disguiseType) {
    switch (disguiseType) {
      case SPRINGTRAP:
        return 1; // Vengeful Echo: 1 second
      case HEROBRINE:
        return 2; // Glitch Sight: 2 seconds
      case SLENDERMAN:
        return 999999; // Paranoia Aura: Always present, fills on effect, not idle time
      case CRYPTID:
        return 3; // Shadow Phase: 3 seconds
      case SCARECROW:
        return 4; // Field of Fear: 4 seconds
      case JIGSAW:
        return 3; // Puzzle Field: 3 seconds
      default:
        return 5; // Default fallback
    }
  }

  /** Gets the ability name for display in the hotbar. */
  private String getIdleAbilityName(HunterDisguiseType disguiseType) {
    switch (disguiseType) {
      case SPRINGTRAP:
        return "Vengeful Echo";
      case HEROBRINE:
        return "Glitch Sight";
      case SLENDERMAN:
        return "Paranoia Aura";
      case CRYPTID:
        return "Shadow Phase";
      case SCARECROW:
        return "Field of Fear";
      case JIGSAW:
        return "Puzzle Field";
      default:
        return "Passive Ability";
    }
  }

  /** Checks if a player is looking directly at another player. */
  private boolean isPlayerLookingAt(Player observer, Player target) {
    Vector observerDirection = observer.getEyeLocation().getDirection();
    Vector toTarget =
        target
            .getEyeLocation()
            .toVector()
            .subtract(observer.getEyeLocation().toVector())
            .normalize();

    double dot = observerDirection.dot(toTarget);
    return dot > 0.9; // Looking within ~25 degrees
  }

  /** Removes passive effects from a player. */
  private void removePassiveEffects(Player player) {
    UUID playerId = player.getUniqueId();

    // Cancel any running tasks
    BukkitTask springtrapTask = springtrapAuraTasks.remove(playerId);
    if (springtrapTask != null) {
      springtrapTask.cancel();
    }

    BukkitTask herobrineTask = herobrineGlowTasks.remove(playerId);
    if (herobrineTask != null) {
      herobrineTask.cancel();
    }

    // Cancel any Slenderman paranoia bar task
    BukkitTask paranoiaBarTask = slendermanParanoiaBarTasks.remove(playerId);
    if (paranoiaBarTask != null) {
      paranoiaBarTask.cancel();
    }

    // Cancel any hider "RUN" title task
    BukkitTask hiderRunTask = hiderRunTitleTasks.remove(playerId);
    if (hiderRunTask != null) {
      hiderRunTask.cancel();
    }

    // Clear any remaining title for the hider
    if (player != null) {
      player.clearTitle();
    }

    activeAbilityEndTimes.remove(playerId);

    // Remove scarecrow clone
    Entity clone = scarecrowClones.remove(playerId);
    if (clone != null && !clone.isDead()) {
      clone.remove();
    }

    // Clear tracking data
    slendermanLookTimes.remove(playerId);
    slendermanParanoiaCooldowns.remove(playerId);
    jigsawVanishTimes.remove(playerId);
    activeAbilities.remove(playerId);
  }

  /** Gets the disguise type for a player based on their current disguise. */
  private HunterDisguiseType getPlayerDisguiseType(Player player) {
    String disguiseDisplayName = disguiseManager.getPlayerDisguiseSkin(player.getUniqueId());
    if (disguiseDisplayName != null) {
      return HunterDisguiseType.fromDisplayName(disguiseDisplayName);
    }
    return null;
  }

  /** Checks if a player is a disguised hunter. */
  private boolean isDisguisedHunter(Player player) {
    // Don't allow spectators to use abilities
    if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
      return false;
    }

    // Debug to help diagnose issues
    plugin
        .getLogger()
        .fine(
            "Checking if player "
                + player.getName()
                + " is a disguised hunter in world: "
                + player.getWorld().getName());

    // Check if player is a hunter
    HuntPlayerData playerData = lobbyManager.getPlayerData(player.getUniqueId());
    if (playerData == null) {
      plugin.getLogger().fine("Player data is null for " + player.getName());
      return false;
    }

    if (playerData.getSelectedTeam() != HuntTeam.HUNTERS) {
      plugin.getLogger().fine("Player " + player.getName() + " is not on HUNTERS team");
      return false;
    }

    if (playerData.getSelectedHunterClass() == null) {
      plugin.getLogger().fine("Player " + player.getName() + " has no hunter class selected");
      return false;
    }

    // Check if player has an active disguise
    boolean isDisguised = disguiseManager.isPlayerDisguised(player.getUniqueId());
    if (!isDisguised) {
      plugin.getLogger().fine("Player " + player.getName() + " is not disguised");
    }

    return isDisguised;
  }

  private void applyBlockDisguiseGlow(Player hider, long durationTicks) {
    if (!(DisguiseAPI.getDisguise(hider) instanceof MiscDisguise)) {
      return;
    }
    // Use stored block data — FallingBlockWatcher.getBlockData() is broken on Paper 26
    var blockData =
        hiderUtilityListener != null
            ? hiderUtilityListener.getPlayerBlockData(hider.getUniqueId())
            : null;
    if (blockData == null) {
      return;
    }

    MiscDisguise glowing = new MiscDisguise(DisguiseType.FALLING_BLOCK);
    glowing.setReplaceSounds(true);
    var gw = (FallingBlockWatcher) glowing.getWatcher();
    gw.setBlockData(blockData);
    // Apply first so LibsDisguises registers the fake entity with observers,
    // then set glow so sendData targets the now-live entity ID
    DisguiseAPI.disguiseToAll(hider, glowing);
    gw.setGlowing(true);

    new BukkitRunnable() {
      @Override
      public void run() {
        if (!hider.isOnline()) {
          return;
        }
        MiscDisguise restore = new MiscDisguise(DisguiseType.FALLING_BLOCK);
        restore.setReplaceSounds(true);
        var rw = (FallingBlockWatcher) restore.getWatcher();
        rw.setBlockData(blockData);
        DisguiseAPI.disguiseToAll(hider, restore);
      }
    }.runTaskLater(plugin, durationTicks);
  }

  private boolean isPlayerHider(Player player) {
    // Don't allow spectators to be affected by or use abilities
    if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
      return false;
    }

    // Debug to help diagnose issues
    plugin
        .getLogger()
        .fine(
            "Checking if player "
                + player.getName()
                + " is a hider in world: "
                + player.getWorld().getName());

    HuntPlayerData playerData = lobbyManager.getPlayerData(player.getUniqueId());
    if (playerData == null) {
      plugin.getLogger().fine("Player data is null for " + player.getName());
      return false;
    }

    if (playerData.getSelectedTeam() != HuntTeam.HIDERS) {
      plugin.getLogger().fine("Player " + player.getName() + " is not on HIDERS team");
      return false;
    }
    return playerData.getSelectedHiderClass() != null;
  }

  /** Checks if a player has moved significantly. */
  private boolean hasMovedSignificantly(Location lastLocation, Location currentLocation) {
    if (!lastLocation.getWorld().equals(currentLocation.getWorld())) {
      return true;
    }
    return lastLocation.distance(currentLocation) > 0.1;
  }

  /** Cancels any passive effect task for a player. */
  private void cancelPassiveTask(UUID playerId) {
    BukkitTask task = passiveEffectTasks.remove(playerId);
    if (task != null) {
      task.cancel();
    }
  }

  /**
   * Starts the global tick-based update system that checks all hunters every tick. This ensures
   * idle progress and passive abilities are updated smoothly regardless of movement.
   */
  private void startGlobalTickTask() {
    globalTickTask =
        new BukkitRunnable() {
          @Override
          public void run() {
            // Check all online players every tick
            for (Player player : Bukkit.getOnlinePlayers()) {
              if (isDisguisedHunter(player)) {
                updateHunterState(player);
              } else {
                // Clean up any tracking data for players who are no longer disguised hunters
                UUID playerId = player.getUniqueId();
                if (lastPlayerLocations.containsKey(playerId)
                    || playerStillTimes.containsKey(playerId)
                    || abilityCooldowns.containsKey(playerId)
                    || activeAbilities.containsKey(playerId)) {
                  lastPlayerLocations.remove(playerId);
                  playerStillTimes.remove(playerId);
                  abilityCooldowns.remove(playerId);
                  activeAbilities.remove(playerId);
                  activeAbilityEndTimes.remove(playerId);
                  chargedAbilities.remove(playerId);
                }
              }
            }
          }
        }.runTaskTimer(plugin, 0L, 1L); // Run every tick (20 times per second)
  }

  /**
   * Updates a single hunter's state including idle progress and passive abilities. Called every
   * tick for all disguised hunters.
   */
  private void updateHunterState(Player player) {
    UUID playerId = player.getUniqueId();
    Location currentLocation = player.getLocation();
    Location lastLocation = lastPlayerLocations.get(playerId);

    // Apply armor hiding for all disguised hunters
    applyArmorHiding(player);

    HunterDisguiseType currentDisguiseType = getPlayerDisguiseType(player);

    // Check if player has moved significantly (more than 0.1 blocks)
    boolean hasMovedSignificantly =
        lastLocation == null || hasMovedSignificantly(lastLocation, currentLocation);

    if (hasMovedSignificantly) {
      // Player has moved - update location
      lastPlayerLocations.put(playerId, currentLocation.clone());

      // Only reset still time if the ability is not already fully charged
      if (!chargedAbilities.getOrDefault(playerId, false)) {
        playerStillTimes.remove(playerId);
      }

      // Handle movement-based abilities
      if (currentDisguiseType == HunterDisguiseType.CRYPTID) {
        handleCryptidSilentMovement(player);
      }

    } else {
      // Player hasn't moved significantly - track idle time
      if (!playerStillTimes.containsKey(playerId)) {
        playerStillTimes.put(playerId, System.currentTimeMillis());
      }

      // Update idle progress every tick only if no ability is currently active
      Long stillStartTime = playerStillTimes.get(playerId);
      if (stillStartTime != null && !activeAbilities.getOrDefault(playerId, false)) {
        long currentIdleTime = (System.currentTimeMillis() - stillStartTime) / 1000;

        if (currentDisguiseType != null && hasIdleTimeAbility(currentDisguiseType)) {
          updateIdleProgress(player, currentDisguiseType, currentIdleTime);
        }
      }
    }
  }

  /** Clears all passive effects and tasks for a specific player. */
  public void clearPlayerPassiveEffects(UUID playerId) {
    Player player = Bukkit.getPlayer(playerId);
    if (player != null) {
      removePassiveEffects(player);
    }

    lastPlayerLocations.remove(playerId);
    playerStillTimes.remove(playerId);
    abilityCooldowns.remove(playerId);
    activeAbilities.remove(playerId);
    chargedAbilities.remove(playerId);
    cancelPassiveTask(playerId);
  }

  /** Clears all passive effects and tasks for all players. */
  public void clearAllPassiveEffects() {
    // Remove passive effects from all tracked players
    for (UUID playerId : lastPlayerLocations.keySet()) {
      Player player = Bukkit.getPlayer(playerId);
      if (player != null) {
        removePassiveEffects(player);
      }
    }

    // Clear all tracking data
    lastPlayerLocations.clear();
    playerStillTimes.clear();
    passiveEffectTasks.values().forEach(BukkitTask::cancel);
    passiveEffectTasks.clear();
    springtrapAuraTasks.values().forEach(BukkitTask::cancel);
    springtrapAuraTasks.clear();
    herobrineGlowTasks.values().forEach(BukkitTask::cancel);
    herobrineGlowTasks.clear();

    // Remove all clones
    for (Entity clone : scarecrowClones.values()) {
      if (!clone.isDead()) {
        clone.remove();
      }
    }
    scarecrowClones.clear();

    slendermanLookTimes.clear();
    slendermanParanoiaCooldowns.clear();
    jigsawVanishTimes.clear();

    // Clear ability cooldowns
    abilityCooldowns.clear();

    // Clear active abilities
    activeAbilities.clear();
    activeAbilityEndTimes.clear();

    // Clear charged abilities
    chargedAbilities.clear();

    // Cancel Slenderman paranoia task
    if (slendermanParanoiaTask != null) {
      slendermanParanoiaTask.cancel();
    }

    // Cancel and clear all Slenderman paranoia bar tasks
    slendermanParanoiaBarTasks.values().forEach(BukkitTask::cancel);
    slendermanParanoiaBarTasks.clear();

    // Cancel and clear all hider "RUN" title tasks
    hiderRunTitleTasks.values().forEach(BukkitTask::cancel);
    hiderRunTitleTasks.clear();

    // Clear hider seen cooldowns
    hiderSeenCooldowns.clear();

    // Cancel global tick task
    if (globalTickTask != null) {
      globalTickTask.cancel();
      globalTickTask = null;
    }
  }

  /** Stops the Slenderman paranoia detection task. */
  public void stopSlendermanParanoiaTask() {
    if (slendermanParanoiaTask != null) {
      slendermanParanoiaTask.cancel();
      slendermanParanoiaTask = null;
    }
  }

  /** Stops the global tick task. */
  public void stopGlobalTickTask() {
    if (globalTickTask != null) {
      globalTickTask.cancel();
      globalTickTask = null;
    }
  }

  /**
   * Handles player shift detection to trigger passive abilities when the bar is full.
   *
   * @param event The PlayerToggleSneakEvent
   */
  @EventHandler
  public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
    Player player = event.getPlayer();
    UUID playerId = player.getUniqueId();

    // Only handle shift down (start sneaking) for disguised hunters
    if (!event.isSneaking() || !isDisguisedHunter(player)) {
      return;
    }

    // Check if player has a cooldown
    Long cooldownEnd = abilityCooldowns.get(playerId);
    if (cooldownEnd != null && System.currentTimeMillis() < cooldownEnd) {
      return;
    }

    HunterDisguiseType disguiseType = getPlayerDisguiseType(player);
    if (disguiseType == null || !hasIdleTimeAbility(disguiseType)) {
      return;
    }

    // Check if player has a charged ability ready to use
    if (!chargedAbilities.getOrDefault(playerId, false)) {
      return;
    }

    applyPassiveAbility(player, disguiseType, getRequiredIdleTime(disguiseType));

    // Set cooldown (2 seconds)
    abilityCooldowns.put(playerId, System.currentTimeMillis() + 2000L);

    // Clear charged ability and reset idle time to start charging again
    chargedAbilities.remove(playerId);
    playerStillTimes.put(playerId, System.currentTimeMillis());
  }

  /**
   * Shows a bold red "RUN" title message to a hider when they are spotted by a Slenderman.
   *
   * @param hider The hider who has been spotted
   * @param slenderman The Slenderman player who spotted the hider
   */
  private void showRunTitleToHider(Player hider, Player slenderman) {
    UUID hiderId = hider.getUniqueId();
    long currentTime = System.currentTimeMillis();

    // Check if hider is on cooldown (to prevent spam)
    Long cooldownEnd = hiderSeenCooldowns.get(hiderId);
    if (cooldownEnd != null && currentTime < cooldownEnd) {
      return; // Still on cooldown
    }

    // Cancel any existing title task
    BukkitTask existingTask = hiderRunTitleTasks.get(hiderId);
    if (existingTask != null) {
      existingTask.cancel();
      hiderRunTitleTasks.remove(hiderId);
    }

    // Set cooldown (5 seconds to match paranoia effect duration)
    hiderSeenCooldowns.put(hiderId, currentTime + 5000L);

    // Apply darkness effect to the hider for the paranoia duration
    hider.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 100, 0)); // 5 seconds

    // Play hunter screech sound when effect is applied to hider
    disguiseManager.playCharacterScreech(slenderman);

    // Create a blinking title task that shows/hides the title randomly
    BukkitTask blinkingTask =
        new BukkitRunnable() {
          private int ticksRemaining = 100; // 5 seconds total (100 ticks)
          private boolean titleVisible = false;
          private final Random random = new Random();

          @Override
          public void run() {
            if (ticksRemaining <= 0) {
              // Time's up, clear the title and finish
              hider.clearTitle();
              hiderRunTitleTasks.remove(hiderId);
              cancel();
              return;
            }

            // Randomly decide if title should be visible (70% chance to show when hidden,
            // 30% chance to hide when shown)
            boolean shouldShow;
            if (titleVisible) {
              shouldShow = random.nextDouble() > 0.3; // 70% chance to keep showing
            } else {
              shouldShow = random.nextDouble() < 0.7; // 70% chance to start showing
            }

            if (shouldShow && !titleVisible) {
              // Show the title with no fade (instant appearance)
              Title runTitle =
                  Title.title(
                      Component.text("HE'S HERE")
                          .color(NamedTextColor.DARK_RED)
                          .decorate(TextDecoration.BOLD),
                      Component.empty(), // No subtitle
                      Title.Times.times(
                          Duration.ofMillis(0), // No fade in
                          Duration.ofMillis(1000), // Stay for 1 second max
                          Duration.ofMillis(0) // No fade out
                          ));
              hider.showTitle(runTitle);
              titleVisible = true;
            } else if (!shouldShow && titleVisible) {
              // Hide the title
              hider.clearTitle();
              titleVisible = false;
            }

            ticksRemaining--;
          }
        }.runTaskTimer(plugin, 0L, 1L); // Run every tick for smooth blinking

    // Store the task for potential cancellation
    hiderRunTitleTasks.put(hiderId, blinkingTask);
  }

  /**
   * Shows a flash-glitch "FEAR ME" title effect when a scarecrow clone catches a hider.
   *
   * @param hider The hider who was caught by the clone
   */
  private void showFearMeTitleFlash(Player hider) {
    // Create a rapid flash-glitch effect for "FEAR ME" title
    new BukkitRunnable() {
      private int flashCount = 0;
      private final int totalFlashes = 8; // Total number of flashes (4 on/off cycles)

      @Override
      public void run() {
        if (flashCount >= totalFlashes) {
          // Clear title at the end
          hider.clearTitle();
          cancel();
          return;
        }

        boolean shouldShow = (flashCount % 2 == 0); // Show on even counts, hide on odd

        if (shouldShow) {
          // Show the "FEAR ME" title with dramatic styling
          Title fearTitle =
              Title.title(
                  Component.text("FEAR ME")
                      .color(NamedTextColor.DARK_RED)
                      .decorate(TextDecoration.BOLD),
                  Component.empty(), // No subtitle
                  Title.Times.times(
                      Duration.ofMillis(0), // No fade in
                      Duration.ofMillis(150), // Stay for 150ms
                      Duration.ofMillis(0) // No fade out
                      ));
          hider.showTitle(fearTitle);
        } else {
          // Hide the title for glitch effect
          hider.clearTitle();
        }

        flashCount++;
      }
    }.runTaskTimer(plugin, 0L, 3L); // Run every 3 ticks (150ms) for rapid flashing
  }

  /** Creates a circular red particle effect around a player to show ability radius. */
  private void showRadiusParticleEffect(Player player, double radius) {
    Location center = player.getLocation().add(0, 0.1, 0); // Slightly above ground

    // Create a circle of red particles
    for (int i = 0; i < 50; i++) { // 50 particles for a smooth circle
      double angle = 2 * Math.PI * i / 50;
      double x = center.getX() + radius * Math.cos(angle);
      double z = center.getZ() + radius * Math.sin(angle);
      Location particleLocation = new Location(center.getWorld(), x, center.getY(), z);

      // Spawn red particle (using DUST with red color)
      center
          .getWorld()
          .spawnParticle(
              Particle.DUST,
              particleLocation,
              1,
              new Particle.DustOptions(org.bukkit.Color.RED, 1.0f));
    }
  }

  /** Checks for nearby hiders within a radius and plays screech if any are found. */
  private void playScreechIfHidersNearby(Player hunter, double radius) {
    for (Player nearbyPlayer : hunter.getWorld().getPlayers()) {
      if (!nearbyPlayer.equals(hunter) && isPlayerHider(nearbyPlayer)) {
        double distance = nearbyPlayer.getLocation().distance(hunter.getLocation());
        if (distance <= radius) {
          // Play character-specific screech if hiders are nearby
          disguiseManager.playCharacterScreech(hunter);
          return; // Only play screech once per ability use
        }
      }
    }
  }

  /**
   * Returns this hunter's disguise-specific passive ability status for the unified ability status
   * action bar, or empty if they aren't a disguised hunter with an idle-time ability.
   *
   * @param player The player to check
   * @return The disguise ability's status, or empty
   */
  public java.util.Optional<AbilityStatus> getDisguiseAbilityStatus(Player player) {
    if (!isDisguisedHunter(player)) {
      return java.util.Optional.empty();
    }
    HunterDisguiseType disguiseType = getPlayerDisguiseType(player);
    if (disguiseType == null || !hasIdleTimeAbility(disguiseType)) {
      return java.util.Optional.empty();
    }

    UUID playerId = player.getUniqueId();
    String label = getIdleAbilityName(disguiseType).toUpperCase(java.util.Locale.ROOT);

    if (disguiseType == HunterDisguiseType.SLENDERMAN) {
      BukkitTask paranoiaTask = slendermanParanoiaBarTasks.get(playerId);
      boolean paranoiaActive = paranoiaTask != null && !paranoiaTask.isCancelled();
      return java.util.Optional.of(new AbilityStatus(label, false, 0, 0, paranoiaActive));
    }

    int requiredIdleTime = (int) getRequiredIdleTime(disguiseType);

    if (activeAbilities.getOrDefault(playerId, false)) {
      Long endTime = activeAbilityEndTimes.get(playerId);
      long remaining =
          endTime == null ? 0 : Math.max(0, (endTime - System.currentTimeMillis()) / 1000);
      return java.util.Optional.of(
          new AbilityStatus(label, true, remaining, requiredIdleTime, true));
    }

    if (chargedAbilities.getOrDefault(playerId, false)) {
      return java.util.Optional.of(new AbilityStatus(label, false, 0, requiredIdleTime, true));
    }

    Long stillStart = playerStillTimes.get(playerId);
    long currentIdleTime =
        stillStart == null ? 0 : (System.currentTimeMillis() - stillStart) / 1000;
    long remaining = Math.max(0, requiredIdleTime - currentIdleTime);
    return java.util.Optional.of(new AbilityStatus(label, true, remaining, requiredIdleTime, true));
  }
}
