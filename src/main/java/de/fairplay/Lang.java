package de.fairplay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads language files from resources/lang/ and provides translated messages
 * based on the player's client language (player.locale()).
 *
 * Fallback order: player locale → language only → en_us
 */
public class Lang {

    private static final String FALLBACK_LANG = "en_us";
    private static final Map<String, Properties> CACHE = new ConcurrentHashMap<>();

    private Lang() {}

    /**
     * Returns a translated message as a red Component.
     * Automatically falls back to en_us if the language is not available.
     */
    public static Component get(Player player, String key) {
        return Component.text(getString(player, key), NamedTextColor.RED);
    }

    /**
     * Returns the raw translated string (without a Component wrapper).
     */
    public static String getString(Player player, String key) {
        Locale locale = player.locale();
        String lang = (locale.getLanguage() + "_" + locale.getCountry()).toLowerCase();

        // Attempt 1: exact match (e.g. "en_us")
        String value = load(lang).getProperty(key);
        if (value != null) return value;

        // Attempt 2: language only (e.g. "en" → "en_us")
        String langOnly = locale.getLanguage().toLowerCase();
        if (!langOnly.equals(lang)) {
            value = load(langOnly).getProperty(key);
            if (value != null) return value;
        }

        // Fallback: en_us
        return load(FALLBACK_LANG).getProperty(key, key);
    }

    private static Properties load(String lang) {
        return CACHE.computeIfAbsent(lang, l -> {
            Properties props = new Properties();
            String path = "/lang/" + l + ".properties";
            try (InputStream is = Lang.class.getResourceAsStream(path)) {
                if (is != null) {
                    props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                // Unknown language → empty Properties → fallback applies
            }
            return props;
        });
    }
}
