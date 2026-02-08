package mage.client.streaming;

import java.awt.*;
import java.util.*;
import java.util.UUID;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;

import mage.abilities.icon.CardIconRenderSettings;
import mage.cards.MageCard;
import mage.client.cards.BigCard;
import mage.client.dialog.PreferencesDialog;
import mage.client.plugins.impl.Plugins;
import mage.client.util.GUISizeHelper;
import mage.constants.Zone;
import mage.view.CardView;
import mage.view.CardsView;
import org.apache.log4j.Logger;

/**
 * Panel for displaying commander cards in a horizontal layout.
 * Used in streaming/observer mode to show each player's commander(s).
 * Supports partner commanders displayed side-by-side.
 */
public class CommanderPanel extends JPanel {

    private static final Logger logger = Logger.getLogger(CommanderPanel.class);

    // Card dimensions for commander display (same as exile/graveyard for consistency)
    private static final int CARD_WIDTH = 60;
    private static final int CARD_HEIGHT = (int) (CARD_WIDTH * GUISizeHelper.CARD_WIDTH_TO_HEIGHT_COEF);

    // Gap between partner commanders
    private static final int CARD_GAP = 5;

    private static final Border EMPTY_BORDER = new EmptyBorder(2, 2, 2, 2);

    private final Map<UUID, MageCard> cards = new LinkedHashMap<>();
    private JPanel cardArea;
    private BigCard bigCard;
    private UUID gameId;

    public CommanderPanel() {
        initComponents();
    }

    private void initComponents() {
        cardArea = new JPanel();
        cardArea.setLayout(null); // Absolute positioning like ExilePanel
        cardArea.setBackground(new Color(0, 0, 0, 0));
        cardArea.setOpaque(false);

        setOpaque(true);
        setBackground(new Color(100, 80, 40)); // Gold/amber for commander zone
        setBorder(EMPTY_BORDER);
        setLayout(new BorderLayout());
        add(cardArea, BorderLayout.CENTER);

        // Initial size for 1 commander
        setPreferredSize(new Dimension(CARD_WIDTH + 10, CARD_HEIGHT + 4));
        setMinimumSize(new Dimension(CARD_WIDTH + 10, 50));

        // Start hidden - will show when commanders are loaded
        setVisible(false);
    }

    public void cleanUp() {
        cards.clear();
        cardArea.removeAll();
    }

    public void changeGUISize() {
        layoutCards();
    }

    public void loadCards(CardsView cardsView, BigCard bigCard, UUID gameId) {
        this.bigCard = bigCard;
        this.gameId = gameId;

        logger.info("CommanderPanel.loadCards called with " + (cardsView != null ? cardsView.size() : "null") + " cards");

        // Remove cards no longer in command zone
        Set<UUID> toRemove = new HashSet<>();
        for (UUID id : cards.keySet()) {
            if (!cardsView.containsKey(id)) {
                toRemove.add(id);
            }
        }
        for (UUID id : toRemove) {
            MageCard card = cards.remove(id);
            if (card != null) {
                cardArea.remove(card);
            }
        }

        // Add/update cards
        for (CardView cardView : cardsView.values()) {
            if (!cards.containsKey(cardView.getId())) {
                addCard(cardView);
            } else {
                cards.get(cardView.getId()).update(cardView);
            }
        }

        layoutCards();
        cardArea.revalidate();
        cardArea.repaint();
        revalidate();
        repaint();

        // Hide panel when no commanders to display
        logger.info("CommanderPanel after load: " + cards.size() + " cards, setting visible=" + !cards.isEmpty());
        setVisible(!cards.isEmpty());
    }

    private void addCard(CardView cardView) {
        Dimension cardDimension = new Dimension(CARD_WIDTH, CARD_HEIGHT);
        MageCard mageCard = Plugins.instance.getMageCard(
                cardView,
                bigCard,
                new CardIconRenderSettings(),
                cardDimension,
                gameId,
                true,
                true,
                PreferencesDialog.getRenderMode(),
                true
        );
        mageCard.setCardContainerRef(cardArea);
        mageCard.setZone(Zone.COMMAND);
        mageCard.setCardBounds(0, 0, CARD_WIDTH, CARD_HEIGHT);
        mageCard.update(cardView);

        cards.put(cardView.getId(), mageCard);
        cardArea.add(mageCard);
    }

    private void layoutCards() {
        // Position cards horizontally with absolute positioning
        int x = 2; // Small left margin
        for (MageCard card : cards.values()) {
            card.setCardBounds(x, 2, CARD_WIDTH, CARD_HEIGHT);
            x += CARD_WIDTH + CARD_GAP;
        }

        // Calculate total size based on number of commanders
        int numCards = Math.max(1, cards.size());
        int width = numCards * CARD_WIDTH + (numCards - 1) * CARD_GAP + 10;

        Dimension size = new Dimension(width, CARD_HEIGHT + 4);
        cardArea.setPreferredSize(size);
        setPreferredSize(size);
        setMinimumSize(size);
        revalidate();
    }

    /**
     * Get the number of cards currently displayed.
     */
    public int getCardCount() {
        return cards.size();
    }

    /**
     * Return commander card components keyed by card id.
     * Used by the streaming overlay exporter for pixel-position sync.
     */
    public Map<UUID, MageCard> getCardPanels() {
        return cards;
    }
}
