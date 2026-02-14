package mage.client.streaming;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;
import mage.view.ManaPoolView;
import org.mage.card.arcane.ManaSymbols;

/**
 * Overlay panel that displays floating mana on a player's battlefield.
 * Renders mana symbols with counts horizontally in WUBRG+C order.
 * Positioned at bottom-center of the BattlefieldPanel, above the scroll pane layer.
 * Only visible when total mana > 0.
 */
public class StreamingManaPoolPanel extends JPanel {

    private static final String[] MANA_KEYS = {"W", "U", "B", "R", "G", "C"};

    private final int symbolSize;
    private final int hGap;
    private final int vPad;
    private final int hPad;
    private final Font countFont;

    private int[] manaCounts = new int[6]; // W, U, B, R, G, C
    private int totalMana = 0;

    public StreamingManaPoolPanel(double scaleFactor) {
        this.symbolSize = (int) (20 * scaleFactor);
        this.hGap = (int) (8 * scaleFactor);
        this.hPad = (int) (10 * scaleFactor);
        this.vPad = (int) (4 * scaleFactor);
        this.countFont = new Font(Font.SANS_SERIF, Font.BOLD, (int) (14 * scaleFactor));
        setOpaque(false);
        setVisible(false);
    }

    public void update(ManaPoolView pool) {
        int[] newCounts = {
            pool.getWhite(), pool.getBlue(), pool.getBlack(),
            pool.getRed(), pool.getGreen(), pool.getColorless()
        };

        int newTotal = 0;
        for (int c : newCounts) {
            newTotal += c;
        }

        boolean changed = (newTotal != totalMana);
        if (!changed) {
            for (int i = 0; i < 6; i++) {
                if (newCounts[i] != manaCounts[i]) {
                    changed = true;
                    break;
                }
            }
        }
        if (!changed) {
            return;
        }

        manaCounts = newCounts;
        totalMana = newTotal;
        setVisible(totalMana > 0);
        repaint();
    }

    public int computePreferredWidth() {
        int activeColors = 0;
        for (int c : manaCounts) {
            if (c > 0) {
                activeColors++;
            }
        }
        if (activeColors == 0) {
            return 0;
        }

        FontMetrics fm = getFontMetrics(countFont);
        int width = hPad * 2;
        boolean first = true;
        for (int i = 0; i < 6; i++) {
            if (manaCounts[i] > 0) {
                if (!first) {
                    width += hGap;
                }
                first = false;
                width += symbolSize;
                width += fm.stringWidth(Integer.toString(manaCounts[i])) + 2;
            }
        }
        return width;
    }

    public int getPreferredHeight() {
        return symbolSize + 2 * vPad;
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (totalMana <= 0) {
            return;
        }

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        // Semi-transparent dark background pill
        g2.setColor(new Color(0, 0, 0, 180));
        g2.fillRoundRect(0, 0, w, h, h / 2, h / 2);

        // Draw each mana color that has count > 0
        int x = hPad;
        g2.setFont(countFont);
        FontMetrics fm = g2.getFontMetrics();

        for (int i = 0; i < 6; i++) {
            if (manaCounts[i] <= 0) {
                continue;
            }

            // Draw mana symbol
            BufferedImage symbolImage = ManaSymbols.getSizedManaSymbol(MANA_KEYS[i], symbolSize);
            if (symbolImage != null) {
                int iconY = (h - symbolSize) / 2;
                g2.drawImage(symbolImage, x, iconY, null);
            }
            x += symbolSize + 2;

            // Draw count
            String countStr = Integer.toString(manaCounts[i]);
            g2.setColor(Color.WHITE);
            int textY = (h + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(countStr, x, textY);
            x += fm.stringWidth(countStr) + hGap;
        }

        g2.dispose();
    }
}
