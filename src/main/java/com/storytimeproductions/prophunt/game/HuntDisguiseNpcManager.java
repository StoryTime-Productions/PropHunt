package com.storytimeproductions.prophunt.game;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import io.papermc.paper.profile.MutablePropertyMap;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Spawns fake-player NPCs for the Hunt disguise preview stands using client-only entity packets.
 * Identical approach to Eden's NpcManager — no server-side entities, purely packet-based.
 */
public class HuntDisguiseNpcManager implements Listener {

  private record DisguiseNpc(String id, Location location, String displayName, String skinName) {}

  private final JavaPlugin plugin;
  private final HuntDisguiseManager disguiseManager;
  private final HuntHologramManager hologramManager;
  private final FileConfiguration huntConfig;

  private final List<DisguiseNpc> npcs = new ArrayList<>();
  private final Map<String, Integer> npcEntityIds = new HashMap<>();
  private final Map<Integer, String> fakeEntityToNpcId = new HashMap<>();
  private final Map<String, String[]> skinCache = new HashMap<>();
  private final List<Interaction> npcInteractions = new ArrayList<>();
  private final Map<UUID, String> npcInteractionMap = new HashMap<>();
  private final NamespacedKey npcInteractionKey;
  private final NamespacedKey npcInteractionIdKey;

  // Use a different range from Eden's 100_000_000 counter
  private static final AtomicInteger FAKE_ID_COUNTER = new AtomicInteger(200_000_000);

  /**
   * Constructs a new HuntDisguiseNpcManager.
   *
   * @param plugin The plugin instance
   * @param disguiseManager The disguise manager to route clicks to
   * @param hologramManager The hologram manager (passed to interaction handler)
   * @param huntConfig The loaded hunt.yml configuration
   */
  public HuntDisguiseNpcManager(
      JavaPlugin plugin,
      HuntDisguiseManager disguiseManager,
      HuntHologramManager hologramManager,
      FileConfiguration huntConfig) {
    this.plugin = plugin;
    this.disguiseManager = disguiseManager;
    this.hologramManager = hologramManager;
    this.huntConfig = huntConfig;
    this.npcInteractionKey = new NamespacedKey(plugin, "hunt_npc_interaction");
    this.npcInteractionIdKey = new NamespacedKey(plugin, "hunt_npc_interaction_id");
  }

  /** Loads NPC data from config and fetches skins. Call once after the world is loaded. */
  public void loadAndSpawn() {
    npcs.clear();
    npcEntityIds.clear();
    fakeEntityToNpcId.clear();
    npcInteractions.forEach(
        i -> {
          if (i.isValid()) {
            i.remove();
          }
        });
    npcInteractions.clear();
    npcInteractionMap.clear();
    disguiseManager.clearDisguiseLocations();

    String worldName = huntConfig.getString("hunt.world", "hunt");
    World world = Bukkit.getWorld(worldName);
    if (world == null) {
      plugin
          .getLogger()
          .warning("Hunt world '" + worldName + "' not found — disguise NPCs skipped");
      return;
    }

    cleanupLegacyEntities(world);

    ConfigurationSection locations = huntConfig.getConfigurationSection("hunt.disguise-locations");
    if (locations == null) {
      plugin.getLogger().warning("No disguise locations configured in hunt.yml");
      return;
    }

    for (String locationId : locations.getKeys(false)) {
      ConfigurationSection cfg = locations.getConfigurationSection(locationId);
      if (cfg == null || !cfg.getBoolean("enabled", true)) {
        continue;
      }

      String skinName = cfg.getString("player-skin", "Steve");
      String skinValue = cfg.getString("skin-value");
      String skinSignature = cfg.getString("skin-signature");

      if (skinValue != null && skinSignature != null) {
        skinCache.put(skinName, new String[] {skinValue, skinSignature});
      } else {
        fetchSkinAsync(skinName);
      }

      int fakeId = FAKE_ID_COUNTER.incrementAndGet();
      npcEntityIds.put(locationId, fakeId);
      fakeEntityToNpcId.put(fakeId, locationId);

      String displayName = cfg.getString("display-name", skinName);
      Location npcLoc =
          new Location(
              world,
              cfg.getDouble("x"),
              cfg.getDouble("y"),
              cfg.getDouble("z"),
              (float) cfg.getDouble("yaw", 0.0),
              0f);
      npcs.add(new DisguiseNpc(locationId, npcLoc, displayName, skinName));
      disguiseManager.registerDisguiseLocation(
          locationId, skinName, displayName, cfg.getDouble("scale", 1.3), skinValue, skinSignature);
    }

    // Spawn server-side Interaction entities for reliable right-click detection
    for (DisguiseNpc npc : npcs) {
      String npcId = npc.id();
      Interaction interaction =
          world.spawn(
              npc.location(),
              Interaction.class,
              i -> {
                i.setInteractionWidth(1.2f);
                i.setInteractionHeight(2.0f);
                i.setPersistent(false);
                i.getPersistentDataContainer()
                    .set(npcInteractionKey, PersistentDataType.BYTE, (byte) 1);
                i.getPersistentDataContainer()
                    .set(npcInteractionIdKey, PersistentDataType.STRING, npcId);
              });
      npcInteractions.add(interaction);
      npcInteractionMap.put(interaction.getUniqueId(), npcId);
    }

    // Send appearance packets to any already-online players in the hunt world
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (isInHuntWorld(player)) {
        for (DisguiseNpc npc : npcs) {
          sendAppearance(player, npc);
        }
      }
    }

