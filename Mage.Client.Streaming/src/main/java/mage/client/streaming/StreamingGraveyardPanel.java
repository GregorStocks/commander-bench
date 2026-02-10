package mage.client.streaming;

import java.awt.*;
import java.util.*;
import java.util.List;
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

/**
 * Streaming-mode graveyard panel with wider cards and a zone label.
 * Fixed size — cards compress their stack offset to always fit without scrolling.
 */
public class StreamingGraveyardPanel extends JPanel {

    // Wider cards to fill the west panel (upstream uses 60)
    private static final int CARD_WIDTH = 80;
    private static final int CARD_HEIGHT = (int) (CARD_WIDTH * GUISizeHelper.CARD_WIDTH_TO_HEIGHT_COEF);

    // Maximum peek offset between stacked cards (shrinks as pile grows)
    private static final int MAX_STACK_OFFSET = 24;
    private static final int MIN_STACK_OFFSET = 5;

    // Fixed content area height (room for card stacking)
    private static final int CONTENT_HEIGHT = CARD_HEIGHT * 2;

    // Panel margin
    private static final int MARGIN = 5;

    private static final Border EMPTY_BORDER = new EmptyBorder(0, 0, 0, 0);
    private static final Font LABEL_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 10);
    private static final Color LABEL_COLOR = new Color(140, 140, 180);
    private static final int LABEL_HEIGHT = 14;

    private static final int PANEL_WIDTH = CARD_WIDTH + 2 * MARGIN;
    private static final int PANEL_HEIGHT = LABEL_HEIGHT + CONTENT_HEIGHT;

    private final Map<UUID, MageCard> cards = new LinkedHashMap<>();
    private JPanel cardArea;
    private BigCard bigCard;
    private UUID gameId;

    public StreamingGraveyardPanel() {
        initComponents();
    }

    private void initComponents() {
        cardArea = new JPanel();
        cardArea.setLayout(null); // Absolute positioning for stacked cards
        cardArea.setBackground(new Color(0, 0, 0, 0));
        cardArea.setOpaque(false);

        JLabel label = new JLabel("GY");
        label.setFont(LABEL_FONT);
        label.setForeground(LABEL_COLOR);
        label.setPreferredSize(new Dimension(0, LABEL_HEIGHT));
        label.setBorder(new EmptyBorder(1, MARGIN, 0, 0));

        setOpaque(true);
        setBackground(new Color(50, 50, 80)); // Dark blue-gray
        setBorder(EMPTY_BORDER);
        setLayout(new BorderLayout());
        add(label, BorderLayout.NORTH);
        add(cardArea, BorderLayout.CENTER);

        setPreferredSize(new Dimension(PANEL_WIDTH, PANEL_HEIGHT));
        setMinimumSize(new Dimension(PANEL_WIDTH, PANEL_HEIGHT));
        setMaximumSize(new Dimension(PANEL_WIDTH, PANEL_HEIGHT));
    }

    public void cleanUp() {
        cards.clear();
        cardArea.removeAll();
    }

    public void loadCards(CardsView cardsView, BigCard bigCard, UUID gameId) {
        this.bigCard = bigCard;
        this.gameId = gameId;

        // Remove cards no longer in graveyard
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
        mageCard.setZone(Zone.GRAVEYARD);
        mageCard.setCardBounds(0, 0, CARD_WIDTH, CARD_HEIGHT);
        mageCard.update(cardView);

        cards.put(cardView.getId(), mageCard);
        cardArea.add(mageCard);
    }

    private void layoutCards() {
        if (cards.isEmpty()) {
            return;
        }

        List<MageCard> cardList = new ArrayList<>(cards.values());
        int n = cardList.size();

        // Dynamically compute stack offset so all cards fit in CONTENT_HEIGHT
        int offset;
        if (n <= 1) {
            offset = 0;
        } else {
            int availableForOffsets = CONTENT_HEIGHT - CARD_HEIGHT;
            offset = Math.min(MAX_STACK_OFFSET, availableForOffsets / (n - 1));
            offset = Math.max(MIN_STACK_OFFSET, offset);
        }

        int y = 0;
        for (int i = 0; i < n; i++) {
            MageCard card = cardList.get(i);
            card.setCardBounds(0, y, CARD_WIDTH, CARD_HEIGHT);
            cardArea.setComponentZOrder(card, n - 1 - i);
            if (i < n - 1) {
                y += offset;
            }
        }

        cardArea.setPreferredSize(new Dimension(PANEL_WIDTH, CONTENT_HEIGHT));
    }

    public int getCardCount() {
        return cards.size();
    }

    /**
     * Return card components keyed by card id.
     * Used by the overlay exporter for pixel-position sync (avoids reflection).
     */
    public Map<UUID, MageCard> getCardPanels() {
        return cards;
    }
}
