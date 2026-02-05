package mage.client.streaming;

import mage.client.chat.ChatPanelBasic;

/**
 * Combined chat panel that displays both game log and chat messages
 * in a single interleaved view for streaming/observer mode.
 */
public class CombinedChatPanel extends ChatPanelBasic {

    public CombinedChatPanel() {
        super();
        useExtendedView(VIEW_MODE.GAME);
        setChatType(ChatType.GAME);
        disableInput();  // Observers cannot chat
    }
}
