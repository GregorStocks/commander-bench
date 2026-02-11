package mage.client.game;

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
 * Panel for displaying exiled cards in a stacked vertical layout.
 * Cards are stacked on top of each other with only the top portion visible.
 * Used in streaming/spectator mode to show each player's exiled cards.
 */
public class ExilePanel extends JPanel {

    // Card dimensions for exile display
    private static final int CARD_WIDTH = 60;
    private static final int CARD_HEIGHT = (int) (CARD_WIDTH * GUISizeHelper.CARD_WIDTH_TO_HEIGHT_COEF);

    // How much of each card is visible when stacked (pixels from top)
    private static final int STACK_OFFSET = 18;

    private static final Border EMPTY_BORDER = new EmptyBorder(0, 0, 0, 0);

    private final Map<UUID, MageCard> cards = new LinkedHashMap<>();
    private JPanel cardArea;
    private JScrollPane jScrollPane;
    private BigCard bigCard;
    private UUID gameId;

    public ExilePanel() {
        initComponents();
    }

    private void initComponents() {
        cardArea = new JPanel();
        cardArea.setLayout(null); // Absolute positioning for stacked cards
        cardArea.setBackground(new Color(0, 0, 0, 0));
        cardArea.setOpaque(false);

        jScrollPane = new JScrollPane(cardArea);
        jScrollPane.getViewport().setBackground(new Color(0, 0, 0, 0));
        jScrollPane.setOpaque(false);
        jScrollPane.setBorder(EMPTY_BORDER);
        jScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        jScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        jScrollPane.getVerticalScrollBar().setUnitIncrement(STACK_OFFSET);
        jScrollPane.setViewportBorder(EMPTY_BORDER);

        setOpaque(true);
        setBackground(new Color(80, 50, 50)); // Dark red-gray background to distinguish from graveyard
        setBorder(EMPTY_BORDER);
        setLayout(new BorderLayout());
        add(jScrollPane, BorderLayout.CENTER);

        // Set initial preferred size so panel is visible even when empty
        setPreferredSize(new Dimension(CARD_WIDTH + 10, CARD_HEIGHT));
        setMinimumSize(new Dimension(CARD_WIDTH + 10, 50));
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

        // Remove cards no longer in exile
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
        mageCard.setZone(Zone.EXILED);
        mageCard.setCardBounds(0, 0, CARD_WIDTH, CARD_HEIGHT);
        mageCard.update(cardView);

        cards.put(cardView.getId(), mageCard);
        cardArea.add(mageCard);
    }

    private void layoutCards() {
        int totalHeight;
        if (cards.isEmpty()) {
            totalHeight = CARD_HEIGHT; // Minimum height when empty
        } else {
            // Stack cards vertically - newest on top (highest z-order)
            // Cards are positioned so each one peeks out from behind the one above
            List<MageCard> cardList = new ArrayList<>(cards.values());

            int y = 0;
            for (int i = 0; i < cardList.size(); i++) {
                MageCard card = cardList.get(i);
                // Use setCardBounds which is the proper method for MageCard positioning
                card.setCardBounds(0, y, CARD_WIDTH, CARD_HEIGHT);
                // Set z-order: first card (bottom of exile) at back, last card (top) at front
                cardArea.setComponentZOrder(card, cardList.size() - 1 - i);

                // Next card positioned to show just the top portion of this card
                y += STACK_OFFSET;
            }

            // Total height: stack offsets + full height of top card
            totalHeight = (cardList.size() - 1) * STACK_OFFSET + CARD_HEIGHT;
        }

        // Update both the card area and this panel's preferred size
        Dimension size = new Dimension(CARD_WIDTH + 10, Math.max(totalHeight, CARD_HEIGHT));
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
}
