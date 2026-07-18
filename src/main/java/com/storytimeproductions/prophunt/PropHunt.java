package com.storytimeproductions.prophunt;

import com.storytimeproductions.prophunt.commands.DisguiseCommand;
import com.storytimeproductions.prophunt.commands.HuntCommand;
import com.storytimeproductions.prophunt.game.HiderUtilityListener;
import com.storytimeproductions.prophunt.game.HuntAmbientTrackingListener;
import com.storytimeproductions.prophunt.game.HuntCleanupListener;
import com.storytimeproductions.prophunt.game.HuntDisguiseListener;
import com.storytimeproductions.prophunt.game.HuntDisguiseManager;
import com.storytimeproductions.prophunt.game.HuntDisguiseNpcManager;
import com.storytimeproductions.prophunt.game.HuntDisguisePassiveListener;
import com.storytimeproductions.prophunt.game.HuntGameModeCommand;
import com.storytimeproductions.prophunt.game.HuntGameModeHologramListener;
import com.storytimeproductions.prophunt.game.HuntGameModeManager;
import com.storytimeproductions.prophunt.game.HuntHologramClickListener;
import com.storytimeproductions.prophunt.game.HuntHologramManager;
import com.storytimeproductions.prophunt.game.HuntKitManager;
import com.storytimeproductions.prophunt.game.HuntLobbyListener;
import com.storytimeproductions.prophunt.game.HuntLobbyManager;
import com.storytimeproductions.prophunt.game.HuntPlayerJoinListener;
import com.storytimeproductions.prophunt.game.HuntPrepPhaseManager;
import com.storytimeproductions.prophunt.game.HuntSpotlightListener;
import com.storytimeproductions.prophunt.game.HuntUtilityListener;
import com.storytimeproductions.prophunt.game.ImposterCoinManager;
import com.storytimeproductions.prophunt.game.ImposterGameManager;
import com.storytimeproductions.prophunt.game.ImposterListener;
import com.storytimeproductions.prophunt.util.EntityDisguiseManager;
import java.io.File;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main plugin class for PropHunt. Constructs every manager and listener on {@link #onEnable()} and
 * wires their cross-dependencies together (most classes here need a handful of the others - see the
 * constructor calls and {@code setXxx} wiring in {@code onEnable()} for the exact dependency
 * graph), then registers the {@code /hunt}, {@code /huntgamemode}, and {@code /stdisguise} commands
 * from {@link com.storytimeproductions.prophunt.commands}.
 */
public class PropHunt extends JavaPlugin {

  private static PropHunt instance;
  private HuntHologramManager huntHologramManager;
  private HuntDisguiseManager huntDisguiseManager;
  private HuntDisguiseNpcManager huntDisguiseNpcManager;
  private HuntLobbyManager huntLobbyManager;
  private HuntPrepPhaseManager huntPrepPhaseManager;

  @Override
  public void onEnable() {
    instance = this;
    saveDefaultConfig();

    File huntConfigFile = new File(getDataFolder(), "hunt.yml");
    if (!huntConfigFile.exists()) {
      saveResource("hunt.yml", false);
    }
    final org.bukkit.configuration.file.FileConfiguration huntConfig =
        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(huntConfigFile);

    HuntGameModeManager huntGameModeManager = new HuntGameModeManager(this);

    huntHologramManager = new HuntHologramManager(this);
    huntDisguiseManager = new HuntDisguiseManager(this, huntGameModeManager);

    huntHologramManager.setDisguiseManager(huntDisguiseManager);

    Bukkit.getScheduler()
        .runTaskLater(
            this,
            () -> {
              huntHologramManager.initialize(huntConfig);
              huntGameModeManager.initializeGameModeHologram();
            },
            20L);

    huntDisguiseNpcManager =
        new HuntDisguiseNpcManager(this, huntDisguiseManager, huntHologramManager, huntConfig);
    getServer().getPluginManager().registerEvents(huntDisguiseNpcManager, this);
    Bukkit.getScheduler().runTaskLater(this, huntDisguiseNpcManager::loadAndSpawn, 40L);

    huntLobbyManager = new HuntLobbyManager();
    huntDisguiseManager.setLobbyManager(huntLobbyManager);

    HuntKitManager huntKitManager = new HuntKitManager(this);
    huntKitManager.setGameModeManager(huntGameModeManager);

    final ImposterGameManager imposterGameManager =
        new ImposterGameManager(this, huntConfig, huntLobbyManager);
    final ImposterListener imposterListener =
        new ImposterListener(this, huntConfig, imposterGameManager);
    final ImposterCoinManager imposterCoinManager =
        new ImposterCoinManager(this, huntConfig, imposterGameManager);

    huntPrepPhaseManager =
        new HuntPrepPhaseManager(
            this,
            huntLobbyManager,
            huntHologramManager,
            huntDisguiseManager,
            huntKitManager,
            huntConfig,
            huntGameModeManager,
            imposterGameManager);

    huntDisguiseManager.setPrepPhaseManager(huntPrepPhaseManager);

    huntGameModeManager.setDependencies(
        huntLobbyManager, huntKitManager, huntDisguiseManager, huntHologramManager);

    getServer()
        .getPluginManager()
        .registerEvents(new HuntLobbyListener(huntLobbyManager, huntGameModeManager), this);
    getServer()
        .getPluginManager()
        .registerEvents(
            new HuntPlayerJoinListener(huntDisguiseManager, huntKitManager, huntLobbyManager, this),
            this);
    getServer()
        .getPluginManager()
        .registerEvents(new HuntDisguiseListener(huntDisguiseManager, huntHologramManager), this);

    HuntUtilityListener huntUtilityListener =
        new HuntUtilityListener(this, huntConfig, huntLobbyManager);
    getServer().getPluginManager().registerEvents(huntUtilityListener, this);

    HuntDisguisePassiveListener huntDisguisePassiveListener =
        new HuntDisguisePassiveListener(this, huntDisguiseManager, huntLobbyManager);
    getServer().getPluginManager().registerEvents(huntDisguisePassiveListener, this);

    HiderUtilityListener hiderUtilityListener = new HiderUtilityListener(this, huntLobbyManager);
    getServer().getPluginManager().registerEvents(hiderUtilityListener, this);
    huntDisguisePassiveListener.setHiderUtilityListener(hiderUtilityListener);
    huntUtilityListener.setHiderUtilityListener(hiderUtilityListener);
    huntUtilityListener.setHuntDisguisePassiveListener(huntDisguisePassiveListener);
    huntUtilityListener.setPrepPhaseManager(huntPrepPhaseManager);

    // Passive ambient tracking (scratch marks / red stain equivalents) - not a Listener, just
    // needs constructing to start its own periodic task. See specs/ambient-tracking-layer.md.
    new HuntAmbientTrackingListener(this, huntConfig, huntLobbyManager);

    // Idle-triggered spotlight reveal - not a Listener, just needs constructing to start its own
    // periodic tasks. See specs/idle-spotlight.md.
    HuntSpotlightListener huntSpotlightListener =
        new HuntSpotlightListener(this, huntConfig, huntPrepPhaseManager, hiderUtilityListener);
    huntUtilityListener.setSpotlightListener(huntSpotlightListener);
    huntPrepPhaseManager.setSpotlightListener(huntSpotlightListener);

    getServer()
        .getPluginManager()
        .registerEvents(
            new HuntCleanupListener(
                this,
                huntHologramManager,
                huntKitManager,
                huntDisguiseManager,
                huntUtilityListener,
                huntDisguisePassiveListener,
                hiderUtilityListener,
                huntPrepPhaseManager,
                huntSpotlightListener,
                "hunt"),
            this);

    getServer()
        .getPluginManager()
        .registerEvents(new HuntGameModeHologramListener(this, huntGameModeManager), this);

    getServer().getPluginManager().registerEvents(imposterListener, this);
    getServer().getPluginManager().registerEvents(imposterCoinManager, this);

    EntityDisguiseManager disguiseManager = new EntityDisguiseManager(this);
    getCommand("stdisguise").setExecutor(new DisguiseCommand(disguiseManager));

    HuntCommand huntCommand =
        new HuntCommand(
            this,
            huntLobbyManager,
            huntHologramManager,
            huntKitManager,
            huntDisguiseManager,
            huntPrepPhaseManager,
            hiderUtilityListener,
            huntGameModeManager);
    getCommand("hunt").setExecutor(huntCommand);

    getCommand("huntgamemode").setExecutor(new HuntGameModeCommand(this, huntGameModeManager));

    getServer()
        .getPluginManager()
        .registerEvents(
            new HuntHologramClickListener(
                huntHologramManager, huntCommand, huntPrepPhaseManager, huntGameModeManager),
            this);

    initHuntDeathHandler(hiderUtilityListener);

    getLogger().info("PropHunt enabled!");
  }

  @Override
  public void onDisable() {
    if (huntDisguiseManager != null) {
      resetDisguisedHunterSizes();
      huntDisguiseManager.removeAllDisguises();
    }

    if (huntDisguiseNpcManager != null) {
      huntDisguiseNpcManager.despawnAll();
    }

    if (huntHologramManager != null) {
      huntHologramManager.despawnAllDisplays();
    }

    getLogger().info("PropHunt disabled!");
  }

  private void resetDisguisedHunterSizes() {
    for (java.util.UUID playerId : huntDisguiseManager.getDisguisedPlayers()) {
      org.bukkit.entity.Player player = Bukkit.getPlayer(playerId);
      if (player != null && player.isOnline()) {
        org.bukkit.attribute.AttributeInstance scaleAttr =
            player.getAttribute(org.bukkit.attribute.Attribute.SCALE);
        if (scaleAttr != null) {
          scaleAttr.setBaseValue(1.0);
        }
      }
    }
  }

  private void initHuntDeathHandler(HiderUtilityListener hiderUtilityListener) {
    try {
      File huntConfigFile = new File(getDataFolder(), "hunt.yml");
      if (!huntConfigFile.exists()) {
        saveResource("hunt.yml", false);
      }

      com.storytimeproductions.prophunt.game.HuntDeathHandler deathHandler =
          new com.storytimeproductions.prophunt.game.HuntDeathHandler(
              this, huntPrepPhaseManager, huntLobbyManager);

      getServer().getPluginManager().registerEvents(deathHandler, this);

      if (huntPrepPhaseManager != null) {
        huntPrepPhaseManager.setDeathHandler(deathHandler);
      }

      if (huntDisguiseManager != null) {
        deathHandler.setDisguiseManager(huntDisguiseManager);
      }

      deathHandler.setHiderUtilityListener(hiderUtilityListener);
    } catch (Exception e) {
      getLogger().severe("Failed to initialize HuntDeathHandler: " + e.getMessage());
      e.printStackTrace();
      try {
        com.storytimeproductions.prophunt.game.HuntDeathHandler deathHandler =
            new com.storytimeproductions.prophunt.game.HuntDeathHandler(this, null, null);
        getServer().getPluginManager().registerEvents(deathHandler, this);
      } catch (Exception ex) {
        getLogger().severe("HuntDeathHandler fallback also failed: " + ex.getMessage());
      }
    }
  }

  public static PropHunt getInstance() {
    return instance;
  }

  /**
   * Registers a Bukkit event listener with this plugin.
   *
   * @param listener The listener to register
   */
  public void registerListener(Listener listener) {
    Bukkit.getPluginManager().registerEvents(listener, this);
  }
}
