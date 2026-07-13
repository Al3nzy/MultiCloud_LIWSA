package org.liwsa.multicloud.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.liwsa.multicloud.model.CloudTask;
import org.liwsa.multicloud.model.TaskPriority;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/**
 * Reads a task workload from CSV, XML, or JSON into {@link CloudTask}s,
 * using the same schema across all three formats so a workload can be
 * converted between them without losing information:
 *
 * <pre>
 * required: id, length
 * optional: priority (HIGH/MEDIUM/LOW or 1/2/3, default MEDIUM),
 *           deadline (absolute sim time, default: none),
 *           memoryMb, bandwidthMbps (defaults from CloudTask.Builder),
 *           arrivalTime (default 0), pes (default 1),
 *           dependencies (other task ids this one depends on, default none)
 * </pre>
 *
 * <p>CSV: header row with the column names above, in any order
 * (case-insensitive); {@code dependencies} is a {@code ;}-separated list of
 * ids, e.g. {@code "3;7;12"}. Parsed with plain Java (no CSV library) via a
 * simple comma split -- this deliberately does not support commas embedded
 * inside a quoted field, since none of this schema's columns need one.
 *
 * <p>XML: {@code <tasks><task id="0" length="12000" priority="HIGH" .../></tasks>},
 * one {@code <task>} element per task, same attribute names as the CSV
 * columns, {@code dependencies} likewise {@code ;}-separated.
 *
 * <p>JSON: a top-level array of objects with the same field names, e.g.
 * {@code [{"id":0,"length":12000,"priority":"HIGH","dependencies":[3,7]}]}
 * -- here {@code dependencies} is a genuine JSON array of integers rather
 * than a delimited string.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class TaskWorkloadReader {

    private TaskWorkloadReader() { }

    // ---------------------------------------------------------------
    // CSV (plain Java, no external library)
    // ---------------------------------------------------------------
    public static List<CloudTask> readCsv(Path path) throws IOException {
        List<String> lines;
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            lines = reader.lines().toList();
        }
        if (lines.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> col = indexHeader(splitCsvLine(lines.get(0)));
        List<CloudTask> tasks = new ArrayList<>();

        for (int lineNum = 1; lineNum < lines.size(); lineNum++) {
            String line = lines.get(lineNum);
            if (line.isBlank()) {
                continue;
            }
            String[] row = splitCsvLine(line);

            int id = Integer.parseInt(value(row, col, "id").trim());
            long length = Long.parseLong(value(row, col, "length").trim());
            CloudTask.Builder b = new CloudTask.Builder(id, length);

            applyPriority(b, value(row, col, "priority"));
            applyOptionalDouble(b::deadline, value(row, col, "deadline"));
            applyOptionalLong(b::memoryRequirementMb, value(row, col, "memorymb"));
            applyOptionalLong(b::bandwidthRequirementMbps, value(row, col, "bandwidthmbps"));
            applyOptionalDouble(b::arrivalTime, value(row, col, "arrivaltime"));
            applyOptionalInt(b::pes, value(row, col, "pes"));
            applyDependencies(b, value(row, col, "dependencies"));

            tasks.add(b.build());
        }
        return tasks;
    }

    /** Splits one CSV line on commas not inside a double-quoted field, trimming a wrapping quote pair if present. */
    private static String[] splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    private static Map<String, Integer> indexHeader(String[] header) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            map.put(header[i].trim().toLowerCase(), i);
        }
        return map;
    }

    private static String value(String[] row, Map<String, Integer> col, String name) {
        Integer idx = col.get(name.toLowerCase());
        if (idx == null || idx >= row.length) {
            return null;
        }
        return row[idx];
    }

    // ---------------------------------------------------------------
    // XML
    // ---------------------------------------------------------------
    public static List<CloudTask> readXml(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Harden against XXE: this reader only ever needs plain elements/attributes.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(path.toFile());
        doc.getDocumentElement().normalize();

        NodeList nodes = doc.getElementsByTagName("task");
        List<CloudTask> tasks = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            int id = Integer.parseInt(el.getAttribute("id").trim());
            long length = Long.parseLong(el.getAttribute("length").trim());
            CloudTask.Builder b = new CloudTask.Builder(id, length);

            applyPriority(b, attrOrNull(el, "priority"));
            applyOptionalDouble(b::deadline, attrOrNull(el, "deadline"));
            applyOptionalLong(b::memoryRequirementMb, attrOrNull(el, "memoryMb"));
            applyOptionalLong(b::bandwidthRequirementMbps, attrOrNull(el, "bandwidthMbps"));
            applyOptionalDouble(b::arrivalTime, attrOrNull(el, "arrivalTime"));
            applyOptionalInt(b::pes, attrOrNull(el, "pes"));
            applyDependencies(b, attrOrNull(el, "dependencies"));

            tasks.add(b.build());
        }
        return tasks;
    }

    private static String attrOrNull(Element el, String name) {
        if (!el.hasAttribute(name)) {
            return null;
        }
        String v = el.getAttribute(name);
        return v.isBlank() ? null : v;
    }

    // ---------------------------------------------------------------
    // JSON
    // ---------------------------------------------------------------
    public static List<CloudTask> readJson(Path path) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(path.toFile());

        List<CloudTask> tasks = new ArrayList<>();
        for (JsonNode node : root) {
            int id = node.get("id").asInt();
            long length = node.get("length").asLong();
            CloudTask.Builder b = new CloudTask.Builder(id, length);

            if (hasNonNull(node, "priority")) {
                applyPriority(b, node.get("priority").asText());
            }
            if (hasNonNull(node, "deadline")) {
                b.deadline(node.get("deadline").asDouble());
            }
            if (hasNonNull(node, "memoryMb")) {
                b.memoryRequirementMb(node.get("memoryMb").asLong());
            }
            if (hasNonNull(node, "bandwidthMbps")) {
                b.bandwidthRequirementMbps(node.get("bandwidthMbps").asLong());
            }
            if (hasNonNull(node, "arrivalTime")) {
                b.arrivalTime(node.get("arrivalTime").asDouble());
            }
            if (hasNonNull(node, "pes")) {
                b.pes(node.get("pes").asInt());
            }
            if (node.has("dependencies") && node.get("dependencies").isArray()) {
                List<Integer> deps = new ArrayList<>();
                for (JsonNode d : node.get("dependencies")) {
                    deps.add(d.asInt());
                }
                b.dependencies(deps);
            }
            tasks.add(b.build());
        }
        return tasks;
    }

    private static boolean hasNonNull(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull();
    }

    // ---------------------------------------------------------------
    // Shared field parsing
    // ---------------------------------------------------------------
    private static void applyPriority(CloudTask.Builder b, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String s = raw.trim().toUpperCase();
        TaskPriority priority = switch (s) {
            case "1", "HIGH" -> TaskPriority.HIGH;
            case "3", "LOW" -> TaskPriority.LOW;
            default -> TaskPriority.MEDIUM;
        };
        b.priority(priority);
    }

    private static void applyDependencies(CloudTask.Builder b, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        List<Integer> ids = new ArrayList<>();
        for (String part : raw.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                ids.add(Integer.parseInt(trimmed));
            }
        }
        b.dependencies(ids);
    }

    private static void applyOptionalDouble(DoubleConsumer setter, String raw) {
        if (raw != null && !raw.isBlank()) {
            setter.accept(Double.parseDouble(raw.trim()));
        }
    }

    private static void applyOptionalLong(LongConsumer setter, String raw) {
        if (raw != null && !raw.isBlank()) {
            setter.accept(Long.parseLong(raw.trim()));
        }
    }

    private static void applyOptionalInt(IntConsumer setter, String raw) {
        if (raw != null && !raw.isBlank()) {
            setter.accept(Integer.parseInt(raw.trim()));
        }
    }
}
