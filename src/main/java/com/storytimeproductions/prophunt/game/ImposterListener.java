package com.storytimeproductions.prophunt.game;

import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/** Handles player interactions and events for the Imposter Hunt gamemode. */
public class ImposterListener implements Listener {
  private final JavaPlugin plugin;
  private final FileConfiguration config;
  private final ImposterGameManager gameManager;

  /**
   * Creates a new ImposterListener instance.
   *
   * @param plugin The plugin instance
   * @param config The configuration file
   * @param gameManager The imposter game manager
   */
  public ImposterListener(
      JavaPlugin plugin, FileConfiguration config, ImposterGameManager gameManager) {
    this.plugin = plugin;
    this.config = config;
    this.gameManager = gameManager;
  }

  /** Handles player interactions with Imposter items. */
  @EventHandler
  public void onPlayerInteract(PlayerInteractEvent event) {
    if (!gameManager.isGameActive()) {
      return;
    }

    Player player = event.getPlayer();
    ItemStack item = event.getItem();

    if (item == null || !item.hasItemMeta()) {
      return;
    }

    ImposterPlayerData playerData = gameManager.getPlayerData(player.getUniqueId());
    if (playerData == null || playerData.isDead()) {
      return;
    }

    // Only handle right-click interactions
    if (!event.getAction().toString().contains("RIGHT_CLICK")) {
      return;
    }

    Material material = item.getType();
    String displayName =
        item.getItemMeta().displayName() != null
            ? ((net.kyori.adventure.text.TextComponent) item.getItemMeta().displayName()).content()
            : "";

    switch (material) {
      case NETHER_STAR:
        if (displayName.contains("Throwable Weapon")) {
          handleThrowableWeapon(player, playerData, event);
        }
        break;
      case IRON_SWORD:
        if (displayName.contains("Sheriff Zapper")) {
          handleSheriffZapper(player, playerData, event);
        }
        break;
      case CLOCK:
        if (displayName.contains("Magnifying Glass")) {
          handleMagnifyingGlass(player, playerData, event);
        }
        break;
      case STICK:
        if (displayName.contains("Innocent Zapper")) {
          handleInnocentZapper(player, playerData, event);
        }
        break;
      default:
        // No special handling for other materials
        break;
    }
  }

  /** Handles the throwable weapon used by murderers. */
  private void handleThrowableWeapon(
      Player player, ImposterPlayerData playerData, PlayerInteractEvent event) {
    if (playerData.getRole() != ImposterRole.MURDERER) {
      player.sendMessage(Component.text("You cannot use this item!", NamedTextColor.RED));
      event.setCancelled(true);
      return;
    }

    if (!playerData.canUseThrowable(THROWABLE_COOLDOWN)) {
      player.sendMessage(Component.text("Throwable weapon is on cooldown!", NamedTextColor.RED));
      event.setCancelled(true);
      return;
    }

    // Launch a projectile
    Snowball projectile = player.launchProjectile(Snowball.class);
    projectile.setVelocity(player.getLocation().getDirection().multiply(2.0));

    // Mark the projectile as a throwable weapon
    projectile.setCustomName("ThrowableWeapon:" + player.getUniqueId().toString());

    // Set cooldown
    playerData.setLastThrowableUse(System.currentTimeMillis());

    // Visual and audio effects
    player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_SHOOT, 1.0f, 1.0f);
    player.getWorld().spawnParticle(Particle.LARGE_SMOKE, player.getLocation(), 10);

    event.setCancelled(true);

