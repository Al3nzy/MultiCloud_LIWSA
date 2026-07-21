package org.liwsa.multicloud.viz;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Renders a labelled bar chart to a PNG file using nothing but
 * {@code java.awt}/{@code javax.imageio} (both part of the JDK), so this
 * framework does not need a charting library dependency it can't verify
 * resolves without network access in this environment. Offscreen
 * {@code BufferedImage} rendering works headlessly (no display needed),
 * which is what matters for generating charts on a server/CI machine.
 *
 * <p>One renderer covers every "category -&gt; single number" chart this
 * framework needs: cost per algorithm, energy per algorithm, average CPU
 * utilization per cloud, task distribution per cloud, and so on -- only
 * the execution timeline needs its own renderer, see {@link GanttChartRenderer}.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class ChartRenderer2 {

    private static final int WIDTH = 900;
    private static final int HEIGHT = 520;
    private static final int LEFT_MARGIN = 70;
    private static final int RIGHT_MARGIN = 30;
    private static final int TOP_MARGIN = 60;
    private static final int BOTTOM_MARGIN = 70;

    private static final Color[] PALETTE = {
            new Color(66, 133, 244), new Color(219, 68, 55), new Color(244, 180, 0),
            new Color(15, 157, 88), new Color(171, 71, 188), new Color(0, 172, 193)
    };

    private ChartRenderer2() { }

    public static void renderBarChart(String title, String yAxisLabel, List<String> categories,
                                       List<Double> values, Path outputPng) throws IOException {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        g.setColor(Color.BLACK);
        g.setFont(g.getFont().deriveFont(Font.BOLD, 16f));
        drawCentered(g, title, WIDTH / 2, 28);

        double maxVal = 0.0;
        for (double v : values) {
            maxVal = Math.max(maxVal, v);
        }
        if (maxVal <= 0) {
            maxVal = 1.0;
        }

        int chartLeft = LEFT_MARGIN;
        int chartRight = WIDTH - RIGHT_MARGIN;
        int chartTop = TOP_MARGIN;
        int chartBottom = HEIGHT - BOTTOM_MARGIN;
        int chartWidth = chartRight - chartLeft;
        int chartHeight = chartBottom - chartTop;

        g.setColor(Color.DARK_GRAY);
        g.drawLine(chartLeft, chartTop, chartLeft, chartBottom);
        g.drawLine(chartLeft, chartBottom, chartRight, chartBottom);

        int n = Math.max(categories.size(), 1);
        int slot = chartWidth / n;
        int barWidth = Math.max(1, (int) (slot * 0.6));

        g.setFont(g.getFont().deriveFont(Font.PLAIN, 12f));
        for (int i = 0; i < categories.size(); i++) {
            double v = values.get(i);
            int barHeight = (int) (chartHeight * (v / maxVal));
            int x = chartLeft + i * slot + (slot - barWidth) / 2;
            int y = chartBottom - barHeight;

            g.setColor(PALETTE[i % PALETTE.length]);
            g.fillRect(x, y, barWidth, barHeight);
            g.setColor(Color.BLACK);
            g.drawRect(x, y, barWidth, barHeight);

            drawCentered(g, String.format("%.2f", v), x + barWidth / 2, y - 6);
            drawCentered(g, categories.get(i), x + barWidth / 2, chartBottom + 18);
        }

        g.setFont(g.getFont().deriveFont(Font.ITALIC, 12f));
        drawRotatedYLabel(g, yAxisLabel, 18, (chartTop + chartBottom) / 2);

        g.dispose();
        Path parent = outputPng.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ImageIO.write(image, "png", outputPng.toFile());
    }

    private static void drawCentered(Graphics2D g, String text, int centerX, int y) {
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text);
        g.drawString(text, centerX - w / 2, y);
    }

    private static void drawRotatedYLabel(Graphics2D g, String text, int x, int y) {
        AffineTransform old = g.getTransform();
        g.rotate(-Math.PI / 2, x, y);
        drawCentered(g, text, x, y);
        g.setTransform(old);
    }
}
