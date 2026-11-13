package io.github.igorcv88.oneuiliquidglass.i18n;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.SettingsRegistry;
import io.github.igorcv88.oneuiliquidglass.config.SettingSpec;
import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** English (default) and Brazilian Portuguese stay complete and consistent, string by string. */
public class LocalizationParityTest {
    private static final File RES = new File("src/main/res");
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+\\$)?[sd]");

    private static Map<String, String> strings(String folder, String file) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        NodeList list = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new File(RES, folder + "/" + file)).getElementsByTagName("string");
        for (int i = 0; i < list.getLength(); i++) {
            Element e = (Element) list.item(i);
            out.put(e.getAttribute("name"), e.getTextContent());
        }
        return out;
    }

    private static List<String> placeholders(String s) {
        List<String> found = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(s);
        while (m.find()) found.add(m.group());
        Collections.sort(found);
        return found;
    }

    @Test
    public void everyResourceFileHasTheSameNamesAndPlaceholdersInBothLanguages() throws Exception {
        for (String file : new String[] {"strings.xml", "control_titles.xml", "control_descriptions.xml",
                "control_options.xml", "control_reasons.xml"}) {
            Map<String, String> en = strings("values", file), pt = strings("values-pt-rBR", file);
            assertEquals(file + " names", en.keySet(), pt.keySet());
            for (Map.Entry<String, String> e : en.entrySet()) {
                assertFalse(file + " " + e.getKey() + " is empty in English", e.getValue().trim().isEmpty());
                assertFalse(file + " " + e.getKey() + " is empty in Portuguese", pt.get(e.getKey()).trim().isEmpty());
                assertEquals(file + " " + e.getKey(), placeholders(e.getValue()), placeholders(pt.get(e.getKey())));
            }
        }
    }

    @Test
    public void theMaterialStringsAreTranslatedNotCopied() throws Exception {
        Map<String, String> en = strings("values", "strings.xml"), pt = strings("values-pt-rBR", "strings.xml");
        int checked = 0;
        for (String name : en.keySet()) {
            if (!name.startsWith("material_") && !name.startsWith("search_material_")
                    && !name.equals("control_caution_contrast")) continue;
            checked++;
            assertNotEquals(name + " is not translated", en.get(name), pt.get(name));
            assertFalse(name + " keeps English wording in Portuguese: " + pt.get(name),
                    pt.get(name).contains("Clear (iOS style)") || pt.get(name).contains("Frosted"));
        }
        assertTrue("material strings exist", checked >= 25);
        assertEquals("Glass material", en.get("material_title"));
        assertEquals("Material do vidro", pt.get("material_title"));
        assertEquals("Frosted", en.get("material_frosted"));
        assertEquals("Fosco", pt.get("material_frosted"));
        assertEquals("Clear (iOS style)", en.get("material_clear"));
        assertEquals("Transparente (estilo iOS)", pt.get("material_clear"));
        assertEquals("Choose how pop-ups and lock-screen cards look.", en.get("material_subtitle"));
        assertEquals("Escolha o visual dos pop-ups e cartões da tela de bloqueio.", pt.get("material_subtitle"));
    }

    @Test
    public void everyClearControlHasASpecificDescriptionInBothLanguagesWithoutTheOldPlaceholder() throws Exception {
        Map<String, String> en = strings("values", "control_descriptions.xml"), pt = strings("values-pt-rBR", "control_descriptions.xml");
        Set<String> seen = new HashSet<>();
        for (SettingSpec s : SettingsRegistry.all()) {
            if (!(s.key.startsWith("sf.hc") || s.key.startsWith("sf.kc") || s.key.equals("sf.huclear") || s.key.equals("sf.kgclear")))
                continue;
            String name = "control_description_" + s.key.replace('.', '_');
            assertNotNull(s.key, en.get(name));
            for (String text : new String[] {en.get(name), pt.get(name)}) {
                assertFalse(s.key + ": " + text, text.contains("Experimental") || text.contains("experimental"));
                assertFalse(s.key + ": " + text, text.contains("not yet a control") || text.contains("ainda sem controle"));
                assertTrue(s.key + " says where it acts: " + text, text.matches("(?s).*(pop-up|pop-ups|lock|bloqueio|Home|Início).*"));
            }
            assertTrue("unique " + s.key, seen.add(en.get(name)));
            assertNotEquals(s.key, en.get(name), pt.get(name));
        }
        assertEquals(31, seen.size());
    }

    @Test
    public void everyControlWithAnEditorHasATitleResource() throws Exception {
        Map<String, String> en = strings("values", "control_titles.xml");
        for (Controls.Control c : Controls.all()) {
            if (!Controls.editable(c.key)) continue;
            String hash = null;
            for (Map.Entry<String, String> e : en.entrySet()) {
                // Resource names are a hash of the Portuguese title: look the title up by its hash.
                if (e.getKey().equals("control_title_" + sha1(c.title).substring(0, 12))) hash = e.getKey();
            }
            assertNotNull(c.key + " (" + c.title + ") has no English title", hash);
        }
    }

    private static String sha1(String s) throws Exception {
        java.security.MessageDigest d = java.security.MessageDigest.getInstance("SHA-1");
        StringBuilder b = new StringBuilder();
        for (byte x : d.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))) b.append(String.format("%02x", x));
        return b.toString();
    }
}
