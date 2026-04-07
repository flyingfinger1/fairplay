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
        "assets/fairplay/lang/en_us.json"
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

    /** Stops the HTTP server on plugin shutdown. */
    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    public String getUrl()     { return url; }
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

    private static String computeSha1(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }
}
