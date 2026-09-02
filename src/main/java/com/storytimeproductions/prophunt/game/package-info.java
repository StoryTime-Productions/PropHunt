/**
 * Core game logic for PropHunt's two modes - Prop Hunt and Imposter Hunt.
 *
 * <p>Broadly organized into a few groups of classes:
 *
 * <ul>
 *   <li><b>Lobby and prep phase</b>: {@link
 *       com.storytimeproductions.prophunt.game.HuntLobbyManager}, {@link
 *       com.storytimeproductions.prophunt.game.HuntLobbyListener}, and {@link
 *       com.storytimeproductions.prophunt.game.HuntPrepPhaseManager} handle team/class/map
 *       selection, ready-up, countdown, and the hunter lock-in before a round starts.
 *   <li><b>Gamemode strategy</b>: {@link
 *       com.storytimeproductions.prophunt.game.HuntGameModeManager} and the {@link
 *       com.storytimeproductions.prophunt.game.HuntGameModeStrategy} implementations ({@link
 *       com.storytimeproductions.prophunt.game.PropHuntStrategy}, {@link
 *       com.storytimeproductions.prophunt.game.ImposterHuntStrategy}) let the same lobby/prep flow
 *       support multiple rule sets.
 *   <li><b>Abilities</b>: {@link com.storytimeproductions.prophunt.game.HiderUtilityListener} and
 *       {@link com.storytimeproductions.prophunt.game.HuntUtilityListener} implement each hider and
 *       hunter class's utility ability and cooldown, rendered via {@link
 *       com.storytimeproductions.prophunt.game.AbilityStatus}.
 *   <li><b>Disguises</b>: {@link com.storytimeproductions.prophunt.game.HuntDisguiseManager} and
 *       {@link com.storytimeproductions.prophunt.game.HuntDisguiseNpcManager} apply LibsDisguises
 *       block/entity disguises; {@link
 *       com.storytimeproductions.prophunt.game.HuntDisguisePassiveListener} drives
 *       disguise-specific passive hunter abilities.
 *   <li><b>Ambient tension and detection</b>: {@link
 *       com.storytimeproductions.prophunt.game.HuntAmbientTrackingListener} and {@link
 *       com.storytimeproductions.prophunt.game.HuntSpotlightListener} add passive
 *       proximity/idle-based pressure on top of the core hide-and-seek loop.
 *   <li><b>Win conditions and cleanup</b>: {@link
 *       com.storytimeproductions.prophunt.game.HuntDeathHandler} and {@link
 *       com.storytimeproductions.prophunt.game.HuntCleanupListener} track eliminations, detect win
 *       conditions, and clear per-player state when someone leaves.
 *   <li><b>Imposter Hunt-specific</b>: {@link
 *       com.storytimeproductions.prophunt.game.ImposterGameManager}, {@link
 *       com.storytimeproductions.prophunt.game.ImposterCoinManager}, and {@link
 *       com.storytimeproductions.prophunt.game.ImposterListener} implement that mode's
 *       murderer/sheriff/innocent roles and coin economy.
 * </ul>
 */
package com.storytimeproductions.prophunt.game;