    plugin.getLogger().info("Murderer " + player.getName() + " threw a weapon");
  }

  /** Handles the sheriff's zapper weapon. */
  private void handleSheriffZapper(
      Player player, ImposterPlayerData playerData, PlayerInteractEvent event) {
    if (playerData.getRole() != ImposterRole.SHERIFF) {
      player.sendMessage(Component.text("You cannot use this item!", NamedTextColor.RED));
      event.setCancelled(true);
      return;
    }

    // Find the target player the sheriff is looking at
    Player target = getTargetPlayer(player, 10.0); // 10 block range

    if (target == null) {
      player.sendMessage(Component.text("No target in range!", NamedTextColor.RED));
      event.setCancelled(true);
      return;
    }

    ImposterPlayerData targetData = gameManager.getPlayerData(target.getUniqueId());
    if (targetData == null || targetData.isDead()) {
      player.sendMessage(Component.text("Invalid target!", NamedTextColor.RED));
      event.setCancelled(true);
      return;
    }

    // Zap the target
    zapPlayer(player, target, playerData, targetData);
    event.setCancelled(true);
  }

  /** Handles the innocent's zapper (purchased with coins). */
  private void handleInnocentZapper(
      Player player, ImposterPlayerData playerData, PlayerInteractEvent event) {
    if (playerData.getRole() != ImposterRole.INNOCENT) {
      player.sendMessage(Component.text("You cannot use this item!", NamedTextColor.RED));
      event.setCancelled(true);
      return;
    }

    if (!playerData.useZapper()) {
      player.sendMessage(Component.text("You don't have any zappers!", NamedTextColor.RED));
      event.setCancelled(true);
      return;
    }

    // Find the target player
    Player target = getTargetPlayer(player, 8.0); // 8 block range for innocent zappers

    if (target == null) {
      player.sendMessage(Component.text("No target in range!", NamedTextColor.RED));
      // Refund the zapper since no target was found
      playerData.addZappers(1);
      event.setCancelled(true);
      return;
    }

    ImposterPlayerData targetData = gameManager.getPlayerData(target.getUniqueId());
    if (targetData == null || targetData.isDead()) {
      player.sendMessage(Component.text("Invalid target!", NamedTextColor.RED));
      // Refund the zapper since target was invalid
      playerData.addZappers(1);
      event.setCancelled(true);
      return;
    }

    // Update the item to show remaining zappers
    updateZapperItem(player, playerData.getZappers());

    // Zap the target
    zapPlayer(player, target, playerData, targetData);
    event.setCancelled(true);
  }

  /** Handles the magnifying glass used by sheriffs to investigate gravestones. */
  private void handleMagnifyingGlass(
      Player player, ImposterPlayerData playerData, PlayerInteractEvent event) {
    if (playerData.getRole() != ImposterRole.SHERIFF) {
      player.sendMessage(Component.text("You cannot use this item!", NamedTextColor.RED));
      event.setCancelled(true);
      return;
    }

    if (!playerData.canInvestigate(INVESTIGATION_COOLDOWN)) {
      long remainingTime =
          INVESTIGATION_COOLDOWN
              - (System.currentTimeMillis() - playerData.getLastInvestigationTime()) / 1000;
      player.sendMessage(
          Component.text(
              "Investigation on cooldown for " + remainingTime + " seconds!", NamedTextColor.RED));
      event.setCancelled(true);
      return;
    }

    Block targetBlock = event.getClickedBlock();
    if (targetBlock == null || targetBlock.getType() != Material.PLAYER_HEAD) {
      player.sendMessage(Component.text("You must target a gravestone!", NamedTextColor.RED));
      event.setCancelled(true);
      return;
    }

    // Investigate the gravestone
    investigateGravestone(player, playerData, targetBlock.getLocation());
    event.setCancelled(true);
  }

  /** Handles projectile hits for throwable weapons. */
  @EventHandler
  public void onProjectileHit(ProjectileHitEvent event) {
    if (!gameManager.isGameActive()) {
      return;
    }

    if (!(event.getEntity() instanceof Snowball)) {
      return;
    }

    Snowball projectile = (Snowball) event.getEntity();
    String customName = projectile.getCustomName();

    if (customName == null || !customName.startsWith("ThrowableWeapon:")) {
      return;
    }

    // Extract the shooter's UUID
    String uuidString = customName.substring("ThrowableWeapon:".length());
    UUID shooterUuid;
    try {
      shooterUuid = UUID.fromString(uuidString);
    } catch (IllegalArgumentException e) {
      return;
    }

    Player shooter = Bukkit.getPlayer(shooterUuid);
    if (shooter == null) {
      return;
    }

    // Check if it hit a player
    Entity hitEntity = event.getHitEntity();
    if (hitEntity instanceof Player) {
      Player hitPlayer = (Player) hitEntity;

      // Don't allow self-damage
      if (hitPlayer.equals(shooter)) {
        return;
      }

      ImposterPlayerData hitData = gameManager.getPlayerData(hitPlayer.getUniqueId());
      if (hitData != null && !hitData.isDead()) {
        // Instant kill
        gameManager.handlePlayerDeath(hitPlayer, shooter);

        // Visual and audio effects
        Location hitLocation = hitPlayer.getLocation();
        hitLocation.getWorld().spawnParticle(Particle.EXPLOSION, hitLocation, 1);
        hitLocation.getWorld().playSound(hitLocation, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f);

        plugin
            .getLogger()
            .info("Throwable weapon from " + shooter.getName() + " hit " + hitPlayer.getName());
      }
    }

    // Create explosion effect at impact location
    Location impactLocation = projectile.getLocation();
    impactLocation.getWorld().spawnParticle(Particle.EXPLOSION, impactLocation, 5);
    impactLocation
        .getWorld()
        .playSound(impactLocation, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.5f, 1.0f);
  }

  /** Handles players picking up sheriff magnifying glass. */
  @EventHandler
  public void onPlayerPickupItem(PlayerPickupItemEvent event) {
    if (!gameManager.isGameActive()) {
      return;
    }

    Player player = event.getPlayer();
    ItemStack item = event.getItem().getItemStack();

    if (item.getType() != Material.CLOCK || !item.hasItemMeta()) {
      return;
    }

    String displayName =
        item.getItemMeta().displayName() != null
            ? ((net.kyori.adventure.text.TextComponent) item.getItemMeta().displayName()).content()
            : "";

    if (!displayName.contains("Sheriff Magnifying Glass")) {
      return;
    }

    ImposterPlayerData playerData = gameManager.getPlayerData(player.getUniqueId());
    if (playerData == null
        || playerData.isDead()
        || playerData.getRole() != ImposterRole.INNOCENT) {
      event.setCancelled(true);
      return;
    }

    // Convert innocent to sheriff
    playerData.setRole(ImposterRole.SHERIFF);
    player.sendMessage(Component.text("You are now the Sheriff!", NamedTextColor.BLUE));

    // Give sheriff items
    giveSheriffItems(player);

    // Remove the dropped item
    event.getItem().remove();
    event.setCancelled(true);

    plugin
        .getLogger()
        .info(player.getName() + " picked up sheriff magnifying glass and became sheriff");
  }

  /** Gets the player that another player is looking at within a certain range. */
  private Player getTargetPlayer(Player shooter, double range) {
    Location eyeLocation = shooter.getEyeLocation();
    Vector direction = eyeLocation.getDirection();

    Player closestPlayer = null;
    double closestDistance = range;

    for (Player player : shooter.getWorld().getPlayers()) {
      if (player.equals(shooter)) {
        continue;
      }

      ImposterPlayerData data = gameManager.getPlayerData(player.getUniqueId());
      if (data == null || data.isDead()) {
        continue;
      }

      Vector toPlayer = player.getLocation().toVector().subtract(eyeLocation.toVector());
      double distance = toPlayer.length();

      if (distance > range) {
        continue;
      }

      // Check if the player is roughly in the direction the shooter is looking
      double angle = direction.angle(toPlayer);
      if (angle < Math.PI / 4) { // Within 45 degrees
        if (distance < closestDistance) {
          closestPlayer = player;
          closestDistance = distance;
        }
      }
    }

    return closestPlayer;
  }

  /** Zaps a target player. */
  private void zapPlayer(
      Player shooter,
      Player target,
      ImposterPlayerData shooterData,
      ImposterPlayerData targetData) {
    // Visual and audio effects
    Location shooterLocation = shooter.getEyeLocation();
    Location targetLocation = target.getEyeLocation();

    // Create lightning effect
    shooter
        .getWorld()
        .spawnParticle(Particle.ELECTRIC_SPARK, targetLocation, 20, 0.5, 0.5, 0.5, 0.1);
    shooter.getWorld().playSound(targetLocation, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.5f, 2.0f);

    // Handle the death
    gameManager.handlePlayerDeath(target, shooter);

    plugin
        .getLogger()
        .info(
            shooter.getName()
                + " ("
                + shooterData.getRole()
                + ") zapped "
                + target.getName()
                + " ("
                + targetData.getRole()
                + ")");
  }

  /** Investigates a gravestone. */
  private void investigateGravestone(
      Player investigator, ImposterPlayerData investigatorData, Location gravestoneLocation) {
    // Check if this is actually a gravestone (this would need to be implemented in the game
    // manager)
    // For now, we'll simulate the investigation

    investigatorData.setLastInvestigationTime(System.currentTimeMillis());

    // Calculate time since death (simulated)
    long timeSinceDeath =
        System.currentTimeMillis() - (System.currentTimeMillis() - 60000); // Simulate 1 minute ago
    int secondsAgo = (int) (timeSinceDeath / 1000);

    String timeInfo;
    if (secondsAgo < 60) {
      timeInfo = secondsAgo + " seconds ago";
    } else {
      int minutesAgo = secondsAgo / 60;
      timeInfo = minutesAgo + " minute" + (minutesAgo > 1 ? "s" : "") + " ago";
    }

    investigator.sendMessage(Component.text("Investigation Result:", NamedTextColor.AQUA));
    investigator.sendMessage(Component.text("Time of death: " + timeInfo, NamedTextColor.YELLOW));

    // Visual and audio effects
    investigator
        .getWorld()
        .spawnParticle(Particle.HAPPY_VILLAGER, gravestoneLocation, 10, 1, 1, 1, 0.1);
    investigator.playSound(
        investigator.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.5f);

    plugin.getLogger().info("Sheriff " + investigator.getName() + " investigated a gravestone");
  }

  /** Gives sheriff items to a player. */
  private void giveSheriffItems(Player player) {
    player.getInventory().clear();

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

  /** Updates the zapper item to show remaining count. */
  private void updateZapperItem(Player player, int remainingZappers) {
    ItemStack zapper = new ItemStack(Material.STICK);
    ItemMeta meta = zapper.getItemMeta();
    meta.displayName(
        Component.text("Innocent Zapper (" + remainingZappers + " left)", NamedTextColor.GREEN));
    meta.lore(
        List.of(
            Component.text("Right-click to zap a player", NamedTextColor.GRAY),
            Component.text("Instantly kills the target", NamedTextColor.RED),
            Component.text("Be careful - killing innocents kills you too!", NamedTextColor.YELLOW),
            Component.text("Zappers remaining: " + remainingZappers, NamedTextColor.AQUA)));
    zapper.setItemMeta(meta);

    // Find the zapper in inventory and update it
    for (int i = 0; i < player.getInventory().getSize(); i++) {
      ItemStack item = player.getInventory().getItem(i);
      if (item != null && item.getType() == Material.STICK && item.hasItemMeta()) {
        String displayName =
            item.getItemMeta().displayName() != null
                ? ((net.kyori.adventure.text.TextComponent) item.getItemMeta().displayName())
                    .content()
                : "";
        if (displayName.contains("Innocent Zapper")) {
          player.getInventory().setItem(i, zapper);
          break;
        }
      }
    }
  }

  // Configuration constants
  private static final int THROWABLE_COOLDOWN = 10; // seconds
  private static final int INVESTIGATION_COOLDOWN = 30; // seconds
}
