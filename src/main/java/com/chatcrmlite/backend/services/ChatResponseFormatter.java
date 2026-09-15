package com.chatcrmlite.backend.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Post-process LLM replies for chat widgets that do not render Markdown tables
 * and should not sound like a PDF/knowledge-base dump.
 */
public final class ChatResponseFormatter {

    private static final Pattern MD_TABLE_ROW = Pattern.compile("^\\s*\\|(.+)\\|\\s*$");
    private static final Pattern RAW_ROW_PREFIX = Pattern.compile("(?im)^\\s*Row:\\s*");
    private static final Pattern PIPE_CELL = Pattern.compile("\\s*\\|\\s*");

    /** Robotic intros / meta phrases to strip from the start of a reply. */
    private static final List<Pattern> META_INTRO = List.of(
            Pattern.compile("(?is)^\\s*based on (the )?(product )?data available in my knowledge base[,:]?\\s*(here's what i found[:]?\\s*)?"),
            Pattern.compile("(?is)^\\s*based on (the )?(information|data|documents?) (available )?in my knowledge base[,:]?\\s*"),
            Pattern.compile("(?is)^\\s*based on (the )?(available )?(product )?data[,:]?\\s*(here's what i found[:]?\\s*)?"),
            Pattern.compile("(?is)^\\s*from (the )?(products?|data) (sheet|available in my knowledge base)[,:]?\\s*"),
            Pattern.compile("(?is)^\\s*here's what i found[:\\s]*"),
            Pattern.compile("(?is)^\\s*here is what i found[:\\s]*"),
            Pattern.compile("(?is)^\\s*according to (my|the) knowledge base[,:]?\\s*"),
            Pattern.compile("(?is)^\\s*from (the )?provided (context|documents?|sources?)[,:]?\\s*"),
            Pattern.compile("(?is)^\\s*looking at (the )?(product )?data[,:]?\\s*")
    );

    private ChatResponseFormatter() {}

    public static String forChatWidget(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String text = convertMarkdownTablesToBullets(raw);
        text = convertRawRowDumps(text);
        text = stripTruncationDisclaimers(text);
        text = stripMetaIntros(text);
        text = softenTechnicalMissingInfo(text);
        text = scrubKnowledgeJargon(text);
        text = text.replaceAll("[ \\t]{2,}", " ");
        text = text.replaceAll("\n{3,}", "\n\n");
        return text.trim();
    }

    /**
     * True when a cached/LLM reply still sounds like a document dump and should be regenerated.
     */
    public static boolean looksRobotic(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.contains("knowledge base")
                || lower.contains("products sheet")
                || lower.contains("field tracking")
                || lower.contains("vector_context")
                || lower.contains("graph_context")
                || lower.contains("here's what i found")
                || lower.contains("here is what i found")
                || lower.contains("appears truncated")
                || lower.contains("|---|")
                || lower.contains("|---");
    }

