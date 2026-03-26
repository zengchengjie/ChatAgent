package com.chatagent.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 切块策略（Phase 2 minimal RAG）：
 *
 * <ul>
 *   <li>先按空行分段（两次及以上换行分隔）</li>
 *   <li>段落 > 1200 字符：用 windowSize=800、overlap=120 二次切块</li>
 * </ul>
 */
public class KnowledgeChunker {

    public static final int PARAGRAPH_MAX_CHARS = 1200;
    public static final int WINDOW_SIZE = 800;
    public static final int OVERLAP = 120;

    private static final Pattern PARAGRAPH_SPLITTER =
            Pattern.compile("\\r?\\n\\s*\\r?\\n+"); // 空行（含多行空白）作为段落边界

    /** chunk 记录：docTitle、chunkIndex、offset、chunkText。 */
    public record ChunkDraft(String docTitle, int chunkIndex, int offset, String chunkText) {}

    public List<ChunkDraft> chunkDoc(String docTitle, String docText) {
        if (docText == null) {
            return List.of();
        }

        List<ChunkDraft> out = new ArrayList<>();
        int chunkIndex = 0;

        Matcher m = PARAGRAPH_SPLITTER.matcher(docText);
        int prevEnd = 0;
        while (m.find()) {
            int segStart = prevEnd;
            int segEnd = m.start();
            int nextStart = m.end();

            String raw = docText.substring(segStart, segEnd);
            int[] trimmed = trimBounds(raw);
            if (trimmed[0] >= 0) {
                String paragraph = raw.substring(trimmed[0], trimmed[1] + 1);
                int paragraphOffset = segStart + trimmed[0];
                chunkParagraph(out, docTitle, paragraph, paragraphOffset, chunkIndex);
                chunkIndex += countChunksInParagraph(paragraph);
            }

            prevEnd = nextStart;
        }

        // tail
        String raw = docText.substring(prevEnd);
        int[] trimmed = trimBounds(raw);
        if (trimmed[0] >= 0) {
            String paragraph = raw.substring(trimmed[0], trimmed[1] + 1);
            int paragraphOffset = prevEnd + trimmed[0];
            int startingChunkIndex = chunkIndex;
            chunkParagraph(out, docTitle, paragraph, paragraphOffset, chunkIndex);
            // update chunkIndex
            chunkIndex = startingChunkIndex + countChunksInParagraph(paragraph);
        }
        return out;
    }

    private void chunkParagraph(
            List<ChunkDraft> out,
            String docTitle,
            String paragraph,
            int paragraphOffset,
            int startingChunkIndex) {
        if (paragraph == null || paragraph.isEmpty()) {
            return;
        }
        if (paragraph.length() <= PARAGRAPH_MAX_CHARS) {
            out.add(new ChunkDraft(docTitle, startingChunkIndex, paragraphOffset, paragraph));
            return;
        }

        int step = WINDOW_SIZE - OVERLAP; // 680
        int chunkIndex = startingChunkIndex;
        for (int start = 0; start < paragraph.length(); start += step) {
            int end = Math.min(start + WINDOW_SIZE, paragraph.length());
            String chunkText = paragraph.substring(start, end);
            int offset = paragraphOffset + start;
            out.add(new ChunkDraft(docTitle, chunkIndex, offset, chunkText));
            chunkIndex++;
            if (end >= paragraph.length()) {
                break;
            }
        }
    }

    private int countChunksInParagraph(String paragraph) {
        if (paragraph == null || paragraph.isEmpty()) {
            return 0;
        }
        if (paragraph.length() <= PARAGRAPH_MAX_CHARS) {
            return 1;
        }
        int step = WINDOW_SIZE - OVERLAP;
        int count = 0;
        for (int start = 0; start < paragraph.length(); start += step) {
            count++;
            if (start + WINDOW_SIZE >= paragraph.length()) {
                break;
            }
        }
        return count;
    }

    /**
     * @return [start,end] inclusive bounds within {@code s} for non-whitespace characters; if s is all
     *     whitespace return [-1,-1]
     */
    private static int[] trimBounds(String s) {
        int start = 0;
        int end = s.length() - 1;
        while (start <= end && Character.isWhitespace(s.charAt(start))) {
            start++;
        }
        while (end >= start && Character.isWhitespace(s.charAt(end))) {
            end--;
        }
        if (start > end) {
            return new int[] {-1, -1};
        }
        return new int[] {start, end};
    }
}

