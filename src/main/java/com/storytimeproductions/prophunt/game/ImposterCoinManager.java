package com.storytimeproductions.prophunt.game;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Manages Imposter Hunt's coin economy: periodically spawns pickup-able coin items around the map,
 * and credits a player's {@link ImposterPlayerData} balance on pickup. Coins are the currency
 * players spend on role-specific tools during a round.
 */
public class ImposterCoinManager implements Listener {
  private final JavaPlugin plugin;
  private final FileConfiguration config;
  private final ImposterGameManager gameManager;
  private final Random random;

  // Configuration values
  private static final int COINS_FOR_ZAPPER = 10;
  private static final int COIN_SPAWN_INTERVAL = 30; // seconds
  private static final int MAX_COINS_ON_MAP = 20;

  /**
   * Creates a new ImposterCoinManager instance.
   *
   * @param plugin The plugin instance
   * @param config The configuration file
   * @param gameManager The imposter game manager
   */
  public ImposterCoinManager(
      JavaPlugin plugin, FileConfiguration config, ImposterGameManager gameManager) {
    this.plugin = plugin;
    this.config = config;
    this.gameManager = gameManager;
    this.random = new Random();
  }

  /** Starts spawning coins on the map at regular intervals. */
  public void startCoinSpawning() {
    new BukkitRunnable() {
      @Override
      public void run() {
        if (!gameManager.isGameActive()) {
          cancel();
          return;
        }

        spawnRandomCoins();
      }
    }.runTaskTimer(plugin, 0L, COIN_SPAWN_INTERVAL * 20L);
  }

  /** Spawns random coins on the map. */
  private void spawnRandomCoins() {
    // Get all alive players to determine spawn areas
    java.util.List<Player> alivePlayers =
        gameManager.getAllPlayerData().entrySet().stream()
            .filter(entry -> !entry.getValue().isDead())
            .map(entry -> Bukkit.getPlayer(entry.getKey()))
            .filter(player -> player != null && player.isOnline())
            .collect(java.util.stream.Collectors.toList());

    if (alivePlayers.isEmpty()) {
      return;
    }

    // Count existing coins on the map
    int existingCoins = countCoinsOnMap();
    if (existingCoins >= MAX_COINS_ON_MAP) {
      return;
    }

    // Spawn 1-3 coins
    int coinsToSpawn = random.nextInt(3) + 1;
    for (int i = 0; i < coinsToSpawn && existingCoins + i < MAX_COINS_ON_MAP; i++) {
      spawnCoin();
    }
  }

  /** Spawns a single coin at a random location. */
  private void spawnCoin() {
    // Get a random alive player to spawn near
    java.util.List<Player> alivePlayers =
        gameManager.getAllPlayerData().entrySet().stream()
            .filter(entry -> !entry.getValue().isDead())
            .map(entry -> Bukkit.getPlayer(entry.getKey()))
            .filter(player -> player != null && player.isOnline())
            .collect(java.util.stream.Collectors.toList());

    if (alivePlayers.isEmpty()) {
      return;
    }

    Player randomPlayer = alivePlayers.get(random.nextInt(alivePlayers.size()));
    Location baseLocation = randomPlayer.getLocation();

    // Find a safe location within 20 blocks
    Location spawnLocation = findSafeSpawnLocation(baseLocation, 20);
    if (spawnLocation == null) {
      return;
    }

    // Create coin item
    ItemStack coin = createCoinItem();

    // Spawn the coin
    Item droppedCoin = spawnLocation.getWorld().dropItem(spawnLocation, coin);
    droppedCoin.setCustomName("ImposterCoin");
    droppedCoin.setPickupDelay(20); // 1 second delay before pickup

    // Visual effects
    spawnLocation
        .getWorld()
        .spawnParticle(Particle.HAPPY_VILLAGER, spawnLocation, 5, 0.3, 0.3, 0.3, 0.1);
    spawnLocation
        .getWorld()
        .playSound(spawnLocation, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f);

    plugin
        .getLogger()
        .info(
            "Spawned coin at "
                + spawnLocation.getBlockX()
                + ", "
                + spawnLocation.getBlockY()
                + ", "
                + spawnLocation.getBlockZ());
  }

