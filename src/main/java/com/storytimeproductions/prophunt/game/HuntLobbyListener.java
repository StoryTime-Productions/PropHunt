package com.storytimeproductions.prophunt.game;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Handles inventory click events for the Hunt lobby system. */
public class HuntLobbyListener implements Listener {

  private final HuntLobbyManager lobbyManager;
  private final HuntGameModeManager gameModeManager;

  /**
   * Constructs a new HuntLobbyListener for the given lobby manager.
   *
   * @param lobbyManager The HuntLobbyManager instance
   * @param gameModeManager The HuntGameModeManager instance
   */
  public HuntLobbyListener(HuntLobbyManager lobbyManager, HuntGameModeManager gameModeManager) {
    this.lobbyManager = lobbyManager;
    this.gameModeManager = gameModeManager;
  }

  /**
   * Handles inventory click events for the Hunt lobby menus.
   *
   * @param event The InventoryClickEvent
   */
  @EventHandler
  public void onInventoryClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }

    if (!lobbyManager.hasOpenMenu(player.getUniqueId())) {
      return;
    }

    event.setCancelled(true);

    ItemStack clickedItem = event.getCurrentItem();
    if (clickedItem == null || !clickedItem.hasItemMeta()) {
      return;
    }

    ItemMeta meta = clickedItem.getItemMeta();
    Component displayName = meta.displayName();
    if (displayName == null) {
      return;
    }

    String itemName = ((net.kyori.adventure.text.TextComponent) displayName).content();
    String inventoryTitle =
        ((net.kyori.adventure.text.TextComponent) event.getView().title()).content();

    handleMenuClick(player, inventoryTitle, itemName, event.getSlot());
  }

  private void handleMenuClick(Player player, String inventoryTitle, String itemName, int slot) {
    HuntPlayerData data = lobbyManager.getPlayerData(player.getUniqueId());
    if (data == null) {
      return;
    }

    switch (inventoryTitle) {
      case "Hunt Game Lobby" -> handleMainMenuClick(player, data, itemName);
      case "Select Team" -> handleTeamSelectionClick(player, data, itemName);
      case "Select Hunter Class" -> handleHunterClassClick(player, data, slot);
      case "Select Hider Class" -> handleHiderClassClick(player, data, slot);
      case "Select Map" -> handleMapSelectionClick(player, data, slot);
      case "Select Game Mode" -> handleGameModeClick(player, data, slot);
      default -> {
        // No action
      }
    }
  }

  private void handleMainMenuClick(Player player, HuntPlayerData data, String itemName) {
    switch (itemName) {
      case "Select Team" -> {
        // Block team selection in Imposter Hunt mode
        if (gameModeManager.getCurrentGameMode() == HuntGameMode.IMPOSTER_HUNT) {
          player.sendMessage(
              Component.text(
                  "Team selection is not available in Imposter Hunt mode!", NamedTextColor.RED));
          return;
        }
        lobbyManager.openTeamSelectionMenu(player);
      }
      case "Select Class" -> {
        // Block class selection in Imposter Hunt mode
        if (gameModeManager.getCurrentGameMode() == HuntGameMode.IMPOSTER_HUNT) {
          player.sendMessage(
              Component.text(
                  "Class selection is not available in Imposter Hunt mode!", NamedTextColor.RED));
          return;
        }
        lobbyManager.openClassSelectionMenu(player);
      }
      case "Select Map" -> lobbyManager.openMapSelectionMenu(player);
      case "Select Game Mode" -> lobbyManager.openGameModeSelectionMenu(player);
      case "Ready!", "Not Ready" -> {
        if (data.hasValidSelections()) {
          data.setReady(!data.isReady());
          player.sendMessage(
              Component.text(
                  data.isReady() ? "You are now ready!" : "You are no longer ready.",
                  data.isReady() ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
          lobbyManager.openMainMenu(player); // Refresh menu
        } else {
          player.sendMessage(
              Component.text(
                  "Please complete all selections before readying up!", NamedTextColor.RED));
        }
      }
      case "Back" -> {
        player.closeInventory();
        lobbyManager.removePlayer(player.getUniqueId());
        player.sendMessage(Component.text("Exited Hunt Lobby", NamedTextColor.YELLOW));
      }
      default -> {
        // No action
      }
    }
  }

  private void handleTeamSelectionClick(Player player, HuntPlayerData data, String itemName) {
    // Block team selection in Imposter Hunt mode
    if (gameModeManager.getCurrentGameMode() == HuntGameMode.IMPOSTER_HUNT) {
      player.sendMessage(
          Component.text(
              "Team selection is not available in Imposter Hunt mode!", NamedTextColor.RED));
      lobbyManager.openMainMenu(player);
      return;
    }

    switch (itemName) {
      case "Hunters" -> {
        data.setSelectedTeam(HuntTeam.HUNTERS);
        data.setSelectedHiderClass(null); // Clear opposite team class
        player.sendMessage(Component.text("Selected team: Hunters", NamedTextColor.GREEN));
        lobbyManager.openMainMenu(player);
      }
      case "Hiders" -> {
        data.setSelectedTeam(HuntTeam.HIDERS);
        data.setSelectedHunterClass(null); // Clear opposite team class
        player.sendMessage(Component.text("Selected team: Hiders", NamedTextColor.GREEN));
        lobbyManager.openMainMenu(player);
      }
      case "Back" -> lobbyManager.openMainMenu(player);
      default -> {
        // No action
      }
    }
  }

  private void handleHunterClassClick(Player player, HuntPlayerData data, int slot) {
    // Block class selection in Imposter Hunt mode
    if (gameModeManager.getCurrentGameMode() == HuntGameMode.IMPOSTER_HUNT) {
      player.sendMessage(
          Component.text(
              "Class selection is not available in Imposter Hunt mode!", NamedTextColor.RED));
      lobbyManager.openMainMenu(player);
      return;
    }

    switch (slot) {
      case 2 -> {
        data.setSelectedHunterClass(HunterClass.BRUTE);
        sendHunterClassInfo(player, HunterClass.BRUTE);
        lobbyManager.openMainMenu(player);
      }
      case 5 -> {
        data.setSelectedHunterClass(HunterClass.NIMBLE);
        sendHunterClassInfo(player, HunterClass.NIMBLE);
        lobbyManager.openMainMenu(player);
      }
      case 8 -> {
        data.setSelectedHunterClass(HunterClass.SABOTEUR);
        sendHunterClassInfo(player, HunterClass.SABOTEUR);
        lobbyManager.openMainMenu(player);
      }
      case 13 -> lobbyManager.openMainMenu(player); // Back button
      default -> {
        // No action
      }
    }
  }

  private void handleHiderClassClick(Player player, HuntPlayerData data, int slot) {
    // Block class selection in Imposter Hunt mode
    if (gameModeManager.getCurrentGameMode() == HuntGameMode.IMPOSTER_HUNT) {
      player.sendMessage(
          Component.text(
              "Class selection is not available in Imposter Hunt mode!", NamedTextColor.RED));
      lobbyManager.openMainMenu(player);
      return;
    }

    switch (slot) {
      case 2 -> {
        data.setSelectedHiderClass(HiderClass.TRICKSTER);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
        sendHiderClassInfo(player, HiderClass.TRICKSTER);
        lobbyManager.openMainMenu(player);
      }
      case 5 -> {
        data.setSelectedHiderClass(HiderClass.PHASER);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
        sendHiderClassInfo(player, HiderClass.PHASER);
        lobbyManager.openMainMenu(player);
      }
      case 8 -> {
        data.setSelectedHiderClass(HiderClass.CLOAKER);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
        sendHiderClassInfo(player, HiderClass.CLOAKER);
        lobbyManager.openMainMenu(player);
      }
      case 13 -> lobbyManager.openMainMenu(player); // Back button
      default -> {
        // No action
      }
    }
  }

  private void sendHunterClassInfo(Player player, HunterClass cls) {
    Component divider = Component.text("────────────────────────────", NamedTextColor.DARK_GRAY);
    player.sendMessage(divider);
    player.sendMessage(
        Component.text("  ⚔ ", NamedTextColor.RED)
            .append(
                Component.text(cls.getDisplayName().toUpperCase(), NamedTextColor.RED)
                    .decorate(TextDecoration.BOLD))
            .append(Component.text("  (Hunter)", NamedTextColor.GRAY)));
    player.sendMessage(Component.text("  " + cls.getDescription(), NamedTextColor.YELLOW));
    player.sendMessage(Component.empty());
    player.sendMessage(
        Component.text("  Speed: ", NamedTextColor.GRAY)
            .append(Component.text(cls.getSpeedModifier() + "×", NamedTextColor.WHITE))
            .append(Component.text("   Damage: ", NamedTextColor.GRAY))
            .append(Component.text(cls.getDamageModifier() + "×", NamedTextColor.WHITE)));
    player.sendMessage(
        Component.text("  Melee:  ", NamedTextColor.GRAY)
            .append(
                Component.text(
                    formatItemName(cls.getMeleeWeapon().getType().name()), NamedTextColor.WHITE)));
    player.sendMessage(
        Component.text("  Ranged: ", NamedTextColor.GRAY)
            .append(
                Component.text(
                    formatItemName(cls.getRangedWeapon().getType().name()), NamedTextColor.WHITE)));
    player.sendMessage(
        Component.text("  Utility: ", NamedTextColor.GRAY)
            .append(Component.text("[" + cls.getAbilityName() + "]", NamedTextColor.GOLD))
            .append(Component.text(" — " + cls.getAbilityDescription(), NamedTextColor.WHITE))
            .append(
                Component.text(
                    " (" + cls.getAbilityCooldownSeconds() + "s CD)", NamedTextColor.DARK_AQUA)));
    player.sendMessage(divider);
    player.sendActionBar(
        Component.text("Selected: ", NamedTextColor.GRAY)
            .append(Component.text(cls.getDisplayName(), NamedTextColor.RED)));
  }

  private void sendHiderClassInfo(Player player, HiderClass cls) {
    Component divider = Component.text("────────────────────────────", NamedTextColor.DARK_GRAY);
    player.sendMessage(divider);
    player.sendMessage(
        Component.text("  ◈ ", NamedTextColor.AQUA)
            .append(
                Component.text(cls.getDisplayName().toUpperCase(), NamedTextColor.AQUA)
                    .decorate(TextDecoration.BOLD))
            .append(Component.text("  (Hider)", NamedTextColor.GRAY)));
    player.sendMessage(Component.text("  " + cls.getDescription(), NamedTextColor.YELLOW));
    player.sendMessage(Component.empty());
    player.sendMessage(
        Component.text("  Utility: ", NamedTextColor.GRAY)
            .append(Component.text("[" + cls.getAbilityName() + "]", NamedTextColor.AQUA))
            .append(
                Component.text(
                    " (" + cls.getAbilityCooldownSeconds() + "s CD)", NamedTextColor.DARK_AQUA)));
    player.sendMessage(
        Component.text("  Shared:  ", NamedTextColor.GRAY)
            .append(Component.text("[Block Disguise]", NamedTextColor.AQUA))
            .append(Component.text(" (30s CD)", NamedTextColor.DARK_AQUA)));
    player.sendMessage(divider);
    player.sendActionBar(
        Component.text("Selected: ", NamedTextColor.GRAY)
            .append(Component.text(cls.getDisplayName(), NamedTextColor.AQUA)));
  }

  private String formatItemName(String materialName) {
    String[] words = materialName.replace('_', ' ').toLowerCase().split(" ");
    StringBuilder sb = new StringBuilder();
    for (String word : words) {
      if (!sb.isEmpty()) {
        sb.append(' ');
      }
      sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
    }
    return sb.toString();
  }

  private void handleMapSelectionClick(Player player, HuntPlayerData data, int slot) {
    HuntMap[] maps = HuntMap.values();
    int mapIndex = (slot - 1) / 2; // Convert slot to map index

    if (mapIndex >= 0 && mapIndex < maps.length) {
      data.setPreferredMap(maps[mapIndex]);
      player.sendMessage(
          Component.text("Selected map: " + maps[mapIndex].getDisplayName(), NamedTextColor.GREEN));
      lobbyManager.openMainMenu(player);
    } else if (slot == 13) {
      lobbyManager.openMainMenu(player); // Back button
    }
  }

  private void handleGameModeClick(Player player, HuntPlayerData data, int slot) {
    switch (slot) {
      case 2 -> {
        lobbyManager.handleGameModeSelection(player, HuntGameMode.PROP_HUNT);
        player.sendMessage(Component.text("Selected mode: Prop Hunt", NamedTextColor.GREEN));
        lobbyManager.openMainMenu(player);
      }
      case 5 -> {
        lobbyManager.handleGameModeSelection(player, HuntGameMode.IMPOSTER_HUNT);
        player.sendMessage(Component.text("Selected mode: Imposter Hunt", NamedTextColor.GREEN));
        lobbyManager.openMainMenu(player);
      }
      case 8 -> {
        lobbyManager.handleGameModeSelection(player, HuntGameMode.NEXTBOT_HUNT);
        // Message is handled in handleGameModeSelection for NextBots
        lobbyManager.openMainMenu(player);
      }
      case 13 -> lobbyManager.openMainMenu(player); // Back button
      default -> {
        // No action
      }
    }
  }
}
