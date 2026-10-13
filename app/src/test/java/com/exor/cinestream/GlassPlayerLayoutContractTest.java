package com.exor.cinestream;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import static org.junit.Assert.*;

public class GlassPlayerLayoutContractTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private File resource(String name) {
        File file = new File("src/main/res/" + name);
        return file.exists() ? file : new File("app/src/main/res/" + name);
    }
    private Map<String, Element> ids(String layout) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        NodeList views = factory.newDocumentBuilder().parse(resource("layout/" + layout)).getElementsByTagName("*");
        Map<String, Element> result = new HashMap<>();
        for (int i = 0; i < views.getLength(); i++) {
            Element view = (Element) views.item(i);
            String id = view.getAttributeNS(ANDROID, "id");
            if (!id.isEmpty()) {
                String name = id.substring(id.indexOf('/') + 1);
                assertNull("Duplicate view ID: " + name, result.put(name, view));
            }
        }
        return result;
    }
    @Test public void nativeControlsKeepTheirBindingsAndSeekAmountLabels() throws Exception {
        Map<String, Element> views = ids("view_player_glass_controls.xml");
        for (String id : new String[]{"exo_play_pause", "exo_prev", "exo_next",
                "exo_rew_with_amount", "exo_ffwd_with_amount", "exo_progress", "exo_position",
                "exo_duration", "exo_settings", "top_bar", "btn_back", "btn_rotate", "btn_crop",
                "audio_track", "btn_player_settings", "exo_playback_speed"}) {
            assertTrue(id, views.containsKey(id));
        }
        assertEquals("androidx.media3.ui.DefaultTimeBar", views.get("exo_progress").getTagName());
        assertEquals("true", views.get("exo_progress").getAttributeNS(ANDROID, "clickable"));
        assertEquals("48dp", views.get("exo_progress").getAttributeNS(ANDROID, "layout_height"));
        assertEquals("48dp", views.get("exo_progress").getAttributeNS("http://schemas.android.com/apk/res-auto", "touch_target_height"));
        assertEquals("invisible", views.get("exo_playback_speed").getAttributeNS(ANDROID, "visibility"));
        assertEquals("invisible", views.get("exo_settings").getAttributeNS(ANDROID, "visibility"));
        assertEquals("@style/CinePlayerSettingsButton", views.get("btn_player_settings").getAttribute("style"));
        assertSame(views.get("exo_basic_controls"), views.get("btn_player_settings").getParentNode());
        assertSame(views.get("exo_basic_controls"), views.get("exo_playback_speed").getParentNode());
        assertEquals("48dp", views.get("exo_basic_controls").getAttributeNS(ANDROID, "layout_height"));
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        NodeList styles = factory.newDocumentBuilder().parse(resource("values/player_controls.xml")).getElementsByTagName("style");
        for (int i = 0; i < styles.getLength(); i++) {
            Element style = (Element) styles.item(i);
            if (style.getAttribute("name").equals("CinePlayerSettingsButton")) {
                assertEquals("CinePlayerButton", style.getAttribute("parent"));
            }
            if (style.getAttribute("name").equals("CinePlayerButton")) {
                NodeList items = style.getElementsByTagName("item");
                for (int j = 0; j < items.getLength(); j++) {
                    Element item = (Element) items.item(j);
                    if (item.getAttribute("name").equals("android:layout_height")
                            || item.getAttribute("name").equals("android:layout_width")) {
                        assertEquals("48dp", item.getTextContent());
                    }
                }
            }
        }
    }
    @Test public void separatedGlassKeepsNativeTimelineAndCompactThreshold() throws Exception {
        Map<String, Element> views = ids("view_player_glass_controls.xml");
        assertSame(views.get("player_seek_surface"), views.get("exo_progress").getParentNode());
        assertSame(views.get("player_seek_surface"), views.get("player_seek_glass").getParentNode());
        assertSame(views.get("glass_player_dock"), views.get("player_seek_surface").getParentNode());
        assertSame(views.get("glass_player_dock"), views.get("exo_bottom_bar").getParentNode());
        assertSame(views.get("exo_time"), views.get("exo_position").getParentNode());
        assertSame(views.get("exo_time"), views.get("exo_duration").getParentNode());
        assertEquals("wrap_content", views.get("exo_bottom_bar").getAttributeNS(ANDROID, "layout_height"));
        assertEquals("48dp", views.get("exo_bottom_bar").getAttributeNS(ANDROID, "minHeight"));
        assertEquals("40dp", views.get("player_seek_glass").getAttributeNS(ANDROID, "layout_height"));
        assertEquals("", views.get("glass_player_dock").getAttributeNS(ANDROID, "background"));
        assertEquals("16dp", views.get("glass_player_dock").getAttributeNS(ANDROID, "layout_marginBottom"));
    }
    @Test public void titleIsPlainAndMulticolorIconsAreNotFlattened() throws Exception {
        Map<String, Element> views = ids("view_player_glass_controls.xml");
        assertEquals("TextView", views.get("tv_video_name").getTagName());
        assertEquals("", views.get("tv_video_name").getAttributeNS(ANDROID, "background"));
        for (String id : new String[]{"btn_rotate", "audio_track"}) {
            assertEquals("@null", views.get(id).getAttributeNS("http://schemas.android.com/apk/res-auto", "tint"));
        }
    }
    @Test public void lockLivesInsideTwoEntrySettingsRatherThanOnThePlayerSurface() throws Exception {
        Map<String, Element> views = ids("view_player_glass_controls.xml");
        assertFalse(views.containsKey("btn_screen_lock"));
        assertFalse(views.containsKey("player_lock_row"));
        assertEquals("@style/CinePlayerSettingsButton", views.get("btn_player_settings").getAttribute("style"));
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        NodeList rows = factory.newDocumentBuilder().parse(resource("layout/view_player_settings.xml")).getElementsByTagName("include");
        assertEquals(2, rows.getLength());
        assertEquals("@+id/player_settings_speed", ((Element) rows.item(0)).getAttributeNS(ANDROID, "id"));
        assertEquals("@+id/player_settings_lock", ((Element) rows.item(1)).getAttributeNS(ANDROID, "id"));
    }
    @Test public void settingsOverridesKeepMedia3AdapterIds() throws Exception {
        assertTrue(ids("exo_styled_settings_list.xml").containsKey("exo_settings_listview"));
        Map<String, Element> main = ids("exo_styled_settings_list_item.xml");
        for (String id : new String[]{"exo_icon", "exo_main_text", "exo_sub_text"}) assertTrue(main.containsKey(id));
        assertEquals("com.exor.cinestream.PlayerSettingsRow", main.get("exo_icon").getParentNode().getNodeName());
        Map<String, Element> sub = ids("exo_styled_sub_settings_list_item.xml");
        assertTrue(sub.containsKey("exo_check")); assertTrue(sub.containsKey("exo_text"));
    }
    @Test public void playerRippleUsesAccentInsteadOfWhite() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        for (String name : new String[]{"player_control_ripple", "player_glass_circle_ripple", "player_compact_circle_ripple"}) {
            Element ripple = factory.newDocumentBuilder().parse(resource("drawable/" + name + ".xml")).getDocumentElement();
            assertEquals("@color/player_control_ripple_tint", ripple.getAttributeNS(ANDROID, "color"));
        }
        Element selector = factory.newDocumentBuilder().parse(resource("color/player_control_ripple_tint.xml")).getDocumentElement();
        Element item = (Element) selector.getElementsByTagName("item").item(0);
        assertEquals("@color/glass_text_accent", item.getAttributeNS(ANDROID, "color"));
    }
    @Test public void onlyPlaybackThemeOverridesExplorerPressColor() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        for (String folder : new String[]{"values", "values-night"}) {
            NodeList styles = factory.newDocumentBuilder().parse(resource(folder + "/themes.xml")).getElementsByTagName("style");
            for (int i = 0; i < styles.getLength(); i++) {
                Element style = (Element) styles.item(i);
                String name = style.getAttribute("name");
                if (!name.equals("PlayerFullScreenTheme") && !name.equals("Base.Theme.CineStream")) continue;
                NodeList items = style.getElementsByTagName("item");
                boolean found = false;
                for (int j = 0; j < items.getLength(); j++) {
                    Element item = (Element) items.item(j);
                    if (!item.getAttribute("name").equals("cineGlassPressedColor")) continue;
                    assertEquals(name.equals("PlayerFullScreenTheme") ? "@color/player_control_ripple_tint" : "@color/glass_row_pressed", item.getTextContent());
                    found = true;
                }
                assertTrue(name, found);
            }
        }
    }
    @Test public void darkPlaybackDoesNotChangeApplicationNightMode() throws Exception {
        File source = new File("src/main/java/com/exor/cinestream/VideoPlayerActivity.java");
        if (!source.exists()) source = new File("app/src/main/java/com/exor/cinestream/VideoPlayerActivity.java");
        String text = new String(java.nio.file.Files.readAllBytes(source.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        int attach = text.indexOf("protected void attachBaseContext(Context newBase)");
        int local = text.indexOf("setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES)", attach);
        int delegate = text.indexOf("super.attachBaseContext(newBase)", attach);
        assertTrue(attach >= 0 && local > attach && delegate > local);
        assertFalse(text.contains("setDefaultNightMode("));
    }
    @Test public void feedbackUsesWholeControllerHidingAndAnInvariantUpperLane() throws Exception {
        File javaFolder = new File("src/main/java/com/exor/cinestream");
        if (!javaFolder.exists()) javaFolder = new File("app/src/main/java/com/exor/cinestream");
        String coordinator = new String(java.nio.file.Files.readAllBytes(new File(javaFolder, "PlayerControlsCoordinator.java").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        for (String id : new String[]{"gesture_feedback", "brightness_overlay_container", "overlay_container"}) {
            assertTrue(coordinator.contains("R.id." + id));
        }
        assertTrue(coordinator.contains("if (active && (state.visible || controller.isVisible())) hideNow()"));
        assertTrue(coordinator.contains("!state.canShow()"));
        assertFalse(coordinator.contains("group.setAlpha("));
        assertFalse(coordinator.contains("Rect.intersects("));
        String placement = new String(java.nio.file.Files.readAllBytes(new File(javaFolder, "PlayerFeedbackPlacement.java").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(placement.contains("R.id.top_bar"));
        assertFalse(placement.contains("exo_center_controls"));
        assertTrue(placement.contains("getInsetsIgnoringVisibility("));
    }
    @Test public void adaptiveControlsKeepNativeIdsAndReadableTimeFields() throws Exception {
        Map<String, Element> views = ids("view_player_glass_controls.xml");
        assertEquals("com.exor.cinestream.AdaptivePlayerTransport", views.get("exo_center_controls").getTagName());
        assertEquals("com.exor.cinestream.AdaptivePlayerTimePill", views.get("exo_time").getTagName());
        assertTrue(views.containsKey("player_time_separator"));
        assertEquals("end|center_vertical", views.get("exo_basic_controls").getAttributeNS(ANDROID, "layout_gravity"));
        for (String id : new String[]{"exo_position", "exo_duration"}) {
            assertEquals("", views.get(id).getAttributeNS(ANDROID, "ellipsize"));
            assertEquals("wrap_content", views.get(id).getAttributeNS(ANDROID, "layout_height"));
        }
    }
    @Test public void longInfoDialogReservesItsCloseActionWhileContentScrolls() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Element scroll = (Element) factory.newDocumentBuilder().parse(resource("layout/dialog_glass_info.xml"))
                .getElementsByTagName("ScrollView").item(0);
        assertEquals("0dp", scroll.getAttributeNS(ANDROID, "layout_height"));
        assertEquals("1", scroll.getAttributeNS(ANDROID, "layout_weight"));
        assertTrue(ids("dialog_glass_info.xml").containsKey("dialog_close"));
    }
    @Test public void playPauseMorphsHaveMatchingPathCommands() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        for (String mode : new String[]{"play", "pause"}) for (int i = 0; i < 2; i++) {
            Element animator = factory.newDocumentBuilder().parse(resource("animator/player_morph_" + mode + "_" + i + ".xml"))
                    .getDocumentElement();
            String from = animator.getAttributeNS(ANDROID, "valueFrom").replaceAll("[^a-zA-Z]", "");
            String to = animator.getAttributeNS(ANDROID, "valueTo").replaceAll("[^a-zA-Z]", "");
            assertEquals(from, to);
            assertEquals("200", animator.getAttributeNS(ANDROID, "duration"));
        }
    }
}