  /** Creates a coin item. */
  private ItemStack createCoinItem() {
    ItemStack coin = new ItemStack(Material.GOLD_NUGGET);
    ItemMeta meta = coin.getItemMeta();
    meta.displayName(Component.text("Coin", NamedTextColor.GOLD));
    meta.lore(
        List.of(
            Component.text(
                "Collect " + COINS_FOR_ZAPPER + " coins to buy a zapper!", NamedTextColor.YELLOW),
            Component.text("Right-click to spend coins", NamedTextColor.GRAY)));
    coin.setItemMeta(meta);
    return coin;
  }

  /** Finds a safe location to spawn coins near a base location. */
  private Location findSafeSpawnLocation(Location baseLocation, int radius) {
    for (int attempts = 0; attempts < 10; attempts++) {
      // Random offset within radius
      double x = baseLocation.getX() + (random.nextDouble() - 0.5) * radius * 2;
      double z = baseLocation.getZ() + (random.nextDouble() - 0.5) * radius * 2;

      // Find the highest solid block at this X,Z coordinate
      for (int y = baseLocation.getWorld().getMaxHeight() - 1; y > 0; y--) {
        Location testLocation = new Location(baseLocation.getWorld(), x, y, z);
        if (testLocation.getBlock().getType().isSolid()) {
          // Found solid ground, spawn one block above
          Location spawnLocation = testLocation.clone().add(0, 1, 0);
          if (spawnLocation.getBlock().getType().isAir()) {
            return spawnLocation;
          }
        }
      }
    }
    return null;
  }

  /** Counts the number of coins currently on the map. */
  private int countCoinsOnMap() {
    int count = 0;

    // Get all players' worlds and count coins
    for (UUID playerId : gameManager.getAllPlayerData().keySet()) {
      Player player = Bukkit.getPlayer(playerId);
      if (player != null && player.isOnline()) {
        for (Item item : player.getWorld().getEntitiesByClass(Item.class)) {
          if ("ImposterCoin".equals(item.getCustomName())) {
            count++;
          }
        }
        break; // Only need to check one world
      }
    }

    return count;
  }

  /** Handles players picking up coins. */
  @EventHandler
  public void onPlayerPickupItem(PlayerPickupItemEvent event) {
    if (!gameManager.isGameActive()) {
      return;
    }

    Item item = event.getItem();
    if (!"ImposterCoin".equals(item.getCustomName())) {
      return;
    }

    Player player = event.getPlayer();
    ImposterPlayerData playerData = gameManager.getPlayerData(player.getUniqueId());

    if (playerData == null || playerData.isDead()) {
      event.setCancelled(true);
      return;
    }

    // Add coin to player's total
    playerData.addCoins(1);

    // Check if player can buy a zapper
    boolean canBuyZapper = playerData.getCoins() >= COINS_FOR_ZAPPER;

    // Notify player
    Component message =
        Component.text("Coin collected! ", NamedTextColor.GOLD)
            .append(
                Component.text(
                    "(" + playerData.getCoins() + "/" + COINS_FOR_ZAPPER + ")",
                    NamedTextColor.YELLOW));

    if (canBuyZapper && playerData.getRole() == ImposterRole.INNOCENT) {
      message = message.append(Component.text(" - You can buy a zapper!", NamedTextColor.GREEN));
      giveZapperToBuy(player, playerData);
    }

    player.sendMessage(message);

    // Visual and audio effects
    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
    player
        .getWorld()
        .spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation(), 5, 0.5, 0.5, 0.5, 0.1);

