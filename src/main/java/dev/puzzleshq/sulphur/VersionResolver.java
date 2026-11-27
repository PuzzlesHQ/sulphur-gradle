package dev.puzzleshq.sulphur;

import com.google.gson.Gson;
import dev.puzzleshq.lunar.ConfigPair;
import dev.puzzleshq.sulphur.minecraft.manifest.version.client.VersionData;
import dev.puzzleshq.sulphur.config.MinecraftConfig;
import dev.puzzleshq.sulphur.config.SulpherConfig;
import dev.puzzleshq.sulphur.minecraft.manifest.version.Version;
import dev.puzzleshq.sulphur.minecraft.manifest.version.VersionManifest;
import dev.puzzleshq.sulphur.util.FileUtil;
import dev.puzzleshq.sulphur.util.JavaUtils;
import org.gradle.api.Project;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.Paths;

public class VersionResolver {

    public static final int SECOND = 1000;
    public static final int MINUTE = SECOND * 60;
    public static final int HOUR = MINUTE * 60;

    public static final ThreadLocal<Gson> GSON_LOCAL = ThreadLocal.withInitial(Gson::new);

    private static String getSafeFilename(URL url) {
        String path = url.getPath();
        if (path == null || path.isEmpty() || path.equals("/")) {
            return "manifest.json";
        }
        Path fileName = Paths.get(path).getFileName();
        return fileName != null ? fileName.toString() : "manifest.json";
    }

    private static void writeAtomically(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tempFile = new File(parent, file.getName() + ".tmp");

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(tempFile), StandardCharsets.UTF_8)) {
            writer.write(content);
        }

        try {
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static VersionManifest getManifest(Project project, ConfigPair<SulpherConfig> pair) {
        MinecraftConfig config = pair.getConfig().minecraft;
        String manifestSource = config.manifestSource.get();

        Gson gson = GSON_LOCAL.get();

        try {
            URL url = URI.create(manifestSource).toURL();
            String safeName = getSafeFilename(url);
            File cacheDir = FileUtil.getSlimeCache(project);
            File fileEpoc = new File(cacheDir, safeName + ".epoc");
            File file = new File(cacheDir, safeName);

            long current = System.currentTimeMillis();
            boolean needsUpdate = !file.exists() || !fileEpoc.exists();

            if (!needsUpdate) {
                try {
                    // Java 8 compatible byte-array string construction
                    String epochStr = new String(Files.readAllBytes(fileEpoc.toPath()), StandardCharsets.UTF_8).trim();
                    long lastCheck = Long.parseLong(epochStr);
                    if ((current - lastCheck) >= VersionResolver.HOUR) {
                        needsUpdate = true;
                    }
                } catch (Exception e) {
                    needsUpdate = true;
                }
            }

            if (needsUpdate) {
                writeAtomically(fileEpoc, String.valueOf(current));

                String manifestData;
                try (InputStream stream = url.openStream()) {
                    manifestData = new String(JavaUtils.readAllBytes(stream), StandardCharsets.UTF_8);
                }

                pair.getSpec().save();
                writeAtomically(file, manifestData);

                return gson.fromJson(manifestData, VersionManifest.class);
            } else {
                String manifestData;
                try (InputStream stream = Files.newInputStream(file.toPath())) {
                    manifestData = new String(JavaUtils.readAllBytes(stream), StandardCharsets.UTF_8);
                }
                return gson.fromJson(manifestData, VersionManifest.class);
            }
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid url '" + manifestSource + "' passed as manifest source!", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to resolve or cache version manifest", e);
        }
    }

    public static VersionData getClientJson(Project project, Version version) {
        Gson gson = GSON_LOCAL.get();
        try {
            String safeName = getSafeFilename(version.url);
            File file = new File(FileUtil.getMinecraftManifests(project), safeName);

            if (!file.exists()) {
                String manifestData;
                try (InputStream stream = version.url.openStream()) {
                    manifestData = new String(JavaUtils.readAllBytes(stream), StandardCharsets.UTF_8);
                }
                writeAtomically(file, manifestData);
                return gson.fromJson(manifestData, VersionData.class);
            } else {
                String manifestData;
                try (InputStream stream = Files.newInputStream(file.toPath())) {
                    manifestData = new String(JavaUtils.readAllBytes(stream), StandardCharsets.UTF_8);
                }
                return gson.fromJson(manifestData, VersionData.class);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to retrieve or parse client JSON for version", e);
        }
    }

    public static void deleteManifestFiles(Project project) {
        MinecraftConfig config = SulpherConfig.get(project).getConfig().minecraft;
        String manifestSource = config.manifestSource.get();

        try {
            URL url = URI.create(manifestSource).toURL();
            String safeName = getSafeFilename(url);

            File fileEpoc = new File(FileUtil.getSlimeCache(project), safeName + ".epoc");
            File file = new File(FileUtil.getSlimeCache(project), safeName);

            if (file.exists()) file.delete();
            if (fileEpoc.exists()) fileEpoc.delete();

            FileUtil.delete(FileUtil.getMinecraftManifests(project));
            FileUtil.delete(FileUtil.getPuzzleManifests(project));
        } catch (MalformedURLException e) {
            throw new RuntimeException("Invalid manifest source URL during deletion", e);
        }
    }
}