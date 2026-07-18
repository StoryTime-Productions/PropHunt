package com.storytimeproductions.prophunt.game;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Live cooldown/condition status for a single ability, used to render one segment of the persistent
 * ability status action bar.
 *
 * @param label Short display name, e.g. "PHASE"
 * @param onCooldown Whether the ability is currently on cooldown
 * @param remainingSeconds Seconds left on cooldown (ignored if not on cooldown)
 * @param totalSeconds The ability's full cooldown duration, for the progress bar fraction
 * @param conditionMet Whether the ability's activation condition (if any) is currently met; always
 *     true for abilities with no such condition
 */
public record AbilityStatus(
    String label,
    boolean onCooldown,
    long remainingSeconds,
    int totalSeconds,
    boolean conditionMet) {

  private static final int PROGRESS_BAR_CELLS = 10;

  /** Renders this status as one action-bar segment: a colored square, label, and state. */
  public Component render() {
    if (onCooldown) {
      return Component.text("■ ", NamedTextColor.RED)
          .append(
              Component.text(
                  label + " " + progressBar() + " " + remainingSeconds + "s",
                  NamedTextColor.WHITE));
    }
    NamedTextColor squareColor = conditionMet ? NamedTextColor.GREEN : NamedTextColor.YELLOW;
    return Component.text("■ ", squareColor)
        .append(Component.text(label + " Ready", NamedTextColor.WHITE));
  }

  private String progressBar() {
    int filled =
        totalSeconds <= 0
            ? PROGRESS_BAR_CELLS
            : (int) Math.round(PROGRESS_BAR_CELLS * ((double) remainingSeconds / totalSeconds));
    filled = Math.max(0, Math.min(PROGRESS_BAR_CELLS, filled));
    StringBuilder bar = new StringBuilder("[");
    for (int i = 0; i < PROGRESS_BAR_CELLS; i++) {
      bar.append(i < filled ? '■' : '□');
    }
    return bar.append(']').toString();
  }
}
