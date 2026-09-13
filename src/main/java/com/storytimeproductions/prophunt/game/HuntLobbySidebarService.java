package com.storytimeproductions.prophunt.game;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

/** Renders and clears the Hunt lobby sidebar scoreboard for players. */
public final class HuntLobbySidebarService {
  private final String objectiveName;

  /** Constructs a sidebar service registering its objective under the given name. */
  public HuntLobbySidebarService(String objectiveName) {
    this.objectiveName = objectiveName;
  }

  // Longest possible label ("Killer:") - other labels are left-padded to this width so every
  // colon lines up, regardless of which lines are actually shown for a given player.
  private static final int LABEL_WIDTH = "Killer:".length();

  /**
   * Applies the Hunt lobby sidebar: the active gamemode as a bold title (mirrors DeepCore's
   * LobbySidebarService styling), then role, class, map vote, killer choice, and ready status.
   */
  public void applySidebar(
      Player player,
      HuntPlayerData data,
      HuntMap votedMap,
      int readyCount,
      int totalCount,
      HuntGameMode currentGameMode) {
    if (Bukkit.getScoreboardManager() == null) {
      return;
    }

    Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
    Objective objective =
        scoreboard.registerNewObjective(objectiveName, "dummy", buildTitle(currentGameMode));
    objective.setDisplaySlot(DisplaySlot.SIDEBAR);
    applyBlankSidebarNumberFormat(objective);

    int score = 7;
    objective.getScore(ChatColor.DARK_BLUE.toString()).setScore(score--);

    HuntTeam team = data.getSelectedTeam();
    score = addLine(objective, score, "Role:", resolveRoleText(team));
    score = addLine(objective, score, "Class:", resolveClassText(data, team));
    score = addLine(objective, score, "Map:", votedMap != null ? votedMap.getDisplayName() : "-");
    if (team == HuntTeam.HUNTERS) {
      String killerText = data.getSelectedDisguise() != null ? data.getSelectedDisguise() : "-";
      score = addLine(objective, score, "Killer:", killerText);
    }
    objective.getScore(ChatColor.DARK_GRAY.toString()).setScore(score--);

    ChatColor readyColor = data.isReady() ? ChatColor.GREEN : ChatColor.RED;
    String readyValue =
        readyColor
            + (data.isReady() ? "YES" : "NO")
            + ChatColor.WHITE
            + " ("
            + readyCount
            + "/"
            + totalCount
            + ")";
    objective.getScore(ChatColor.YELLOW + padLabel("Ready:") + " " + readyValue).setScore(score--);

    player.setScoreboard(scoreboard);
  }

  private int addLine(Objective objective, int score, String label, String value) {
    objective
        .getScore(ChatColor.YELLOW + padLabel(label) + " " + ChatColor.WHITE + value)
        .setScore(score);
    return score - 1;
  }

  private String padLabel(String label) {
    int padding = LABEL_WIDTH - label.length();
    return padding > 0 ? " ".repeat(padding) + label : label;
  }

  private String buildTitle(HuntGameMode currentGameMode) {
    String modeName = currentGameMode != null ? currentGameMode.getDisplayName() : "Hunt";
    return ChatColor.GOLD
        + " "
        + ChatColor.BOLD
        + modeName
        + ChatColor.RESET
        + ChatColor.GOLD
        + " ";
  }

  /** Clears the managed sidebar objective from the player's scoreboard. */
  public void clearSidebar(Player player) {
    if (Bukkit.getScoreboardManager() == null) {
      return;
    }

    Scoreboard scoreboard = player.getScoreboard();
    if (scoreboard == null) {
      return;
    }

    Objective objective = scoreboard.getObjective(objectiveName);
    if (objective != null) {
      player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }
  }

  private String resolveRoleText(HuntTeam team) {
    if (team == HuntTeam.HUNTERS) {
      return "Hunter";
    }
    if (team == HuntTeam.HIDERS) {
      return "Hider";
    }
    return "-";
  }

  private String resolveClassText(HuntPlayerData data, HuntTeam team) {
    if (team == HuntTeam.HUNTERS) {
      HunterClass hunterClass = data.getSelectedHunterClass();
      return hunterClass != null ? hunterClass.getDisplayName() : "-";
    }
    if (team == HuntTeam.HIDERS) {
      HiderClass hiderClass = data.getSelectedHiderClass();
      return hiderClass != null ? hiderClass.getDisplayName() : "-";
    }
    return "-";
  }

  private void applyBlankSidebarNumberFormat(Objective objective) {
    if (objective == null) {
      return;
    }

    try {
      Class<?> numberFormatClass = Class.forName("org.bukkit.scoreboard.NumberFormat");
      Object blankFormat = numberFormatClass.getMethod("blank").invoke(null);
      objective
          .getClass()
          .getMethod("setNumberFormat", numberFormatClass)
          .invoke(objective, blankFormat);
    } catch (ReflectiveOperationException ignored) {
      // Older APIs may not support objective number formatting.
    }
  }
}
