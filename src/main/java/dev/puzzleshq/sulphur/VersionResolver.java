package dev.puzzleshq.sulphur;

import com.google.gson.Gson;
import dev.puzzleshq.lunar.ConfigPair;
import dev.puzzleshq.sulphur.config.MinecraftConfig;
import dev.puzzleshq.sulphur.config.SulpherConfig;
import dev.puzzleshq.sulphur.minecraft.manifest.version.Version;
import dev.puzzleshq.sulphur.minecraft.manifest.version.VersionManifest;
import dev.puzzleshq.sulphur.minecraft.manifest.version.client.VersionData;
import dev.puzzleshq.sulphur.util.FileUtil;
import dev.puzzleshq.sulphur.util.JavaUtils;
import org.gradle.api.Project;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class VersionResolver {

    public static final int SECOND = 1000;
    public static final int MINUTE = SECOND * 60;
    public static final int HOUR = MINUTE * 60;

    private static final int CONNECTION_TIMEOUT = 15 * SECOND;
    private static final int READ_TIMEOUT = 30 * SECOND;

    public static final ThreadLocal<Gson> GSON_LOCAL =
            ThreadLocal.withInitial(Gson::new);

    private static String getSafeFilename(URL url) {
        String path = url.getPath();

        String filename = "manifest.json";

        if (path != null && !path.isEmpty() && !path.equals("/")) {
            int separator = path.lastIndexOf('/');

            String name = path.substring(separator + 1);

            if (!name.isEmpty()) {
                filename = name;
            }
        }

        filename = filename.replaceAll("[^a-zA-Z0-9._-]", "_");

        return filename + "-" + sha256(url.toExternalForm());
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(
                    input.getBytes(StandardCharsets.UTF_8)
            );

            StringBuilder result = new StringBuilder();

            for (byte value : hash) {
                result.append(String.format("%02x", value & 0xff));
            }

            return result.toString();

        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is not available.",
                    exception
            );
        }
    }

    private static void writeAtomically(File file, String content)
            throws IOException {

        Path destination = file.toPath().toAbsolutePath();
        Path parent = destination.getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        String prefix = destination.getFileName().toString();

        while (prefix.length() < 3) {
            prefix += "_";
        }

        Path temporary = Files.createTempFile(
                parent,
                prefix,
                ".tmp"
        );

        try {
            Files.write(
                    temporary,
                    content.getBytes(StandardCharsets.UTF_8)
            );

            try {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );

            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static URLConnection openConnection(URL url)
            throws IOException {

        URLConnection connection = url.openConnection();

        connection.setConnectTimeout(CONNECTION_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);

        return connection;
    }

    private static <T> T parseJson(String json, Class<T> type)
            throws IOException {

        try {
            T result = GSON_LOCAL.get().fromJson(json, type);

            if (result == null) {
                throw new IOException("JSON contained no valid object.");
            }

            return result;

        } catch (RuntimeException exception) {
            throw new IOException("Failed to parse JSON.", exception);
        }
    }

    private static <T> T readCachedJson(File file, Class<T> type) {
        if (!file.isFile()) {
            return null;
        }

        try {
            String json = new String(
                    Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8
            );

            return parseJson(json, type);

        } catch (IOException exception) {
            return null;
        }
    }

    private static <T> T downloadJson(
            URL url,
            File cacheFile,
            Class<T> type
    ) throws IOException {

        String json;

        URLConnection connection = openConnection(url);

        try (InputStream stream = connection.getInputStream()) {
            json = new String(
                    JavaUtils.readAllBytes(stream),
                    StandardCharsets.UTF_8
            );
        }

        T result = parseJson(json, type);

        writeAtomically(cacheFile, json);

        return result;
    }

    private static boolean isCacheFresh(
            File manifestFile,
            File timestampFile
    ) {

        if (!manifestFile.isFile() || !timestampFile.isFile()) {
            return false;
        }

        try {
            String timestamp = new String(
                    Files.readAllBytes(timestampFile.toPath()),
                    StandardCharsets.UTF_8
            ).trim();

            long lastCheck = Long.parseLong(timestamp);
            long current = System.currentTimeMillis();

            return lastCheck <= current
                    && current - lastCheck < HOUR;

        } catch (IOException | NumberFormatException exception) {
            return false;
        }
    }

    public static VersionManifest getManifest(
            Project project,
            ConfigPair<SulpherConfig> pair
    ) {

        MinecraftConfig config = pair.getConfig().minecraft;
        String manifestSource = config.manifestSource.get();

        try {
            URL url = URI.create(manifestSource).toURL();

            String safeName = getSafeFilename(url);

            File cacheDirectory = FileUtil.getSlimeCache(project);

            File manifestFile = new File(cacheDirectory, safeName);
            File timestampFile = new File(
                    cacheDirectory,
                    safeName + ".epoc"
            );

            if (isCacheFresh(manifestFile, timestampFile)) {
                VersionManifest cached = readCachedJson(
                        manifestFile,
                        VersionManifest.class
                );

                if (cached != null) {
                    return cached;
                }
            }

            VersionManifest manifest = downloadJson(
                    url,
                    manifestFile,
                    VersionManifest.class
            );

            pair.getSpec().save();

            writeAtomically(
                    timestampFile,
                    String.valueOf(System.currentTimeMillis())
            );

            return manifest;

        } catch (MalformedURLException | IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Invalid URL '" + manifestSource
                            + "' passed as manifest source!",
                    exception
            );

        } catch (IOException exception) {
            throw new RuntimeException(
                    "Failed to resolve or cache Minecraft version manifest.",
                    exception
            );
        }
    }

    public static VersionData getClientJson(
            Project project,
            Version version
    ) {

        try {
            URL url = version.url;

            String safeName = getSafeFilename(url);

            File cacheFile = new File(
                    FileUtil.getMinecraftManifests(project),
                    safeName
            );

            VersionData cached = readCachedJson(
                    cacheFile,
                    VersionData.class
            );

            if (cached != null) {
                return cached;
            }

            return downloadJson(
                    url,
                    cacheFile,
                    VersionData.class
            );

        } catch (IOException exception) {
            throw new RuntimeException(
                    "Failed to retrieve or parse Minecraft client JSON.",
                    exception
            );
        }
    }

    public static void deleteManifestFiles(Project project) {
        MinecraftConfig config = SulpherConfig.get(project)
                .getConfig().minecraft;

        String manifestSource = config.manifestSource.get();

        try {
            URL url = URI.create(manifestSource).toURL();

            String safeName = getSafeFilename(url);

            File cacheDirectory = FileUtil.getSlimeCache(project);

            File manifestFile = new File(cacheDirectory, safeName);
            File timestampFile = new File(
                    cacheDirectory,
                    safeName + ".epoc"
            );

            Files.deleteIfExists(manifestFile.toPath());
            Files.deleteIfExists(timestampFile.toPath());

            FileUtil.delete(FileUtil.getMinecraftManifests(project));
            FileUtil.delete(FileUtil.getPuzzleManifests(project));

        } catch (MalformedURLException | IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Invalid manifest source URL during deletion.",
                    exception
            );

        } catch (IOException exception) {
            throw new RuntimeException(
                    "Failed to delete cached manifest files.",
                    exception
            );
        }
    }
}