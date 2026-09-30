package com.byd.extend;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Guards the exact English/Ukrainian pair used by UiStrings.resourceMatches(). */
public final class UiLocalizationGapResourcesTest {
    private static final Pattern LITERAL_CALL = Pattern.compile(
            "strings\\.(?:text|format)\\s*\\(\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*,\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
            Pattern.DOTALL);

    @Test public void literalUiCallsHaveMatchingEnglishAndUkrainianResources() throws Exception {
        File main = new File("src/main");
        if (!new File(main, "kotlin").isDirectory()) main = new File("app/src/main");
        Map<String, String> english = strings(new File(main, "res/values"));
        Map<String, String> ukrainian = strings(new File(main, "res/values-uk"));
        Map<String, String> chinese = strings(new File(main, "res/values-zh-rCN"));
        Map<String, String> russian = strings(new File(main, "res/values-ru"));

        Set<String> gapNames = new HashSet<>();
        for (String name : english.keySet()) {
            if (name.startsWith("ui_gap_")) gapNames.add(name);
        }
        for (String name : gapNames) {
            assertTrue("Missing Ukrainian " + name, ukrainian.containsKey(name));
            assertTrue("Missing Chinese " + name, chinese.containsKey(name));
            assertTrue("Missing Russian " + name, russian.containsKey(name));
        }

        Set<String> packagedPairs = new HashSet<>();
        for (Map.Entry<String, String> entry : english.entrySet()) {
            String uk = ukrainian.get(entry.getKey());
            if (uk != null) packagedPairs.add(pair(entry.getValue(), uk));
        }
        Set<String> sourcePairs = new HashSet<>();
        try (Stream<java.nio.file.Path> paths = Files.walk(main.toPath())) {
            paths.filter(path -> path.toString().endsWith(".kt") || path.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            String code = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                            Matcher matcher = LITERAL_CALL.matcher(code);
                            while (matcher.find()) {
                                String ukrainianText = unescape(matcher.group(1));
                                String englishText = unescape(matcher.group(2));
                                sourcePairs.add(pair(englishText, ukrainianText));
                            }
                        } catch (Exception error) {
                            throw new IllegalStateException(error);
                        }
                    });
        }
        assertTrue("No literal UiStrings calls found", !sourcePairs.isEmpty());
        for (String sourcePair : sourcePairs) {
            assertTrue("Missing exact English/Ukrainian resource pair: " + sourcePair,
                    packagedPairs.contains(sourcePair));
        }
    }

    private static Map<String, String> strings(File directory) throws Exception {
        Map<String, String> result = new HashMap<>();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".xml"));
        assertNotNull(directory.toString(), files);
        for (File file : files) {
            NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(file).getElementsByTagName("string");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element element = (Element) nodes.item(i);
                if ("false".equals(element.getAttribute("translatable"))) continue;
                String value = element.getTextContent();
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                assertEquals("Duplicate resource " + element.getAttribute("name"), null,
                        result.put(element.getAttribute("name"), unescape(value)));
            }
        }
        return result;
    }

    private static String unescape(String source) {
        StringBuilder result = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '\\' && i + 1 < source.length()) {
                char escaped = source.charAt(++i);
                switch (escaped) {
                    case 'n': result.append('\n'); break;
                    case 'r': result.append('\r'); break;
                    case 't': result.append('\t'); break;
                    default: result.append(escaped); break;
                }
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static String pair(String english, String ukrainian) {
        return english.length() + ":" + english + ukrainian;
    }
}
