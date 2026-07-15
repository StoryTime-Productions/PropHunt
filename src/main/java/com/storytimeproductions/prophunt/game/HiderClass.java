package com.storytimeproductions.prophunt.game;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Represents the different classes available for hiders. */
public enum HiderClass {
  TRICKSTER(
      "Trickster",
      "Sets counter-traps against hunters",
      new ItemStack(Material.TRIPWIRE_HOOK),
      new ItemStack(Material.LEATHER_CHESTPLATE),
      "Counter-Trap",
      45),

  PHASER(
      "Phaser",
      "Can phase through walls temporarily",
      new ItemStack(Material.ENDER_PEARL),
      new ItemStack(Material.CHAINMAIL_CHESTPLATE),
      "Phase",
      60),

  CLOAKER(
      "Cloaker",
      "Can become invisible for limited time",
      new ItemStack(Material.POTION),
      new ItemStack(Material.IRON_CHESTPLATE),
      "Cloak",
      90);

  private final String displayName;
  private final String description;
  private final ItemStack tool;
  private final ItemStack armor;
  private final String abilityName;
  private final int abilityCooldownSeconds;

  HiderClass(
      String displayName,
      String description,
      ItemStack tool,
      ItemStack armor,
      String abilityName,
      int abilityCooldownSeconds) {
    this.displayName = displayName;
    this.description = description;
    this.tool = tool;
    this.armor = armor;
    this.abilityName = abilityName;
    this.abilityCooldownSeconds = abilityCooldownSeconds;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getDescription() {
    return description;
  }

  public ItemStack getTool() {
    return tool.clone();
  }

  public ItemStack getArmor() {
    return armor.clone();
  }

  public String getAbilityName() {
    return abilityName;
  }

  public int getAbilityCooldownSeconds() {
    return abilityCooldownSeconds;
  }
}
