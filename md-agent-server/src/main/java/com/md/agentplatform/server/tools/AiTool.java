package com.md.agentplatform.server.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本清洗工具（裁剪自原 AiTool，仅保留检索所需的 cleanText 及私有辅助）。
 */
public class AiTool {

    private AiTool() {
    }

    /**
     * 文档切片文本清洗。
     */
    public static String cleanText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String cleanedDoc = text;
        cleanedDoc = cleanedDoc.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F-\\x9F]", "");

        // 保护代码块：先占位，避免清洗规则破坏代码内容
        List<String> codeBlocks = new ArrayList<>();
        java.util.regex.Matcher codeMatcher = java.util.regex.Pattern.compile("(?s)```(.*?)```").matcher(cleanedDoc);
        StringBuffer sb = new StringBuffer();
        while (codeMatcher.find()) {
            String placeholder = "%%CODE_BLOCK_" + codeBlocks.size() + "%%";
            codeBlocks.add(codeMatcher.group(1).trim());
            codeMatcher.appendReplacement(sb, placeholder);
        }
        codeMatcher.appendTail(sb);
        cleanedDoc = sb.toString();

        cleanedDoc = removeHtmlMark(cleanedDoc);
        cleanedDoc = removeMarkdownMark(cleanedDoc);
        cleanedDoc = removeUselessSymbols(cleanedDoc);
        cleanedDoc = standardizeWhitespaceForNonCode(cleanedDoc);

        for (int i = 0; i < codeBlocks.size(); i++) {
            String code = codeBlocks.get(i);
            code = code.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F-\\x9F]", "");
            code = code.replaceAll("[\n\r]{3,}", "\n\n");
            cleanedDoc = cleanedDoc.replace("%%CODE_BLOCK_" + i + "%%", "\n" + code + "\n");
        }

        return cleanedDoc;
    }

    private static String removeHtmlMark(String doc) {
        return doc.replaceAll("<[^>]+>", "")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">");
    }

    private static String removeMarkdownMark(String doc) {
        String mdCleaned = doc;
        mdCleaned = mdCleaned.replaceAll("(?s)```.*?```", "");
        mdCleaned = mdCleaned.replaceAll("`", "");
        mdCleaned = mdCleaned.replaceAll("(?m)^#{1,6}\\s*", "");
        mdCleaned = mdCleaned.replaceAll("(\\*{1,2})(.*?)\\1", "$2");
        mdCleaned = mdCleaned.replaceAll("(_{1,2})(.*?)\\1", "$2");
        mdCleaned = mdCleaned.replaceAll("\\[(.*?)\\]\\([^)]*\\)", "$1");
        return mdCleaned;
    }

    private static String removeUselessSymbols(String doc) {
        return doc.replaceAll(
                "[^a-zA-Z0-9\\u4e00-\\u9fa5" +
                        "\\u3002\\uff0c\\uff01\\uff1f\\uff1b\\uff1a\\u201c\\u201d\\u2018\\u2019\\uff08\\uff09\\u3001\\u2014\\u2026" +
                        ".,!?;:\"'()\\[\\]<>{}" +
                        "\\-/%+=@#_" +
                        "\\s]",
                "");
    }

    private static String standardizeWhitespaceForNonCode(String doc) {
        doc = doc.replaceAll("[\n\r]+", "\n");
        doc = doc.replaceAll("[\\s　]+", " ");
        return doc.trim();
    }
}