    plugin
        .getLogger()
        .info(player.getName() + " collected a coin (total: " + playerData.getCoins() + ")");
  }

  /** Gives a zapper purchase item to an innocent player. */
  private void giveZapperToBuy(Player player, ImposterPlayerData playerData) {
    // Check if player already has a zapper purchase item
    for (ItemStack item : player.getInventory().getContents()) {
      if (item != null && item.getType() == Material.EMERALD && item.hasItemMeta()) {
        String displayName =
            item.getItemMeta().displayName() != null
                ? ((net.kyori.adventure.text.TextComponent) item.getItemMeta().displayName())
                    .content()
                : "";
        if (displayName.contains("Buy Zapper")) {
          return; // Player already has a purchase item
        }
      }
    }

    // Create zapper purchase item
    ItemStack zapperBuy = new ItemStack(Material.EMERALD);
    ItemMeta meta = zapperBuy.getItemMeta();
    meta.displayName(
        Component.text("Buy Zapper (" + COINS_FOR_ZAPPER + " coins)", NamedTextColor.GREEN));
    meta.lore(
        List.of(
            Component.text("Right-click to buy a zapper", NamedTextColor.GRAY),
            Component.text("Cost: " + COINS_FOR_ZAPPER + " coins", NamedTextColor.YELLOW),
            Component.text("You have: " + playerData.getCoins() + " coins", NamedTextColor.AQUA)));
    zapperBuy.setItemMeta(meta);

    // Add to player's inventory
    player.getInventory().addItem(zapperBuy);
  }

  /** Handles purchasing zappers with coins. */
  public void handleZapperPurchase(Player player, ImposterPlayerData playerData) {
    if (playerData.getRole() != ImposterRole.INNOCENT) {
      player.sendMessage(Component.text("Only innocents can buy zappers!", NamedTextColor.RED));
      return;
    }

    if (playerData.getCoins() < COINS_FOR_ZAPPER) {
      player.sendMessage(
          Component.text(
              "You need " + COINS_FOR_ZAPPER + " coins to buy a zapper!", NamedTextColor.RED));
      return;
    }

    // Remove coins
    playerData.removeCoins(COINS_FOR_ZAPPER);
    playerData.addZappers(1);

    // Give zapper item
    giveZapperItem(player, playerData.getZappers());

    // Remove purchase item
    removePurchaseItem(player);

    player.sendMessage(
        Component.text("Zapper purchased! ", NamedTextColor.GREEN)
            .append(
                Component.text(
                    "(Remaining coins: " + playerData.getCoins() + ")", NamedTextColor.YELLOW)));

    // Visual and audio effects
    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1.0f, 1.0f);
    player
        .getWorld()
        .spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation(), 10, 0.5, 0.5, 0.5, 0.1);

    plugin
        .getLogger()
        .info(player.getName() + " purchased a zapper for " + COINS_FOR_ZAPPER + " coins");
  }

  /** Gives a zapper item to a player. */
  private void giveZapperItem(Player player, int zapperCount) {
    ItemStack zapper = new ItemStack(Material.STICK);
    ItemMeta meta = zapper.getItemMeta();
    meta.displayName(
        Component.text("Innocent Zapper (" + zapperCount + " left)", NamedTextColor.GREEN));
    meta.lore(
        List.of(
            Component.text("Right-click to zap a player", NamedTextColor.GRAY),
            Component.text("Instantly kills the target", NamedTextColor.RED),
            Component.text("Be careful - killing innocents kills you too!", NamedTextColor.YELLOW),
            Component.text("Zappers remaining: " + zapperCount, NamedTextColor.AQUA)));
    zapper.setItemMeta(meta);

    player.getInventory().addItem(zapper);
  }

  /** Removes the zapper purchase item from a player's inventory. */
  private void removePurchaseItem(Player player) {
    for (int i = 0; i < player.getInventory().getSize(); i++) {
      ItemStack item = player.getInventory().getItem(i);
      if (item != null && item.getType() == Material.EMERALD && item.hasItemMeta()) {
        String displayName =
            item.getItemMeta().displayName() != null
                ? ((net.kyori.adventure.text.TextComponent) item.getItemMeta().displayName())
                    .content()
                : "";
        if (displayName.contains("Buy Zapper")) {
          player.getInventory().setItem(i, null);
          break;
        }
      }
    }
  }

  /** Clears all coins from the map. */
  public void clearAllCoins() {
    for (UUID playerId : gameManager.getAllPlayerData().keySet()) {
      Player player = Bukkit.getPlayer(playerId);
      if (player != null && player.isOnline()) {
        for (Item item : player.getWorld().getEntitiesByClass(Item.class)) {
          if ("ImposterCoin".equals(item.getCustomName())) {
            item.remove();
          }
        }
        break; // Only need to check one world
      }
    }
  }
}
