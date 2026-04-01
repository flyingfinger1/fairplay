package de.fairplay;

import com.sun.net.httpserver.HttpServer;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Baut das FairPlay-Resource-Pack als ZIP aus den eingebetteten Ressourcen,
 * berechnet den SHA-1-Hash und stellt es über einen eingebetteten HTTP-Server
 * zum Download bereit. Clients laden das Pack automatisch beim Einloggen herunter.
 */
public class ResourcePackServer {

    /** Alle Dateien die ins Resource-Pack-ZIP eingebettet werden. */
    private static final String[] PACK_FILES = {
        "pack.mcmeta",
        "assets/fairplay/lang/de_de.json",
        "assets/fairplay/lang/en_us.json"
    };

    private HttpServer httpServer;
    private byte[] packBytes;
    private String sha1Hex;
    private String url;

    /**
     * Baut das ZIP, startet den HTTP-Server und merkt sich URL + Hash.
     * Muss vor der Listener-Registrierung aufgerufen werden.
     */
    public void start(JavaPlugin plugin) throws IOException {
        packBytes = buildZip(plugin);
        sha1Hex   = computeSha1(packBytes);

        int    port = plugin.getConfig().getInt("resource-pack-port", 8765);
        String host = plugin.getConfig().getString("resource-pack-host", "localhost");

        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/fairplay.zip", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, packBytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(packBytes);
            }
        });
        httpServer.setExecutor(null); // Standard-Thread-Pool
        httpServer.start();

        url = "http://" + host + ":" + port + "/fairplay.zip";
        plugin.getLogger().info("Resource Pack verfügbar: " + url + "  (SHA-1: " + sha1Hex + ")");
    }

    /** Stoppt den HTTP-Server beim Plugin-Shutdown. */
    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    public String getUrl()     { return url; }
    public String getSha1Hex() { return sha1Hex; }

    // ── Interne Hilfsmethoden ────────────────────────────────────────────────

    private byte[] buildZip(JavaPlugin plugin) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (String file : PACK_FILES) {
                try (InputStream is = plugin.getResource("resourcepack/" + file)) {
                    if (is == null) {
                        plugin.getLogger().warning("Resource Pack: Datei nicht gefunden: " + file);
                        continue;
                    }
                    zos.putNextEntry(new ZipEntry(file));
                    is.transferTo(zos);
                    zos.closeEntry();
                }
            }
        }
        return baos.toByteArray();
    }

    private static String computeSha1(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 nicht verfügbar", e);
        }
    }
}
