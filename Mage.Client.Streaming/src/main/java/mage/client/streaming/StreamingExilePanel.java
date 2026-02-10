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
 * Streaming-mode exile panel with wider cards, a zone label, and a
 * semi-transparent reddish tint over cards to visually distinguish
 * exile from graveyard (echoing the paper convention of exiling sideways).
 */
public class StreamingExilePanel extends JPanel {

    // Wider cards to fill the west panel (upstream uses 60)
    private static final int CARD_WIDTH = 80;
    private static final int CARD_HEIGHT = (int) (CARD_WIDTH * GUISizeHelper.CARD_WIDTH_TO_HEIGHT_COEF);

    // How much of each card is visible when stacked
    private static final int STACK_OFFSET = 24;

    // Panel margin
    private static final int MARGIN = 5;

    // Red tint painted over exile cards for visual differentiation
    private static final Color EXILE_TINT = new Color(120, 30, 30, 40);

    private static final Border EMPTY_BORDER = new EmptyBorder(0, 0, 0, 0);
    private static final Font LABEL_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 10);
    private static final Color LABEL_COLOR = new Color(200, 130, 130);
    private static final int LABEL_HEIGHT = 14;

    private final Map<UUID, MageCard> cards = new LinkedHashMap<>();
    private JPanel cardArea;
    private JScrollPane jScrollPane;
    private BigCard bigCard;
    private UUID gameId;

    public StreamingExilePanel() {
        initComponents();
    }

    private void initComponents() {
        // Card area with a red tint overlay painted after children
        cardArea = new JPanel() {
            @Override
            protected void paintChildren(Graphics g) {
                super.paintChildren(g);
                // Paint semi-transparent red wash over cards
                if (cards.size() > 0) {
                    g.setColor(EXILE_TINT);
                    g.fillRect(0, 0, getWidth(), getHeight());
                }
            }
        };
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

        JLabel label = new JLabel("EXILE");
        label.setFont(LABEL_FONT);
        label.setForeground(LABEL_COLOR);
        label.setPreferredSize(new Dimension(0, LABEL_HEIGHT));
        label.setBorder(new EmptyBorder(1, MARGIN, 0, 0));

        setOpaque(true);
        setBackground(new Color(90, 40, 40)); // More distinctly reddish than upstream
        setBorder(EMPTY_BORDER);
        setLayout(new BorderLayout());
        add(label, BorderLayout.NORTH);
        add(jScrollPane, BorderLayout.CENTER);

        int panelWidth = CARD_WIDTH + 2 * MARGIN;
        setPreferredSize(new Dimension(panelWidth, LABEL_HEIGHT + CARD_HEIGHT));
        setMinimumSize(new Dimension(panelWidth, 50));
    }

    public void cleanUp() {
        cards.clear();
        cardArea.removeAll();
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
            totalHeight = CARD_HEIGHT;
        } else {
            List<MageCard> cardList = new ArrayList<>(cards.values());
            int y = 0;
            for (int i = 0; i < cardList.size(); i++) {
                MageCard card = cardList.get(i);
                card.setCardBounds(0, y, CARD_WIDTH, CARD_HEIGHT);
                cardArea.setComponentZOrder(card, cardList.size() - 1 - i);
                y += STACK_OFFSET;
            }
            totalHeight = (cardList.size() - 1) * STACK_OFFSET + CARD_HEIGHT;
        }

        int panelWidth = CARD_WIDTH + 2 * MARGIN;
        Dimension cardAreaSize = new Dimension(panelWidth, Math.max(totalHeight, CARD_HEIGHT));
        cardArea.setPreferredSize(cardAreaSize);

        Dimension panelSize = new Dimension(panelWidth, LABEL_HEIGHT + Math.max(totalHeight, CARD_HEIGHT));
        setPreferredSize(panelSize);
        setMinimumSize(panelSize);
        revalidate();
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
