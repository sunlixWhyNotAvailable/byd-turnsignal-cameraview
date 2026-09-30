package com.byd.extend;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

/** Missing translations and broken format arguments affect real runtime messages. */
public final class RussianResourcesTest {
    @Test public void russianCoversPackagedMessagesAndRetainsFormatArguments() throws Exception {
        Map<String, String> base = strings(new File("src/main/res/values"));
        Map<String, String> russian = strings(new File("src/main/res/values-ru"));
        for (Map.Entry<String, String> entry : base.entrySet()) {
            String translated = russian.get(entry.getKey());
            assertNotNull("Missing Russian: " + entry.getKey(), translated);
            assertEquals(entry.getKey(), formats(entry.getValue()), formats(translated));
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
                assertNull("Duplicate resource " + element.getAttribute("name"),
                        result.put(element.getAttribute("name"), element.getTextContent()));
            }
        }
        return result;
    }

    private static List<String> formats(String value) {
        Matcher matcher = Pattern.compile("%(?:[0-9]+\\$)?[a-zA-Z]").matcher(value);
        List<String> result = new ArrayList<>();
        while (matcher.find()) result.add(matcher.group());
        java.util.Collections.sort(result);
        return result;
    }
}
