package org.liwsa.multicloud.viz;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
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
 * Renders labelled charts to PNG files using nothing but {@code java.awt}/
 * {@code javax.imageio} (both part of the JDK), so this framework does not
 * need a charting library dependency it can't verify resolves without
 * network access in this environment. Offscreen {@code BufferedImage}
 * rendering works headlessly (no display needed), which is what matters for
 * generating charts on a server/CI machine.
 *
 * <p>{@link #renderBarChart} covers every "category -&gt; single number"
 * chart this framework needs: cost per algorithm, energy per algorithm,
 * average CPU utilization per cloud, task distribution per cloud, and so on.
 * {@link #renderLineChart} covers "metric vs. workload size" scaling plots
 * (one line per algorithm across several task counts, see
 * {@link org.liwsa.multicloud.ScalabilityDemo}). Only the execution timeline
 * needs its own renderer, see {@link GanttChartRenderer}.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class ChartRenderer {

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

    private ChartRenderer() { }

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

    /**
     * Renders a multi-series line chart (one connected polyline + point
     * markers per series, plus a legend) against a shared, ordered x-axis.
     * Built for "metric vs. workload size" scaling plots (see
     * {@link org.liwsa.multicloud.ScalabilityDemo}), where {@code xValues}
     * commonly spans several orders of magnitude -- hence the optional
     * log10-scaled x-axis. The y-axis always stays linear, since the metrics
     * this framework tracks (makespan, cost, ...) don't typically need a log
     * y-axis themselves.
     *
     * @param xValues      the shared x-axis values, in ascending order (e.g. task counts)
     * @param seriesNames  one name per series, in the order to draw/legend them (e.g. algorithm names)
     * @param seriesValues one {@code double[]} per entry in {@code seriesNames}, each the
     *                     same length as {@code xValues} and in the same x-order
     * @param logXAxis     true to log10-scale the x-axis (recommended whenever
     *                     {@code xValues} spans more than roughly one order of magnitude)
     */
    public static void renderLineChart(String title, String xAxisLabel, String yAxisLabel,
                                        int[] xValues, List<String> seriesNames, List<double[]> seriesValues,
                                        boolean logXAxis, Path outputPng) throws IOException {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        g.setColor(Color.BLACK);
        g.setFont(g.getFont().deriveFont(Font.BOLD, 16f));
        drawCentered(g, title, WIDTH / 2, 28);

        // Legend: a colored swatch + name per series, in one row under the title.
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 12f));
        int legendX = LEFT_MARGIN;
        int legendY = 48;
        for (int s = 0; s < seriesNames.size(); s++) {
            g.setColor(PALETTE[s % PALETTE.length]);
            g.fillRect(legendX, legendY - 9, 14, 4);
            g.setColor(Color.BLACK);
            String name = seriesNames.get(s);
            g.drawString(name, legendX + 20, legendY);
            legendX += 20 + g.getFontMetrics().stringWidth(name) + 26;
        }

        int chartLeft = LEFT_MARGIN;
        int chartRight = WIDTH - RIGHT_MARGIN;
        int chartTop = TOP_MARGIN + 20;
        int chartBottom = HEIGHT - BOTTOM_MARGIN;
        int chartWidth = chartRight - chartLeft;
        int chartHeight = chartBottom - chartTop;

        double maxVal = 0.0;
        for (double[] vals : seriesValues) {
            for (double v : vals) {
                if (!Double.isNaN(v)) {
                    maxVal = Math.max(maxVal, v);
                }
            }
        }
        if (maxVal <= 0) {
            maxVal = 1.0;
        }

        double logMin = Math.log10(Math.max(xValues[0], 1));
        double logMax = Math.log10(Math.max(xValues[xValues.length - 1], 1));
        if (logMax <= logMin) {
            logMax = logMin + 1;
        }
        double linMin = xValues[0];
        double linMax = xValues[xValues.length - 1];
        if (linMax <= linMin) {
            linMax = linMin + 1;
        }

        // Horizontal gridlines + y-axis labels at 0%, 50%, 100% of maxVal.
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
        for (int p = 0; p <= 2; p++) {
            double frac = p / 2.0;
            int y = chartBottom - (int) (chartHeight * frac);
            g.setColor(new Color(230, 230, 230));
            g.drawLine(chartLeft, y, chartRight, y);
            g.setColor(Color.DARK_GRAY);
            String label = String.format("%.2f", maxVal * frac);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(label, chartLeft - fm.stringWidth(label) - 6, y + 4);
        }

        g.setColor(Color.DARK_GRAY);
        g.drawLine(chartLeft, chartTop, chartLeft, chartBottom);
        g.drawLine(chartLeft, chartBottom, chartRight, chartBottom);

        // X-axis ticks + labels, one per xValues entry (these are the exact
        // tested task counts, not arbitrary evenly-spaced ticks).
        int[] px = new int[xValues.length];
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 12f));
        for (int i = 0; i < xValues.length; i++) {
            double frac = logXAxis
                    ? (Math.log10(Math.max(xValues[i], 1)) - logMin) / (logMax - logMin)
                    : (xValues[i] - linMin) / (linMax - linMin);
            px[i] = chartLeft + (int) (chartWidth * frac);
            g.setColor(Color.DARK_GRAY);
            g.drawLine(px[i], chartBottom, px[i], chartBottom + 4);
            drawCentered(g, formatCount(xValues[i]), px[i], chartBottom + 18);
        }
        drawCentered(g, xAxisLabel, (chartLeft + chartRight) / 2, HEIGHT - 12);

        // One polyline + point markers per series. NaN entries (skipped/timed-out
        // runs, see ScalabilityDemo) are gaps: no marker, and no line segment
        // drawn to or from them, rather than a misleading drop to zero.
        for (int s = 0; s < seriesValues.size(); s++) {
            double[] vals = seriesValues.get(s);
            g.setColor(PALETTE[s % PALETTE.length]);
            g.setStroke(new BasicStroke(2.2f));
            int prevX = 0;
            int prevY = 0;
            boolean havePrev = false;
            for (int i = 0; i < vals.length; i++) {
                if (Double.isNaN(vals[i])) {
                    havePrev = false;
                    continue;
                }
                int y = chartBottom - (int) (chartHeight * (vals[i] / maxVal));
                if (havePrev) {
                    g.drawLine(prevX, prevY, px[i], y);
                }
                prevX = px[i];
                prevY = y;
                havePrev = true;
            }
            for (int i = 0; i < vals.length; i++) {
                if (Double.isNaN(vals[i])) {
                    continue;
                }
                int y = chartBottom - (int) (chartHeight * (vals[i] / maxVal));
                g.fillOval(px[i] - 4, y - 4, 8, 8);
            }
        }
        g.setStroke(new BasicStroke(1f));

        g.setColor(Color.BLACK);
        g.setFont(g.getFont().deriveFont(Font.ITALIC, 12f));
        drawRotatedYLabel(g, yAxisLabel, 18, (chartTop + chartBottom) / 2);

        g.dispose();
        Path parent = outputPng.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ImageIO.write(image, "png", outputPng.toFile());
    }

    private static String formatCount(int v) {
        if (v >= 1000 && v % 1000 == 0) {
            return (v / 1000) + "k";
        }
        return String.valueOf(v);
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