    plugin.getLogger().info("Hunt disguise NPCs loaded: " + npcs.size());
  }

  /** Removes all fake NPC entities from all clients and cleans up server-side interactions. */
  public void despawnAll() {
    for (DisguiseNpc npc : npcs) {
      Integer fakeId = npcEntityIds.get(npc.id());
      if (fakeId == null) {
        continue;
      }
      for (Player player : Bukkit.getOnlinePlayers()) {
        ((CraftPlayer) player)
            .getHandle()
            .connection
            .send(new ClientboundRemoveEntitiesPacket(fakeId));
      }
    }
    npcInteractions.forEach(
        i -> {
          if (i.isValid()) {
            i.remove();
          }
        });
    npcInteractions.clear();
    npcInteractionMap.clear();
  }

  // ─── Client-side appearance via NMS ──────────────────────────────────────

  private void sendAppearance(Player viewer, DisguiseNpc npc) {
    Integer fakeId = npcEntityIds.get(npc.id());
    if (fakeId == null) {
      return;
    }

    String[] skin = skinCache.get(npc.skinName());
    UUID npcUuid =
        UUID.nameUUIDFromBytes(("hunt_npc_" + npc.id()).getBytes(StandardCharsets.UTF_8));

    GameProfile profile = buildProfile(npcUuid, npc.displayName(), skin);
    net.minecraft.server.level.ServerPlayer nmsViewer = ((CraftPlayer) viewer).getHandle();
    Location loc = npc.location();

    // 1 — Register fake profile so the client loads the skin texture (not listed in tab)
    var entry =
        new ClientboundPlayerInfoUpdatePacket.Entry(
            npcUuid,
            profile,
            false,
            0,
            net.minecraft.world.level.GameType.SURVIVAL,
            net.minecraft.network.chat.Component.empty(),
            false,
            0,
            null);
    nmsViewer.connection.send(
        new ClientboundPlayerInfoUpdatePacket(
            EnumSet.of(
                ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED),
            List.of(entry)));

    // 2 — Spawn the NPC as a PLAYER entity with a client-only entity ID
    float yaw = loc.getYaw();
    nmsViewer.connection.send(
        new ClientboundAddEntityPacket(
            fakeId,
            npcUuid,
            loc.getX(),
            loc.getY(),
            loc.getZ(),
            0f,
            yaw,
            net.minecraft.world.entity.EntityType.PLAYER,
            0,
            Vec3.ZERO,
            (double) yaw));

    // 3 — Enable all outer skin layers (hat, jacket, sleeves, pants)
    var skinLayerValue =
        new SynchedEntityData.DataValue<>(
            Avatar.DATA_PLAYER_MODE_CUSTOMISATION.id(),
            Avatar.DATA_PLAYER_MODE_CUSTOMISATION.serializer(),
            (byte) 0x7F);
    nmsViewer.connection.send(new ClientboundSetEntityDataPacket(fakeId, List.of(skinLayerValue)));

    // 4 — Scale to 1.3×
    var scaleAttr = new AttributeInstance(Attributes.SCALE, a -> {});
    scaleAttr.setBaseValue(1.3);
    nmsViewer.connection.send(new ClientboundUpdateAttributesPacket(fakeId, List.of(scaleAttr)));

    // 5 — Remove fake profile from tab list after client has loaded skin
    plugin
        .getServer()
        .getScheduler()
        .runTaskLater(
            plugin,
            () ->
                nmsViewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(npcUuid))),
            40L);
  }

  private void cleanupLegacyEntities(World world) {
    // Remove invisible, fixed armor stands from the old disguise-stand system
    world.getEntitiesByClass(ArmorStand.class).stream()
        .filter(e -> e.isInvisible() && !e.hasGravity())
        .forEach(Entity::remove);
    // Remove stale NPC interaction entities from previous sessions
    world.getEntitiesByClass(Interaction.class).stream()
        .filter(e -> e.getPersistentDataContainer().has(npcInteractionKey, PersistentDataType.BYTE))
        .forEach(Entity::remove);
  }

  // ─── Bukkit event listeners ──────────────────────────────────────────────

  /** Sends NPC appearance packets to a player when they join, if they're in the hunt world. */
  @EventHandler
  public void onPlayerJoin(PlayerJoinEvent event) {
    plugin
        .getServer()
        .getScheduler()
        .runTaskLater(
            plugin,
            () -> {
              Player player = event.getPlayer();
              if (isInHuntWorld(player)) {
                for (DisguiseNpc npc : npcs) {
                  sendAppearance(player, npc);
                }
              }
            },
            20L);
  }

  /** Re-sends NPC appearance packets when a player enters the hunt world. */
  @EventHandler
  public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
    Player player = event.getPlayer();
    String huntWorld = huntConfig.getString("hunt.world", "hunt");
    if (!player.getWorld().getName().equals(huntWorld)) {
      return;
    }

    plugin
        .getServer()
        .getScheduler()
        .runTaskLater(
            plugin,
            () -> {
              if (!player.isOnline()) {
                return;
              }
              for (DisguiseNpc npc : npcs) {
                sendAppearance(player, npc);
              }
            },
            20L);
  }

  /** Routes right-clicks on NPC Interaction entities to the disguise manager. */
  @EventHandler
  public void onPlayerInteract(PlayerInteractAtEntityEvent event) {
    String locationId =
        event
            .getRightClicked()
            .getPersistentDataContainer()
            .get(npcInteractionIdKey, PersistentDataType.STRING);
    if (locationId == null) {
      return;
    }
    event.setCancelled(true);
    disguiseManager.handleDisguiseInteraction(event.getPlayer(), locationId, hologramManager);
  }

  // ─── Skin fetching via Mojang API ────────────────────────────────────────

  private void fetchSkinAsync(String skinName) {
    if (skinCache.containsKey(skinName)) {
      return;
    }
    plugin
        .getServer()
        .getScheduler()
        .runTaskAsynchronously(
            plugin,
            () -> {
              try {
                String rawUuid = resolveUsernameToRawUuid(skinName);
                if (rawUuid == null) {
                  plugin.getLogger().warning("Mojang API: no profile for '" + skinName + "'");
                  return;
                }
                String uuid =
                    rawUuid.replaceFirst(
                        "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
                String profileJson = fetchProfileJson(uuid);
                if (profileJson == null) {
                  return;
                }

                String value = extractJsonString(profileJson, "value");
                String signature = extractJsonString(profileJson, "signature");
                if (value != null && signature != null) {
                  skinCache.put(skinName, new String[] {value, signature});
                  plugin.getLogger().info("Cached skin for '" + skinName + "'");
                  plugin
                      .getServer()
                      .getScheduler()
                      .runTask(
                          plugin,
                          () ->
                              npcs.stream()
                                  .filter(n -> n.skinName().equals(skinName))
                                  .forEach(
                                      npc -> {
                                        Integer fakeId = npcEntityIds.get(npc.id());
                                        UUID npcUuid =
                                            UUID.nameUUIDFromBytes(
                                                ("hunt_npc_" + npc.id())
                                                    .getBytes(StandardCharsets.UTF_8));
                                        for (Player p : Bukkit.getOnlinePlayers()) {
                                          if (isInHuntWorld(p)) {
                                            net.minecraft.server.level.ServerPlayer nmsP =
                                                ((CraftPlayer) p).getHandle();
                                            if (fakeId != null) {
                                              nmsP.connection.send(
                                                  new ClientboundRemoveEntitiesPacket(fakeId));
                                            }
                                            nmsP.connection.send(
                                                new ClientboundPlayerInfoRemovePacket(
                                                    List.of(npcUuid)));
                                            sendAppearance(p, npc);
                                          }
                                        }
                                      }));
                }
              } catch (Exception e) {
                plugin
                    .getLogger()
                    .warning("Could not fetch skin for '" + skinName + "': " + e.getMessage());
              }
            });
  }

  private String resolveUsernameToRawUuid(String username) throws IOException {
    HttpURLConnection conn =
        (HttpURLConnection)
            URI.create("https://api.mojang.com/users/profiles/minecraft/" + username)
                .toURL()
                .openConnection();
    conn.setConnectTimeout(5_000);
    conn.setReadTimeout(5_000);
    if (conn.getResponseCode() != 200) {
      return null;
    }
    return extractJsonString(
        new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8), "id");
  }

  private String fetchProfileJson(String uuid) throws IOException {
    HttpURLConnection conn =
        (HttpURLConnection)
            URI.create(
                    "https://sessionserver.mojang.com/session/minecraft/profile/"
                        + uuid
                        + "?unsigned=false")
                .toURL()
                .openConnection();
    conn.setConnectTimeout(5_000);
    conn.setReadTimeout(5_000);
    if (conn.getResponseCode() != 200) {
      return null;
    }
    return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
  }

  private String extractJsonString(String json, String key) {
    String search = "\"" + key + "\":\"";
    int start = json.indexOf(search);
    if (start < 0) {
      return null;
    }
    start += search.length();
    int end = json.indexOf("\"", start);
    return end < 0 ? null : json.substring(start, end);
  }

  // ─── Profile builder ─────────────────────────────────────────────────────

  private GameProfile buildProfile(UUID uuid, String name, String[] skin) {
    MutablePropertyMap props = new MutablePropertyMap();
    if (skin != null && skin.length >= 2) {
      props.put("textures", new Property("textures", skin[0], skin[1]));
    }
    return new GameProfile(uuid, name, props);
  }

  // ─── Helpers ─────────────────────────────────────────────────────────────

  private boolean isInHuntWorld(Player player) {
    return player.getWorld().getName().equals(huntConfig.getString("hunt.world", "hunt"));
  }
}
