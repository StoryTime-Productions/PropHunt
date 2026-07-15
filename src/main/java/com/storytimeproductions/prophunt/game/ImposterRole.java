package com.storytimeproductions.prophunt.game;

/** Represents the different roles in the Imposter Hunt gamemode. */
public enum ImposterRole {
  INNOCENT("Innocent", "Survive and help identify the murderer"),
  SHERIFF("Sheriff", "Protect innocents and eliminate the murderer"),
  MURDERER("Murderer", "Eliminate all other players");

  private final String displayName;
  private final String description;

  ImposterRole(String displayName, String description) {
    this.displayName = displayName;
    this.description = description;
  }

  /**
   * Gets the display name of this role.
   *
   * @return The display name
   */
  public String getDisplayName() {
    return displayName;
  }

  /**
   * Gets the description of this role.
   *
   * @return The description
   */
  public String getDescription() {
    return description;
  }
}
