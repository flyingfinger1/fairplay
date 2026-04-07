package de.fairplay;

import com.sun.net.httpserver.HttpServer;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the FairPlay resource pack as a ZIP from embedded resources,
 * computes the SHA-1 hash, and serves it via an embedded HTTP server.
 * Clients download the pack automatically on login.
 */
public class ResourcePackServer {

    /** All files to be included in the resource pack ZIP. */
    private static final String[] PACK_FILES = {
        "pack.mcmeta",
        "assets/fairplay/lang/de_de.json",
        "assets/fairplay/lang/en_us.json",
        "assets/fairplay/lang/fr_fr.json",
        "assets/fairplay/lang/es_es.json",
        "assets/fairplay/lang/pt_br.json",
        "assets/fairplay/lang/it_it.json",
        "assets/fairplay/lang/nl_nl.json",
        "assets/fairplay/lang/pl_pl.json",
        "assets/fairplay/lang/ru_ru.json",
        "assets/fairplay/lang/zh_cn.json",
        "assets/fairplay/lang/ja_jp.json",
        "assets/fairplay/lang/ko_kr.json"
    };

    private HttpServer httpServer;
    private byte[] packBytes;
    private String sha1Hex;
    private String url;

    /**
     * Builds the ZIP, saves it to the plugin data folder, then either uses the
     * configured external URL or starts the embedded HTTP server.
     * Must be called before registering the resource pack listener.
     */
    public void start(JavaPlugin plugin) throws IOException {
        packBytes = buildZip(plugin);
        sha1Hex   = computeSha1(packBytes);

        // Always save the ZIP so the server owner can upload it to GitHub Releases
        saveZip(plugin);

        String externalUrl = plugin.getConfig().getString("resource-pack-url", "");
        if (externalUrl != null && !externalUrl.isBlank()) {
            url = externalUrl;
            plugin.getLogger().info("Resource pack (external): " + url + "  (SHA-1: " + sha1Hex + ")");
            return; // No embedded HTTP server needed
        }

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
        httpServer.setExecutor(null); // Default thread pool
        httpServer.start();

        url = "http://" + host + ":" + port + "/fairplay.zip";
        plugin.getLogger().info("Resource pack (embedded): " + url + "  (SHA-1: " + sha1Hex + ")");
    }

    /**
     * Stops the embedded HTTP server if one was started.
     * Safe to call even when an external URL is configured (no-op in that case).
     */
    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    /** Returns the URL clients use to download the resource pack. */
    public String getUrl()     { return url; }

    /** Returns the SHA-1 hex hash of the resource pack ZIP, used for client-side validation. */
    public String getSha1Hex() { return sha1Hex; }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private void saveZip(JavaPlugin plugin) {
        File out = new File(plugin.getDataFolder(), "fairplay-resourcepack.zip");
        try {
            plugin.getDataFolder().mkdirs();
            Files.write(out.toPath(), packBytes);
            plugin.getLogger().info("Resource pack saved to: " + out.getPath());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save resource pack ZIP: " + e.getMessage());
        }
    }

    /**
     * Assembles the resource pack ZIP in memory from the embedded {@code resourcepack/}
     * resources. Logs a warning for any file listed in {@link #PACK_FILES} that is missing.
     *
     * @return the raw ZIP bytes
     * @throws IOException if reading a resource or writing the ZIP fails
     */
    private byte[] buildZip(JavaPlugin plugin) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (String file : PACK_FILES) {
                try (InputStream is = plugin.getResource("resourcepack/" + file)) {
                    if (is == null) {
                        plugin.getLogger().warning("Resource pack: file not found: " + file);
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

    /**
     * Computes the SHA-1 hash of the given data and returns it as a lowercase hex string.
     *
     * @param data the bytes to hash
     * @return 40-character lowercase hex SHA-1 digest
     * @throws RuntimeException if the JVM does not support SHA-1 (should never happen)
     */
    private static String computeSha1(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }
}
