package mage.client.game;

import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.util.UUID;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import mage.client.cards.BigCard;
import mage.client.plugins.adapters.MageActionCallback;
import mage.client.util.GUISizeHelper;
import mage.cards.MageCardSpace;
import mage.constants.Zone;
import mage.view.CardsView;

public class HandPanel extends JPanel {

    public HandPanel() {
        initComponents();
        changeGUISize();
    }

    public void initComponents() {
        jPanel = new JPanel();
        jScrollPane1 = new JScrollPane(jPanel);
        jScrollPane1.getViewport().setBackground(new Color(0, 0, 0, 0));

        hand = new mage.client.cards.Cards(true, jScrollPane1);
        hand.setCardDimension(GUISizeHelper.handCardDimension);

        jPanel.setLayout(new GridBagLayout()); // centers hand
        jPanel.setBackground(new Color(0, 0, 0, 0));
        jPanel.add(hand);

        setOpaque(false);
        jPanel.setOpaque(false);
        jScrollPane1.setOpaque(false);

        jPanel.setBorder(EMPTY_BORDER);
        jScrollPane1.setBorder(EMPTY_BORDER);
        jScrollPane1.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        jScrollPane1.getHorizontalScrollBar().setUnitIncrement(8);
        jScrollPane1.setViewportBorder(EMPTY_BORDER);

        setLayout(new BorderLayout());
        add(jScrollPane1, BorderLayout.CENTER);

        hand.setHScrollSpeed(8);
        hand.setBackgroundColor(new Color(0, 0, 0, 0));
        hand.setVisibleIfEmpty(false);
        hand.setBorder(EMPTY_BORDER);
        hand.setZone(Zone.HAND);
    }

    public void cleanUp() {
        hand.cleanUp();
    }

    public void changeGUISize() {
        setGUISize();
    }

    private void setGUISize() {
        jScrollPane1.getVerticalScrollBar().setPreferredSize(new Dimension(GUISizeHelper.scrollBarSize, 0));
        jScrollPane1.getHorizontalScrollBar().setPreferredSize(new Dimension(0, GUISizeHelper.scrollBarSize));
        jScrollPane1.getHorizontalScrollBar().setUnitIncrement(GUISizeHelper.getCardsScrollbarUnitInc(GUISizeHelper.handCardDimension.width));
        hand.setCardDimension(GUISizeHelper.handCardDimension);
        hand.changeGUISize();
    }

    public void loadCards(CardsView cards, BigCard bigCard, UUID gameId) {
        hand.loadCards(cards, bigCard, gameId, false);
        // Recalculate scaling when card count changes
        if (scaleToFit) {
            recalculateCardScale();
        }
    }

    private JPanel jPanel;
    private JScrollPane jScrollPane1;
    private static final Border EMPTY_BORDER = new EmptyBorder(0, 0, 0, 0);
    private mage.client.cards.Cards hand;
    private boolean scaleToFit = false;
    private ComponentListener resizeListener;

    /**
     * Enable or disable auto-scaling of cards to fit available width.
     * When enabled, cards will shrink to fit without horizontal scrolling.
     */
    public void setScaleToFit(boolean enable) {
        if (this.scaleToFit == enable) {
            return;
        }
        this.scaleToFit = enable;

        if (enable) {
            // Hide horizontal scrollbar since we'll fit everything
            jScrollPane1.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

            // Listen for resize events to recalculate card scale
            if (resizeListener == null) {
                resizeListener = new ComponentAdapter() {
                    @Override
                    public void componentResized(ComponentEvent e) {
                        recalculateCardScale();
                    }
                };
            }
            jScrollPane1.getViewport().addComponentListener(resizeListener);

            // Initial calculation
            recalculateCardScale();
        } else {
            // Restore normal scrolling behavior
            jScrollPane1.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

            if (resizeListener != null) {
                jScrollPane1.getViewport().removeComponentListener(resizeListener);
            }

            // Restore default card size
            hand.setCardDimension(GUISizeHelper.handCardDimension);
            hand.changeGUISize();
        }
    }

    /**
     * Recalculate card dimensions to fit all cards in the available width.
     */
    private void recalculateCardScale() {
        if (!scaleToFit) {
            return;
        }

        int cardCount = hand.getNumberOfCards();
        if (cardCount == 0) {
            return;
        }

        int availableWidth = jScrollPane1.getViewport().getWidth();
        if (availableWidth <= 0) {
            return;
        }

        // Get spacing constants
        int gapX = MageActionCallback.HAND_CARDS_BETWEEN_GAP_X;
        MageCardSpace margins = MageActionCallback.HAND_CARDS_MARGINS;

        // Calculate usable width for cards
        int totalMargins = margins.getLeft() + margins.getRight();
        int totalGaps = (cardCount - 1) * gapX;
        int widthForCards = availableWidth - totalMargins - totalGaps;

        // Calculate optimal card width
        int cardWidth = widthForCards / cardCount;

        // Clamp to reasonable bounds
        int baseWidth = GUISizeHelper.handCardDimension.width;
        int minWidth = baseWidth / 3;
        cardWidth = Math.min(cardWidth, baseWidth);
        cardWidth = Math.max(cardWidth, minWidth);

        // Calculate height maintaining aspect ratio
        int cardHeight = (int) (cardWidth * GUISizeHelper.CARD_WIDTH_TO_HEIGHT_COEF);

        Dimension scaledDimension = new Dimension(cardWidth, cardHeight);
        hand.setCardDimension(scaledDimension);
        hand.changeGUISize();
    }

}