    static String convertMarkdownTablesToBullets(String raw) {
        String[] lines = raw.split("\\R", -1);
        StringBuilder out = new StringBuilder();
        List<String> headers = null;
        boolean inTable = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher row = MD_TABLE_ROW.matcher(line);
            if (row.matches()) {
                List<String> cells = splitCells(row.group(1));
                if (isSeparatorCells(cells)) {
                    inTable = headers != null;
                    continue;
                }
                if (!inTable) {
                    boolean nextIsSep = i + 1 < lines.length && isMarkdownSeparatorLine(lines[i + 1]);
                    if (nextIsSep) {
                        headers = cells;
                        inTable = true;
                        continue;
                    }
                }
                if (inTable) {
                    out.append(formatBulletFromCells(cells)).append('\n');
                    continue;
                }
                // Lone pipe row without header — still convert to a bullet
                if (cells.size() >= 2) {
                    out.append(formatBulletFromCells(cells)).append('\n');
                    continue;
                }
            } else if (inTable) {
                inTable = false;
                headers = null;
                if (line.isBlank()) {
                    out.append('\n');
                    continue;
                }
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    /**
     * Convert copied sheet syntax like "Row: Product_Name: X | Category: Y | Price: Z"
     * into a clean bullet.
     */
    static String convertRawRowDumps(String text) {
        String[] lines = text.split("\\R", -1);
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (RAW_ROW_PREFIX.matcher(trimmed).find() || looksLikeLabeledPipeRow(trimmed)) {
                String body = RAW_ROW_PREFIX.matcher(trimmed).replaceFirst("");
                String bullet = prettifyLabeledCells(body);
                out.append("• ").append(bullet).append('\n');
            } else {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static boolean looksLikeLabeledPipeRow(String line) {
        if (line == null || !line.contains("|") || line.startsWith("•")) {
            return false;
        }
        // e.g. Product_Name: Foo | Price_INR: 999
        return line.matches("(?i).*\\b[a-z][a-z0-9_]{1,40}\\s*:\\s*.+\\|.*");
    }

    private static String prettifyLabeledCells(String body) {
        String[] parts = PIPE_CELL.split(body);
        List<String> pretty = new ArrayList<>();
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int colon = p.indexOf(':');
            if (colon > 0 && colon < p.length() - 1) {
                String label = humanizeLabel(p.substring(0, colon).trim());
                String value = p.substring(colon + 1).trim();
                if (!value.isEmpty()) {
                    pretty.add(label + ": " + value);
                }
            } else {
                pretty.add(p);
            }
        }
        return String.join(" — ", pretty);
    }

    private static String humanizeLabel(String raw) {
        String s = raw.replace('_', ' ').trim();
        if (s.isEmpty()) return raw;
        // Price INR → Price
        s = s.replaceAll("(?i)\\s*\\binr\\b", "").trim();
        return s;
    }

    private static boolean isMarkdownSeparatorLine(String line) {
        Matcher m = MD_TABLE_ROW.matcher(line);
        if (m.matches()) {
            return isSeparatorCells(splitCells(m.group(1)));
        }
        String t = line.trim();
        return t.matches("^\\|?[\\s:|-]+\\|?[\\s:|-]*$");
    }

    private static boolean isSeparatorCells(List<String> cells) {
        if (cells.isEmpty()) return false;
        return cells.stream().allMatch(c -> c.replace("-", "").replace(":", "").isBlank());
    }

    private static List<String> splitCells(String inner) {
        String[] parts = inner.split("\\|", -1);
        List<String> cells = new ArrayList<>();
        for (String p : parts) {
            cells.add(p.trim());
        }
        while (!cells.isEmpty() && cells.get(0).isEmpty()) {
            cells.remove(0);
        }
        while (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) {
            cells.remove(cells.size() - 1);
        }
        return cells;
    }

    private static String formatBulletFromCells(List<String> cells) {
        String joined = cells.stream()
                .filter(c -> c != null && !c.isBlank())
                .collect(Collectors.joining(" — "));
        return "• " + joined;
    }

    static String stripTruncationDisclaimers(String text) {
        String[] lines = text.split("\\R", -1);
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("appears truncated")
                    || lower.contains("list appears truncated")
                    || (lower.contains("note:") && lower.contains("truncat"))
                    || (lower.contains("additional products") && (lower.contains("note") || lower.contains("⚠️")
                    || lower.contains("truncated")))) {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString().replaceAll("\n{3,}", "\n\n");
    }

    static String stripMetaIntros(String text) {
        String t = text.trim();
        for (Pattern p : META_INTRO) {
            t = p.matcher(t).replaceFirst("");
        }
        return t.trim();
    }

    /**
     * Rewrite long "missing field in knowledge base" excuses into a short friendly line.
     */
    static String softenTechnicalMissingInfo(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        boolean technicalMissing = (lower.contains("knowledge base") || lower.contains("doesn't contain a field")
                || lower.contains("does not contain a field") || lower.contains("field tracking")
                || lower.contains("no field") || lower.contains("column for")
                || lower.contains("available info doesn't") || lower.contains("what i have doesn't"))
                && (lower.contains("don't have") || lower.contains("do not have")
                || lower.contains("doesn't contain") || lower.contains("does not contain")
                || lower.contains("i don't have specific") || lower.contains("no information"));

        if (!technicalMissing) {
            return text;
        }

        String[] lines = text.split("\\R", -1);
        StringBuilder kept = new StringBuilder();
        boolean replaced = false;
        for (String line : lines) {
            String l = line.toLowerCase(Locale.ROOT);
            boolean badLine = l.contains("knowledge base")
                    || l.contains("field tracking")
                    || l.contains("doesn't contain a field")
                    || l.contains("does not contain a field")
                    || l.contains("available info")
                    || l.contains("what i have")
                    || (l.contains("i don't have specific information") && l.contains("product"))
                    || (l.contains("column") && (l.contains("don't") || l.contains("does not") || l.contains("doesn't")));
            if (badLine) {
                if (!replaced) {
                    kept.append("I don't have that detail yet — happy to help with pricing, features, or WhatsApp options.\n");
                    replaced = true;
                }
                continue;
            }
            kept.append(line).append('\n');
        }
        return kept.toString();
    }

    static String scrubKnowledgeJargon(String text) {
        String t = text;
        t = t.replaceAll("(?i)\\bmy knowledge base\\b", "what I have");
        t = t.replaceAll("(?i)\\bthe knowledge base\\b", "what I have");
        t = t.replaceAll("(?i)\\bknowledge base\\b", "available info");
        t = t.replaceAll("(?i)\\bfrom the Products sheet[,:]?\\s*", "");
        t = t.replaceAll("(?i)\\bProducts sheet\\b", "products");
        t = t.replaceAll("(?i)\\bExcel (file|sheet|data)\\b", "product list");
        t = t.replaceAll("(?i)\\bCSV\\b", "product list");
        t = t.replaceAll("(?i)\\bPDF\\b", "document");
        t = t.replaceAll("(?i)\\bvector[_ ]context\\b", "details");
        t = t.replaceAll("(?i)\\bgraph[_ ]context\\b", "details");
        t = t.replaceAll("(?i)\\b\\(sources?\\)\\b", "");
        return t;
    }
}
