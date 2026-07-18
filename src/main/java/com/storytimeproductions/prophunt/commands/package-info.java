/**
 * Player-facing command executors for PropHunt: {@code /hunt} (lobby entry, prep-phase controls,
 * team/class/map selection, admin overrides) and {@code /stdisguise} (manual entity disguises).
 * These classes parse arguments and delegate the actual game logic to the managers and listeners in
 * {@link com.storytimeproductions.prophunt.game}.
 */
package com.storytimeproductions.prophunt.commands;
