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

    /** Robotic intros / meta phrases to strip from the start of a reply. */
    private static final List<Pattern> META_INTRO = List.of(
            Pattern.compile("(?is)^\\s*based on (the )?(product )?data available in my knowledge base[,:]?\\s*(here's what i found[:]?\\s*)?"),
            Pattern.compile("(?is)^\\s*based on (the )?(information|data|documents?) (available )?in my knowledge base[,:]?\\s*"),
            Pattern.compile("(?is)^\\s*from (the )?(products?|data) (sheet|available in my knowledge base)[,:]?\\s*"),
            Pattern.compile("(?is)^\\s*here's what i found[:\\s]*"),
            Pattern.compile("(?is)^\\s*according to (my|the) knowledge base[,:]?\\s*")
    );

    private ChatResponseFormatter() {}

    public static String forChatWidget(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String text = convertMarkdownTablesToBullets(raw);
        text = stripTruncationDisclaimers(text);
        text = stripMetaIntros(text);
        text = softenTechnicalMissingInfo(text);
        text = scrubKnowledgeJargon(text);
        return text.trim();
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
                || lower.contains("no field") || lower.contains("column for"))
                && (lower.contains("don't have") || lower.contains("do not have")
                || lower.contains("doesn't contain") || lower.contains("does not contain")
                || lower.contains("i don't have specific"));

        if (!technicalMissing) {
            return text;
        }

        // Keep any leading bullet facts; replace the technical paragraph(s)
        String[] lines = text.split("\\R", -1);
        StringBuilder kept = new StringBuilder();
        boolean replaced = false;
        for (String line : lines) {
            String l = line.toLowerCase(Locale.ROOT);
            boolean badLine = l.contains("knowledge base")
                    || l.contains("field tracking")
                    || l.contains("doesn't contain a field")
                    || l.contains("does not contain a field")
                    || (l.contains("i don't have specific information") && l.contains("product"));
            if (badLine) {
                if (!replaced) {
                    kept.append("I don't have that detail for these products yet. ")
                            .append("I can help with pricing, features, or WhatsApp options if you want.\n");
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
        t = t.replaceAll("(?i)\\bPDF\\b", "document");
        return t;
    }
}
