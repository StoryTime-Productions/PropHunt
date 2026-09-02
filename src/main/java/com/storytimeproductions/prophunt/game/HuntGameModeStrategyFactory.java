package com.storytimeproductions.prophunt.game;

/** Factory class for creating gamemode-specific strategies. */
public class HuntGameModeStrategyFactory {

  /**
   * Gets the appropriate strategy for the given gamemode.
   *
   * @param gameMode The gamemode to get a strategy for
   * @return The strategy instance
   * @throws IllegalArgumentException if the gamemode is not supported
   */
  public static HuntGameModeStrategy getStrategy(HuntGameMode gameMode) {
    switch (gameMode) {
      case PROP_HUNT:
        return new PropHuntStrategy();
      case IMPOSTER_HUNT:
        return new ImposterHuntStrategy();
      default:
        throw new IllegalArgumentException("Unsupported gamemode: " + gameMode);
    }
  }
}
