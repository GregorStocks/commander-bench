
package mage.client.game;

/**
 * Defines some options for the PlayAreaPanel
 *
 * @author LevelX2
 */
public class PlayAreaPanelOptions {

    public PlayAreaPanelOptions(boolean isPlayer, boolean isHuman, boolean playerItself, boolean rollbackTurnsAllowed, boolean topRow) {
        this(isPlayer, isHuman, playerItself, rollbackTurnsAllowed, topRow, false);
    }

    public PlayAreaPanelOptions(boolean isPlayer, boolean isHuman, boolean playerItself, boolean rollbackTurnsAllowed, boolean topRow, boolean showHandInPlayArea) {
        this(isPlayer, isHuman, playerItself, rollbackTurnsAllowed, topRow, showHandInPlayArea, false);
    }

    public PlayAreaPanelOptions(boolean isPlayer, boolean isHuman, boolean playerItself, boolean rollbackTurnsAllowed, boolean topRow, boolean showHandInPlayArea, boolean showGraveyardInPlayArea) {
        this(isPlayer, isHuman, playerItself, rollbackTurnsAllowed, topRow, showHandInPlayArea, showGraveyardInPlayArea, false);
    }

    public PlayAreaPanelOptions(boolean isPlayer, boolean isHuman, boolean playerItself, boolean rollbackTurnsAllowed, boolean topRow, boolean showHandInPlayArea, boolean showGraveyardInPlayArea, boolean showExileInPlayArea) {
        this.isPlayer = isPlayer;
        this.isHuman = isHuman;
        this.playerItself = playerItself;
        this.rollbackTurnsAllowed = rollbackTurnsAllowed;
        this.topRow = topRow;
        this.showHandInPlayArea = showHandInPlayArea;
        this.showGraveyardInPlayArea = showGraveyardInPlayArea;
        this.showExileInPlayArea = showExileInPlayArea;
    }

    /**
     * true if the client is a player / false if the client is a watcher
     */
    public boolean isPlayer;

    /**
     * true if the player is the human, not computer
     */
    public boolean isHuman;

    /**
     * true if the player is the client player itself, false if the player is
     * another player playing with the client player
     */
    public boolean playerItself;

    /**
     * true if the player can rollback turns if all players agree
     */
    public boolean rollbackTurnsAllowed;

    /**
     * true if the battlefield is on the top row of player areas
     */
    public boolean topRow;

    /**
     * true to show the player's hand in their play area (for streaming/spectator mode)
     */
    public boolean showHandInPlayArea;

    /**
     * true to show the player's graveyard in their play area (for streaming/spectator mode)
     */
    public boolean showGraveyardInPlayArea;

    /**
     * true to show the player's exile in their play area (for streaming/spectator mode)
     */
    public boolean showExileInPlayArea;

}
