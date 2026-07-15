package com.storytimeproductions.prophunt.game;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Represents the different classes available for hunters. */
public enum HunterClass {
  BRUTE(
      "Brute",
      "Heavy assault with powerful weapons",
      new ItemStack(Material.IRON_AXE),
      new ItemStack(Material.CROSSBOW),
      new ItemStack(Material.TOTEM_OF_UNDYING),
      new ItemStack(Material.IRON_CHESTPLATE),
      0.8f,
      1.5f,
      "Resilience",
      "Resistance II + Regen II for 10s",
      30),

  NIMBLE(
      "Nimble",
      "Swift strikes and quick movement",
      new ItemStack(Material.IRON_SWORD),
      new ItemStack(Material.BOW),
      new ItemStack(Material.FEATHER),
      new ItemStack(Material.LEATHER_CHESTPLATE),
      1.3f,
      0.7f,
      "Dash",
      "Speed burst for 3s",
      15),

  SABOTEUR(
      "Saboteur",
      "Disrupts and reveals hidden enemies",
      new ItemStack(Material.IRON_HOE),
      new ItemStack(Material.FISHING_ROD),
      new ItemStack(Material.ENDER_EYE),
      new ItemStack(Material.CHAINMAIL_CHESTPLATE),
      1.0f,
      1.0f,
      "Scanner",
      "Reveals hiders within 20 blocks for 5s",
      25);

  private final String displayName;
  private final String description;
  private final ItemStack meleeWeapon;
  private final ItemStack rangedWeapon;
  private final ItemStack utilityItem;
  private final ItemStack armor;
  private final float speedModifier;
  private final float damageModifier;
  private final String abilityName;
  private final String abilityDescription;
  private final int abilityCooldownSeconds;

  HunterClass(
      String displayName,
      String description,
      ItemStack meleeWeapon,
      ItemStack rangedWeapon,
      ItemStack utilityItem,
      ItemStack armor,
      float speedModifier,
      float damageModifier,
      String abilityName,
      String abilityDescription,
      int abilityCooldownSeconds) {
    this.displayName = displayName;
    this.description = description;
    this.meleeWeapon = meleeWeapon;
    this.rangedWeapon = rangedWeapon;
    this.utilityItem = utilityItem;
    this.armor = armor;
    this.speedModifier = speedModifier;
    this.damageModifier = damageModifier;
    this.abilityName = abilityName;
    this.abilityDescription = abilityDescription;
    this.abilityCooldownSeconds = abilityCooldownSeconds;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getDescription() {
    return description;
  }

  public ItemStack getMeleeWeapon() {
    return meleeWeapon.clone();
  }

  public ItemStack getRangedWeapon() {
    return rangedWeapon.clone();
  }

  public ItemStack getUtilityItem() {
    return utilityItem.clone();
  }

  public ItemStack getArmor() {
    return armor.clone();
  }

  public float getSpeedModifier() {
    return speedModifier;
  }

  public float getDamageModifier() {
    return damageModifier;
  }

  public String getAbilityName() {
    return abilityName;
  }

  public String getAbilityDescription() {
    return abilityDescription;
  }

  public int getAbilityCooldownSeconds() {
    return abilityCooldownSeconds;
  }

  /**
   * Gets the weapon for this hunter class.
   *
   * @deprecated Use getMeleeWeapon() instead
   */
  @Deprecated
  public ItemStack getWeapon() {
    return getMeleeWeapon();
  }
}
