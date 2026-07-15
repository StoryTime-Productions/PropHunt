package com.storytimeproductions.prophunt.game;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Command handler for cycling between Hunt game modes. Updates both the gamemode manager and the
 * hologram display.
 */
public class HuntGameModeCommand implements CommandExecutor {
  private final JavaPlugin plugin;
  private final HuntGameModeManager gameModeManager;

  /**
   * Creates a new HuntGameModeCommand instance.
   *
   * @param plugin The plugin instance
   * @param gameModeManager The game mode manager
   */
  public HuntGameModeCommand(JavaPlugin plugin, HuntGameModeManager gameModeManager) {
    this.plugin = plugin;
    this.gameModeManager = gameModeManager;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!(sender instanceof Player)) {
      sender.sendMessage(
          Component.text("This command can only be used by players!", NamedTextColor.RED));
      return true;
    }

    Player player = (Player) sender;

    // Check if player is in hunt world
    if (!isPlayerInHuntWorld(player)) {
      player.sendMessage(
          Component.text("You must be in the hunt world to use this command!", NamedTextColor.RED));
      return true;
    }

    if (args.length == 0) {
      // No arguments - cycle to next gamemode
      HuntGameMode nextMode = gameModeManager.cycleGameMode();
      player.sendMessage(
          Component.text(
              "Game mode changed to: " + nextMode.getDisplayName(), NamedTextColor.GREEN));

      // Log the change
      plugin
          .getLogger()
          .info(player.getName() + " changed hunt gamemode to " + nextMode.getDisplayName());

    } else if (args.length == 1) {
      // Specific gamemode requested
      String modeName = args[0].toUpperCase();

      try {
        HuntGameMode requestedMode = HuntGameMode.valueOf(modeName);
        gameModeManager.setGameMode(requestedMode);
        player.sendMessage(
            Component.text(
                "Game mode set to: " + requestedMode.getDisplayName(), NamedTextColor.GREEN));

        // Log the change
        plugin
            .getLogger()
            .info(player.getName() + " set hunt gamemode to " + requestedMode.getDisplayName());

      } catch (IllegalArgumentException e) {
        // Invalid gamemode
        player.sendMessage(
            Component.text("Invalid game mode! Available modes:", NamedTextColor.RED));
        for (HuntGameMode mode : HuntGameMode.values()) {
          player.sendMessage(
              Component.text(
                  "- " + mode.name() + " (" + mode.getDisplayName() + ")", NamedTextColor.GRAY));
        }
      }
    } else {
      // Too many arguments
      player.sendMessage(Component.text("Usage: /huntgamemode [mode]", NamedTextColor.RED));
      player.sendMessage(Component.text("Available modes:", NamedTextColor.GRAY));
      for (HuntGameMode mode : HuntGameMode.values()) {
        player.sendMessage(
            Component.text(
                "- " + mode.name() + " (" + mode.getDisplayName() + ")", NamedTextColor.GRAY));
      }
    }

    return true;
  }

  /**
   * Checks if a player is in the hunt world.
   *
   * @param player The player to check
   * @return true if the player is in the hunt world
   */
  private boolean isPlayerInHuntWorld(Player player) {
    String playerWorldName = player.getWorld().getName();
    return playerWorldName.toLowerCase().contains("hunt");
  }
}
