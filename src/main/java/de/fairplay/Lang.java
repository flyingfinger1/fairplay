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
 * Lädt Sprachdateien aus resources/lang/ und liefert übersetzte Meldungen
 * basierend auf der Client-Sprache des Spielers (player.locale()).
 *
 * Fallback-Reihenfolge: Spieler-Locale → Sprache ohne Region → de_de
 */
public class Lang {

    private static final String FALLBACK_LANG = "de_de";
    private static final Map<String, Properties> CACHE = new ConcurrentHashMap<>();

    private Lang() {}

    /**
     * Gibt eine übersetzte Meldung als rotes Component zurück.
     * Automatischer Fallback auf de_de wenn Sprache nicht verfügbar.
     */
    public static Component get(Player player, String key) {
        return Component.text(getString(player, key), NamedTextColor.RED);
    }

    /**
     * Gibt die rohe übersetzte Zeichenkette zurück (ohne Component-Wrapper).
     */
    public static String getString(Player player, String key) {
        Locale locale = player.locale();
        String lang = (locale.getLanguage() + "_" + locale.getCountry()).toLowerCase();

        // Versuch 1: exakter Match (z.B. "en_us")
        String value = load(lang).getProperty(key);
        if (value != null) return value;

        // Versuch 2: nur Sprache (z.B. "en" → "en_us" o.ä.)
        String langOnly = locale.getLanguage().toLowerCase();
        if (!langOnly.equals(lang)) {
            value = load(langOnly).getProperty(key);
            if (value != null) return value;
        }

        // Fallback: de_de
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
                // Unbekannte Sprache → leere Properties → Fallback greift
            }
            return props;
        });
    }
}
