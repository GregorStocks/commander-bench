package mage.client.streaming;

import mage.client.chat.ChatPanelBasic;
import mage.view.ChatMessage.MessageColor;
import mage.view.ChatMessage.MessageType;

import java.util.Date;
import java.util.regex.Pattern;

/**
 * Game log panel for streaming mode that filters spammy messages
 * and routes player chat (TALK) messages to a separate panel.
 */
public class CombinedChatPanel extends ChatPanelBasic {

    private ChatPanelBasic playerChatPanel;

    public CombinedChatPanel() {
        super();
        useExtendedView(VIEW_MODE.GAME);
        setChatType(ChatType.GAME);
        disableInput();  // Observers cannot chat
    }

    public void setPlayerChatPanel(ChatPanelBasic panel) {
        this.playerChatPanel = panel;
    }

    @Override
    public void receiveMessage(String username, String message, Date time,
            String turnInfo, MessageType messageType, MessageColor color) {
        // Route player chat messages to the separate chat panel
        if (messageType == MessageType.TALK
                || messageType == MessageType.WHISPER_FROM
                || messageType == MessageType.WHISPER_TO) {
            if (playerChatPanel != null) {
                playerChatPanel.receiveMessage(username, message, time, turnInfo, messageType, color);
            }
            return;
        }

        // Game log messages stay here, with spam filtering
        if (shouldFilterMessage(message)) {
            return;
        }
        super.receiveMessage(username, message, time, turnInfo, messageType, color);
    }

    // Patterns for spammy messages to filter out
    private static final Pattern[] FILTER_PATTERNS = {
        // "Player skip attack" - fires every turn when not attacking
        Pattern.compile(" skip attack$"),
        // "Player draws a card" or "Player draws X cards"
        Pattern.compile(" draws (a card|\\d+ cards)$"),
        // "Player puts CardName [id] from hand/stack onto the Battlefield"
        Pattern.compile(" puts .+ from (hand|stack) onto the Battlefield$"),
    };

    private boolean shouldFilterMessage(String message) {
        if (message == null) {
            return false;
        }
        if (message.startsWith("HOTKEYS:")) {
            return true;
        }
        for (Pattern pattern : FILTER_PATTERNS) {
            if (pattern.matcher(message).find()) {
                return true;
            }
        }
        return false;
    }
}
