package com.byd.turnsignalguard.capture;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BulletSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Small, deliberately limited Markdown reader for release notes.
 *
 * <p>The parser has no Android dependency in its public seam. The renderer is
 * the only method that creates Android spans, so local JVM tests can exercise
 * all syntax without loading an Android framework implementation.</p>
 */
final class ReleaseNotesMarkdown {
    private static final String DIVIDER = "────────";

    private ReleaseNotesMarkdown() {}

    /** Parses supported block syntax without touching Android framework classes. */
    static List<Block> parse(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return Collections.emptyList();
        }
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        List<Block> blocks = new ArrayList<>(lines.length);
        for (String line : lines) {
            blocks.add(parseBlock(line));
        }
        return Collections.unmodifiableList(blocks);
    }

    /** Alias kept explicit at call sites that want to document the block seam. */
    static List<Block> parseBlocks(String markdown) {
        return parse(markdown);
    }

    /** Parses supported inline syntax without touching Android framework classes. */
    static List<Inline> parseInline(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        List<Inline> parts = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '`' && !isEscaped(text, i)) {
                int close = findCodeClose(text, i + 1);
                if (close > i + 1) {
                    flushPlain(parts, plain);
                    parts.add(new Inline(InlineType.CODE, text.substring(i + 1, close)));
                    i = close + 1;
                    continue;
                }
            }
            if (startsBold(text, i)) {
                int close = findBoldClose(text, i + 2);
                if (close > i + 2) {
                    flushPlain(parts, plain);
                    parts.add(new Inline(InlineType.BOLD, text.substring(i + 2, close)));
                    i = close + 2;
                    continue;
                }
            }
            plain.append(text.charAt(i));
            i++;
        }
        flushPlain(parts, plain);
        return Collections.unmodifiableList(parts);
    }

    /**
     * Returns the rendered text with Markdown markers removed where supported.
     * This is deterministic and safe for local JVM tests.
     */
    static String plainText(String markdown) {
        List<Block> blocks = parse(markdown);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) out.append('\n');
            Block block = blocks.get(i);
            switch (block.type) {
                case HEADING:
                case PARAGRAPH:
                case BLANK:
                    appendInlineText(out, block.text);
                    break;
                case BULLET:
                    out.append("• ");
                    appendInlineText(out, block.text);
                    break;
                case ORDERED:
                    out.append(block.marker).append(' ');
                    appendInlineText(out, block.text);
                    break;
                case DIVIDER:
                    out.append(DIVIDER);
                    break;
                default:
                    throw new AssertionError(block.type);
            }
        }
        return out.toString();
    }

    /** Alias for callers that prefer renderer-oriented terminology. */
    static String renderPlain(String markdown) {
        return plainText(markdown);
    }

    /**
     * Renders release notes for a TextView. Lists use native spans and headings
     * use bold/size spans; unsupported syntax remains ordinary text.
     */
    static SpannableStringBuilder render(String markdown) {
        List<Block> blocks = parse(markdown);
        SpannableStringBuilder out = new SpannableStringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) out.append('\n');
            appendBlock(out, blocks.get(i));
        }
        return out;
    }

    private static Block parseBlock(String line) {
        if (line.isEmpty()) return new Block(BlockType.BLANK, 0, "", "");
        if (line.equals("---")) return new Block(BlockType.DIVIDER, 0, "", "---");

        int headingLevel = headingLevel(line);
        if (headingLevel > 0) {
            return new Block(BlockType.HEADING, headingLevel,
                    contentAfterMarker(line, headingLevel), "");
        }

        if ((line.charAt(0) == '-' || line.charAt(0) == '*')
                && line.length() > 1 && isSpace(line.charAt(1))) {
            return new Block(BlockType.BULLET, 0, contentAfterMarker(line, 1),
                    String.valueOf(line.charAt(0)));
        }

        int dot = orderedMarkerEnd(line);
        if (dot > 0 && dot + 1 < line.length() && isSpace(line.charAt(dot + 1))) {
            return new Block(BlockType.ORDERED, 0, contentAfterMarker(line, dot + 1),
                    line.substring(0, dot + 1));
        }

        return new Block(BlockType.PARAGRAPH, 0, line, "");
    }

    private static int headingLevel(String line) {
        int count = 0;
        while (count < line.length() && count < 4 && line.charAt(count) == '#') count++;
        if (count < 1 || count > 3 || count >= line.length() || !isSpace(line.charAt(count))) {
            return 0;
        }
        return count;
    }

    /** Returns the index of the dot in an ordered marker, or -1 when absent. */
    private static int orderedMarkerEnd(String line) {
        int digits = 0;
        while (digits < line.length() && digits < 10
                && line.charAt(digits) >= '0' && line.charAt(digits) <= '9') {
            digits++;
        }
        return digits > 0 && digits < line.length() && line.charAt(digits) == '.'
                ? digits : -1;
    }

    private static String contentAfterMarker(String line, int markerLength) {
        int start = markerLength;
        while (start < line.length() && isSpace(line.charAt(start))) start++;
        return line.substring(start);
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t';
    }

    private static boolean startsBold(String text, int index) {
        if (index + 1 >= text.length() || text.charAt(index) != '*'
                || text.charAt(index + 1) != '*' || isEscaped(text, index)) {
            return false;
        }
        return index + 2 >= text.length() || text.charAt(index + 2) != '*';
    }

    private static int findBoldClose(String text, int start) {
        for (int i = start; i + 1 < text.length(); i++) {
            if (text.charAt(i) == '*' && text.charAt(i + 1) == '*'
                    && !isEscaped(text, i)
                    && (i == start || text.charAt(i - 1) != '*')
                    && (i + 2 >= text.length() || text.charAt(i + 2) != '*')) {
                return i;
            }
        }
        return -1;
    }

    private static int findCodeClose(String text, int start) {
        for (int i = start; i < text.length(); i++) {
            if (text.charAt(i) == '`' && !isEscaped(text, i)
                    && (i == start || text.charAt(i - 1) != '`')) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isEscaped(String text, int index) {
        int slashes = 0;
        for (int i = index - 1; i >= 0 && text.charAt(i) == '\\'; i--) slashes++;
        return (slashes & 1) != 0;
    }

    private static void flushPlain(List<Inline> parts, StringBuilder plain) {
        if (plain.length() == 0) return;
        parts.add(new Inline(InlineType.TEXT, plain.toString()));
        plain.setLength(0);
    }

    private static void appendInlineText(StringBuilder out, String text) {
        for (Inline part : parseInline(text)) out.append(part.text);
    }

    private static void appendBlock(SpannableStringBuilder out, Block block) {
        int start = out.length();
        switch (block.type) {
            case HEADING:
                appendInline(out, block.text);
                trySpan(out, new StyleSpan(Typeface.BOLD), start, out.length());
                trySpan(out, new RelativeSizeSpan(headingSize(block.level)), start, out.length());
                break;
            case BULLET:
                appendInline(out, block.text);
                trySpan(out, new BulletSpan(12), start, out.length());
                break;
            case ORDERED:
                out.append(block.marker).append(' ');
                appendInline(out, block.text);
                trySpan(out, new LeadingMarginSpan.Standard(28), start, out.length());
                break;
            case DIVIDER:
                out.append(DIVIDER);
                trySpan(out, new StyleSpan(Typeface.BOLD), start, out.length());
                break;
            case PARAGRAPH:
            case BLANK:
                appendInline(out, block.text);
                break;
            default:
                throw new AssertionError(block.type);
        }
    }

    private static float headingSize(int level) {
        if (level == 1) return 1.35f;
        if (level == 2) return 1.2f;
        return 1.1f;
    }

    private static void appendInline(SpannableStringBuilder out, String text) {
        for (Inline part : parseInline(text)) {
            int start = out.length();
            out.append(part.text);
            if (part.type == InlineType.BOLD) {
                trySpan(out, new StyleSpan(Typeface.BOLD), start, out.length());
            } else if (part.type == InlineType.CODE) {
                trySpan(out, new TypefaceSpan("monospace"), start, out.length());
            }
        }
    }

    private static void trySpan(SpannableStringBuilder out, Object span, int start, int end) {
        if (start >= end) return;
        try {
            out.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } catch (RuntimeException ignored) {
            // Android's local JVM stubs throw from setSpan; parsing remains testable without them.
        }
    }

    enum BlockType { HEADING, BULLET, ORDERED, DIVIDER, PARAGRAPH, BLANK }

    static final class Block {
        final BlockType type;
        final int level;
        final String text;
        final String marker;

        private Block(BlockType type, int level, String text, String marker) {
            this.type = type;
            this.level = level;
            this.text = text;
            this.marker = marker;
        }

        BlockType type() { return type; }
        int level() { return level; }
        String text() { return text; }
        String marker() { return marker; }
    }

    enum InlineType { TEXT, BOLD, CODE }

    static final class Inline {
        final InlineType type;
        final String text;

        private Inline(InlineType type, String text) {
            this.type = type;
            this.text = text;
        }

        InlineType type() { return type; }
        String text() { return text; }
    }

}
