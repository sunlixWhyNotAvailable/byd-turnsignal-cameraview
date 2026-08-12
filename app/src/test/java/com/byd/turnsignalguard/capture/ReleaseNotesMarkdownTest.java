package com.byd.turnsignalguard.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public final class ReleaseNotesMarkdownTest {
    @Test
    public void parsesSupportedBlocksAndKeepsBlankLines() {
        List<ReleaseNotesMarkdown.Block> blocks = ReleaseNotesMarkdown.parse(
                "# Title\n\n- **fixed** camera\n* raw\n1. first\n---\n### Detail");

        assertEquals(7, blocks.size());
        assertEquals(ReleaseNotesMarkdown.BlockType.HEADING, blocks.get(0).type());
        assertEquals(1, blocks.get(0).level());
        assertEquals("Title", blocks.get(0).text());
        assertEquals(ReleaseNotesMarkdown.BlockType.BLANK, blocks.get(1).type());
        assertEquals(ReleaseNotesMarkdown.BlockType.BULLET, blocks.get(2).type());
        assertEquals("**fixed** camera", blocks.get(2).text());
        assertEquals(ReleaseNotesMarkdown.BlockType.ORDERED, blocks.get(4).type());
        assertEquals("1.", blocks.get(4).marker());
        assertEquals(ReleaseNotesMarkdown.BlockType.DIVIDER, blocks.get(5).type());
        assertEquals(3, blocks.get(6).level());
    }

    @Test
    public void plainRendererSupportsInlineBoldAndCode() {
        assertEquals("Title\n• bold and code\n1. item",
                ReleaseNotesMarkdown.plainText("# Title\n- **bold** and `code`\n1. item"));
    }

    @Test
    public void unsupportedSyntaxAndUnmatchedDelimitersRemainLiteral() {
        String source = "[link](https://example.test) **unclosed `also unclosed";
        assertEquals(source, ReleaseNotesMarkdown.plainText(source));
        assertEquals(source, ReleaseNotesMarkdown.renderPlain(source));
    }

    @Test
    public void normalizesCrLfWithoutAddingBlankBlocks() {
        assertEquals("Title\n• item\ntext",
                ReleaseNotesMarkdown.plainText("# Title\r\n- item\r\ntext"));
    }

    @Test
    public void inlineParserExposesOnlySupportedKinds() {
        List<ReleaseNotesMarkdown.Inline> parts = ReleaseNotesMarkdown.parseInline(
                "a **bold** b `code` c");

        assertEquals(5, parts.size());
        assertEquals(ReleaseNotesMarkdown.InlineType.TEXT, parts.get(0).type());
        assertEquals(ReleaseNotesMarkdown.InlineType.BOLD, parts.get(1).type());
        assertEquals("bold", parts.get(1).text());
        assertEquals(ReleaseNotesMarkdown.InlineType.CODE, parts.get(3).type());
        assertEquals("code", parts.get(3).text());
        assertFalse(parts.get(4).text().isEmpty());
        assertTrue(ReleaseNotesMarkdown.parseInline(null).isEmpty());
    }
}
