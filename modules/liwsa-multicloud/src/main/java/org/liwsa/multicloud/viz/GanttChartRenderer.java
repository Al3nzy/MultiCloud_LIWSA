package org.liwsa.multicloud.viz;

import org.liwsa.multicloud.algorithm.SchedulingResult;
import org.liwsa.multicloud.model.CloudTask;
import org.liwsa.multicloud.model.ResourceCandidate;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Renders the execution timeline / Gantt chart for one {@link SchedulingResult}:
 * one horizontal row per resource that was actually used, one coloured bar
 * per task showing its [start, finish) interval on that resource.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class GanttChartRenderer {

    private static final int ROW_HEIGHT = 24;
    private static final int LEFT_MARGIN = 130;
    private static final int TOP_MARGIN = 50;
    private static final int RIGHT_MARGIN = 30;
    private static final int BOTTOM_MARGIN = 40;
    private static final int CHART_WIDTH = 1000;

    private static final Color[] PALETTE = {
            new Color(66, 133, 244), new Color(219, 68, 55), new Color(244, 180, 0),
            new Color(15, 157, 88), new Color(171, 71, 188), new Color(0, 172, 193)
    };

    private GanttChartRenderer() { }

    public static void render(String title, SchedulingResult result, List<ResourceCandidate> resources,
                               Path outputPng) throws IOException {
        List<CloudTask> taskOrder = result.getTaskOrder();
        int[] assignment = result.getAssignment();
        double[] finishTimes = result.getFinishTimes();
        double makespan = Math.max(result.getMakespan(), 1e-6);

        boolean[] used = new boolean[resources.size()];
        for (int a : assignment) {
            used[a] = true;
        }
        int[] rowOfResource = new int[resources.size()];
        int numUsed = 0;
        for (int r = 0; r < resources.size(); r++) {
            if (used[r]) {
                rowOfResource[r] = numUsed;
                numUsed++;
            }
        }
        numUsed = Math.max(numUsed, 1);

        int height = TOP_MARGIN + BOTTOM_MARGIN + numUsed * ROW_HEIGHT;
        BufferedImage image = new BufferedImage(CHART_WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, CHART_WIDTH, height);

        g.setColor(Color.BLACK);
        g.setFont(g.getFont().deriveFont(Font.BOLD, 16f));
        g.drawString(title, 20, 25);

        int plotWidth = CHART_WIDTH - LEFT_MARGIN - RIGHT_MARGIN;
        double pxPerUnit = plotWidth / makespan;

        g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
        for (int r = 0; r < resources.size(); r++) {
            if (!used[r]) {
                continue;
            }
            ResourceCandidate rc = resources.get(r);
            int y = TOP_MARGIN + rowOfResource[r] * ROW_HEIGHT;
            g.setColor(Color.DARK_GRAY);
            g.drawString(rc.getCloudName() + "-vm" + rc.getIndex(), 4, y + ROW_HEIGHT - 8);
        }

        for (int k = 0; k < taskOrder.size(); k++) {
            int resourceIndex = assignment[k];
            CloudTask task = taskOrder.get(k);
            ResourceCandidate resource = resources.get(resourceIndex);
            double duration = task.getCloudletLength() / Math.max(resource.getMips(), 1e-6);
            double finish = finishTimes[k];
            double start = finish - duration;

            int y = TOP_MARGIN + rowOfResource[resourceIndex] * ROW_HEIGHT;
            int x = LEFT_MARGIN + (int) (start * pxPerUnit);
            int w = Math.max(1, (int) (duration * pxPerUnit));

            g.setColor(PALETTE[task.getCloudletId() % PALETTE.length]);
            g.fillRect(x, y + 3, w, ROW_HEIGHT - 6);
            g.setColor(Color.BLACK);
            g.drawRect(x, y + 3, w, ROW_HEIGHT - 6);
        }

        g.setColor(Color.DARK_GRAY);
        int axisY = TOP_MARGIN + numUsed * ROW_HEIGHT + 10;
        g.drawLine(LEFT_MARGIN, axisY, LEFT_MARGIN + plotWidth, axisY);
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
        for (int t = 0; t <= 10; t++) {
            double timeVal = makespan * t / 10.0;
            int x = LEFT_MARGIN + (int) (timeVal * pxPerUnit);
            g.drawLine(x, axisY, x, axisY + 4);
            g.drawString(String.format("%.0f", timeVal), x - 10, axisY + 16);
        }

        g.dispose();
        Path parent = outputPng.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ImageIO.write(image, "png", outputPng.toFile());
    }
}
