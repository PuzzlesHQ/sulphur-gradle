package dev.puzzleshq.sulphur.minecraft;

import dev.puzzleshq.gibberishdefiner.JarRemapper;
import dev.puzzleshq.gibberishdefiner.mapping.IMapping;
import dev.puzzleshq.lunar.ConfigPair;
import dev.puzzleshq.sulphur.VersionResolver;
import dev.puzzleshq.sulphur.config.MinecraftConfig;
import dev.puzzleshq.sulphur.config.SulpherConfig;
import dev.puzzleshq.sulphur.minecraft.manifest.assets.Asset;
import dev.puzzleshq.sulphur.minecraft.manifest.assets.AssetsManifest;
import dev.puzzleshq.sulphur.minecraft.manifest.version.Version;
import dev.puzzleshq.sulphur.minecraft.manifest.version.VersionManifest;
import dev.puzzleshq.sulphur.minecraft.manifest.version.client.VersionData;
import dev.puzzleshq.sulphur.minecraft.manifest.version.client.libraries.Artifact;
import dev.puzzleshq.sulphur.minecraft.manifest.version.client.libraries.Library;
import dev.puzzleshq.sulphur.transform.LocalMaven;
import dev.puzzleshq.sulphur.transform.SulphurConfigurations;
import dev.puzzleshq.sulphur.util.*;
import org.gradle.api.Project;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class MinecraftSetup {

    public static final String assetsUrl =
            "https://resources.download.minecraft.net/";

    private static final ExecutorService assetDownloadService =
            Executors.newFixedThreadPool(4);

    public static void loadMinecraft(
            Project project,
            DependencyHandler handler,
            ConfigPair<SulpherConfig> pair
    ) {
        SulpherConfig config = pair.getConfig();
        MinecraftConfig minecraft = config.minecraft;

        VersionManifest manifest = VersionResolver.getManifest(project, pair);
        Version version = manifest.find(minecraft.minecraftVersion.get());
        VersionData client = version.get(project);

        System.out.println("Found '" + manifest.versions.length + "' minecraft versions!");
        System.out.println("Found version '" + version.type + " " + version.id + "'");

        List<Library> libraries = loadLibrariesForVersion(project, handler, client);
        boolean remapping = !"none".equals(minecraft.mappingsSource.get());

        downloadJars(project, handler, client, !remapping);

        if (!remapping) {
            libraries.forEach(library ->
                    handler.add(SulphurConfigurations.nonTransitiveInclude, library.name)
            );

            registerUnmappedDependencies(project, handler);
            return;
        }

        remapJars(project, handler, client, minecraft);

        for (Library library : libraries) {
            handler.add(SulphurConfigurations.internalRemapConfigurationName, library.name);

            File remapped = LocalMaven.getArtifactFile(
                    project,
                    LocalMaven.transformName(library.name)
            );

            if (remapped.isFile()) {
                handler.add(
                        SulphurConfigurations.nonTransitiveInclude,
                        project.files(remapped)
                );
            }
        }

        registerRemappedDependencies(project, handler);
    }

    private static void registerUnmappedDependencies(
            Project project,
            DependencyHandler handler
    ) {
        project.afterEvaluate(p -> {
            for (ResolvedArtifactResult artifact :
                    SulphurConfigurations.apiRemapConfiguration
                            .getIncoming()
                            .getArtifacts()) {

                handler.add(
                        SulphurConfigurations.nonTransitiveInclude,
                        artifact.getFile()
                );
            }
        });
    }

    private static void registerRemappedDependencies(
            Project project,
            DependencyHandler handler
    ) {
        project.afterEvaluate(p -> {
            for (ResolvedArtifactResult artifact :
                    SulphurConfigurations.apiRemapConfiguration
                            .getIncoming()
                            .getArtifacts()) {

                File input = artifact.getFile();
                String filename = input.getName();

                int extension = filename.lastIndexOf('.');
                String artifactName = extension == -1
                        ? filename
                        : filename.substring(0, extension);

                File directory = LocalMaven.getArtifactDir(
                        project,
                        LocalMaven.transformName(
                                "files:" + artifactName + ":0.0.0"
                        )
                );

                File remapped = new File(
                        directory,
                        artifactName + "-remapped.jar"
                );

                if (remapped.isFile()) {
                    handler.add(
                            SulphurConfigurations.nonTransitiveInclude,
                            project.files(remapped)
                    );
                }
            }
        });
    }

    public static void remapJars(
            Project project,
            DependencyHandler dependencies,
            VersionData versionData,
            MinecraftConfig mcCfg
    ) {
        File clientFile = new File(
                FileUtil.getVersionFolder(project, versionData.id),
                "minecraft-" + versionData.id + "-client.jar"
        );

        File dir = LocalMaven.getArtifactDir(
                project,
                "puzzle.remap.com.mojang:minecraft:" + versionData.id
        );

        if (!dir.exists()) {
            dir.mkdirs();
        }

        File remappedInfo = new File(
                dir,
                "minecraft-" + versionData.id + "-client.info"
        );

        File remappedClientFile = new File(
                dir,
                "minecraft-" + versionData.id + "-client.jar"
        );

        boolean doRemap;

        try {
            doRemap = !remappedInfo.exists()
                    || !remappedClientFile.exists()
                    || !new String(
                    Files.readAllBytes(remappedInfo.toPath()),
                    StandardCharsets.UTF_8
            ).equals(mcCfg.mappingsSource.get());

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (doRemap) {
            System.out.println(
                    "Remapping 'Minecraft " + versionData.id +
                            "' using '" + mcCfg.mappingsSource.get() + "' mappings"
            );

            try {
                Files.write(
                        remappedInfo.toPath(),
                        mcCfg.mappingsSource.get().getBytes(StandardCharsets.UTF_8)
                );
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            IMapping mapping = Mappings.getMappings(project, versionData, mcCfg);

            JarRemapper.applyBareMapping(
                    clientFile,
                    remappedClientFile,
                    mapping,
                    false
            );
        }

        dependencies.add("implementation", project.files(remappedClientFile));
    }

    public static void downloadJars(
            Project project,
            DependencyHandler dependencies,
            VersionData version,
            boolean addDependency
    ) {
        File clientJar = new File(
                FileUtil.getVersionFolder(project, version.id),
                "minecraft-" + version.id + "-client.jar"
        );

        if (!clientJar.isFile() && version.downloads.client != null) {
            try {
                File parent = clientJar.getParentFile();

                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Could not create directory: " + parent);
                }

                try (InputStream input = version.downloads.client.url.openStream()) {
                    Files.copy(input, clientJar.toPath());
                }

            } catch (IOException e) {
                throw new RuntimeException(
                        "Failed to download Minecraft " + version.id,
                        e
                );
            }
        }

        if (addDependency && clientJar.isFile()) {
            dependencies.add(
                    "implementation",
                    "com.mojang:minecraft:" + version.id + ":client"
            );
        }
    }

    private static void applyManifestVersionConstraints(
            Project project,
            Library[] libraries
    ) {
        Map<String, String> requiredVersions =
                getRequiredVersionsMap(libraries);

        project.getConfigurations().all(configuration ->
                configuration.getResolutionStrategy().eachDependency(details -> {
                    String requestedKey =
                            details.getRequested().getGroup() + ":" +
                                    details.getRequested().getName();

                    if (requiredVersions.containsKey(requestedKey)) {
                        String forcedVersion = requiredVersions.get(requestedKey);

                        details.useVersion(forcedVersion);
                        details.because(
                                "Locked automatically to match Minecraft version requirements."
                        );
                    }
                })
        );
    }

    private static @NotNull Map<String, String> getRequiredVersionsMap(
            Library[] libraries
    ) {
        Map<String, String> requiredVersions = new HashMap<>();

        for (Library lib : libraries) {
            String[] parts = lib.name.split("[:@]");

            if (parts.length >= 3) {
                String group = parts[0];
                String name = parts[1];
                String version = parts[2];

                requiredVersions.put(group + ":" + name, version);
            }
        }

        return requiredVersions;
    }

    public static List<Library> loadLibrariesForVersion(
            Project project,
            DependencyHandler dependencies,
            VersionData client
    ) {
        Library[] libraries = client.getValidLibraries();

        System.out.println(
                "Found '" + libraries.length + "/" + client.libraries.length +
                        "' valid libraries for this OS '" + OSUtil.osId +
                        "' (" + OSUtil.ARCH_BITS + "-bit)"
        );

        applyManifestVersionConstraints(project, libraries);

        File nativeDir = FileUtil.getVersionNativesFolder(project, client.id);
        File libraryDir = FileUtil.getVersionLibsFolder(project, client.id);

        File nativeMarker = new File(nativeDir, ".native-architecture");

        String architecture = OSUtil.osId + "-" +
                System.getProperty("os.arch").toLowerCase(Locale.ROOT);

        boolean extractNatives = true;

        try {
            if (nativeMarker.isFile()) {
                String previousArchitecture = new String(
                        Files.readAllBytes(nativeMarker.toPath()),
                        StandardCharsets.UTF_8
                );

                extractNatives = !architecture.equals(previousArchitecture);
            }

            if (extractNatives && nativeDir.exists()) {
                clearDirectory(nativeDir);
            }

            if (!nativeDir.isDirectory() && !nativeDir.mkdirs()) {
                throw new IOException(
                        "Could not create native directory: " + nativeDir
                );
            }

        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to prepare native directory",
                    e
            );
        }

        List<Library> toBeRemapped = new ArrayList<>();

        for (Library library : libraries) {
            if (library.downloads != null
                    && library.downloads.artifact != null) {

                if (library.name.contains("com.mojang")) {
                    toBeRemapped.add(library);
                } else {
                    dependencies.add(
                            SulphurConfigurations.nonTransitiveInclude,
                            library.name
                    );
                }
            }

            if (!extractNatives || library.usesNewFormat()) {
                continue;
            }

            Artifact artifact = library.getNativeArtifact();

            if (artifact == null) {
                continue;
            }

            String filename = new File(artifact.url.getFile()).getName();
            File nativeJar = new File(libraryDir, filename);

            try {
                if (!nativeJar.isFile()) {
                    File parent = nativeJar.getParentFile();

                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException(
                                "Could not create directory: " + parent
                        );
                    }

                    try (InputStream input = artifact.url.openStream()) {
                        Files.copy(input, nativeJar.toPath());
                    }
                }

                extract(project, library, client, nativeJar);

            } catch (IOException e) {
                throw new RuntimeException(
                        "Failed to process native library: " + filename,
                        e
                );
            }
        }

        if (extractNatives) {
            try {
                Files.write(
                        nativeMarker.toPath(),
                        architecture.getBytes(StandardCharsets.UTF_8)
                );
            } catch (IOException e) {
                throw new RuntimeException(
                        "Failed to write native marker",
                        e
                );
            }
        }

        return toBeRemapped;
    }

    private static void extract(
            Project project,
            Library library,
            VersionData client,
            File nativeJar
    ) {
        File nativeDir = FileUtil.getVersionNativesFolder(project, client.id);

        Path nativeRoot = nativeDir.toPath()
                .toAbsolutePath()
                .normalize();

        try (ZipInputStream zip = new ZipInputStream(
                Files.newInputStream(nativeJar.toPath())
        )) {
            ZipEntry entry;

            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                String name = entry.getName();

                if (isExcluded(library, name)) {
                    continue;
                }

                // Read enough bytes for PE header inspection.
                byte[] header = new byte[4096];
                int headerLength = 0;

                while (headerLength < header.length) {
                    int read = zip.read(
                            header,
                            headerLength,
                            header.length - headerLength
                    );

                    if (read == -1) {
                        break;
                    }

                    headerLength += read;
                }

                boolean compatible;

                switch (OSUtil.osId) {
                    case "windows":
                        compatible = PEUtil.isCompatiblePE(
                                header,
                                headerLength
                        );
                        break;

                    case "linux":
                        compatible = ELFUtil.isCompatibleElf(
                                header,
                                headerLength
                        );
                        break;

                    case "osx":
                    case "macos":
                        compatible = MachOUtil.isCompatibleMachO(
                                header,
                                headerLength
                        );
                        break;

                    default:
                        compatible = false;
                        break;
                }

                if (!compatible) {
                    System.out.println(
                            "Skipping incompatible native binary: " + name
                    );
                    continue;
                }

                Path outputPath = nativeRoot.resolve(name)
                        .toAbsolutePath()
                        .normalize();

                if (!outputPath.startsWith(nativeRoot)) {
                    throw new IOException(
                            "Invalid native archive entry: " + name
                    );
                }

                File output = outputPath.toFile();
                File parent = output.getParentFile();

                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException(
                            "Could not create directory: " + parent
                    );
                }

                try (OutputStream out = Files.newOutputStream(outputPath)) {
                    out.write(header, 0, headerLength);

                    byte[] buffer = new byte[8192];
                    int read;

                    while ((read = zip.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
            }

        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to extract natives from " + nativeJar,
                    e
            );
        }
    }

    private static boolean isExcluded(Library library, String name) {
        if (library.extract == null || library.extract.exclude == null) {
            return false;
        }

        for (String excluded : library.extract.exclude) {
            if (name.startsWith(excluded)) {
                return true;
            }
        }

        return false;
    }

    private static void clearDirectory(File directory) throws IOException {
        if (!directory.exists()) {
            return;
        }

        try (java.util.stream.Stream<Path> paths =
                     Files.walk(directory.toPath())) {

            paths.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(directory.toPath()))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });

        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    // -------------------------------------------------------------------------
    // Minecraft Asset Downloading
    // -------------------------------------------------------------------------

    public static void downloadAssets(File assetsDir, VersionData versionData) {
        File indexesDir = new File(assetsDir, "indexes");
        File objectsDir = new File(assetsDir, "objects");

        File assetsManifest = new File(
                indexesDir,
                versionData.assetIndex.id + ".json"
        );

        try {
            Files.createDirectories(indexesDir.toPath());
            Files.createDirectories(objectsDir.toPath());

            String manifestData;

            if (assetsManifest.isFile()) {
                manifestData = new String(
                        Files.readAllBytes(assetsManifest.toPath()),
                        StandardCharsets.UTF_8
                );
            } else {
                try (InputStream input = versionData.assetIndex.url.openStream()) {
                    byte[] bytes = readAllBytes(input);

                    Files.write(assetsManifest.toPath(), bytes);

                    manifestData = new String(
                            bytes,
                            StandardCharsets.UTF_8
                    );
                }
            }

            AssetsManifest manifest = VersionResolver.GSON_LOCAL.get()
                    .fromJson(manifestData, AssetsManifest.class);

            Path objectsPath = objectsDir.toPath()
                    .toAbsolutePath()
                    .normalize();

            List<Callable<Void>> tasks = new ArrayList<>();

            for (Map.Entry<String, Asset> entry :
                    manifest.objects.entrySet()) {

                tasks.add(makeDownloadTask(objectsPath, entry.getValue().hash));
            }

            System.out.println("Downloading Minecraft assets...");

            List<Future<Void>> futures = assetDownloadService.invokeAll(tasks);

            for (Future<Void> future : futures) {
                future.get();
            }

            System.out.println("Minecraft assets finished downloading!");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new RuntimeException(
                    "Minecraft asset downloading was interrupted",
                    e
            );

        } catch (ExecutionException | IOException e) {
            throw new RuntimeException(
                    "Failed to download Minecraft assets",
                    e
            );
        }
    }

    private static Callable<Void> makeDownloadTask(
            Path objectsDir,
            String hash
    ) {
        String subDir = hash.substring(0, 2);

        Path subPath = objectsDir.resolve(subDir);
        Path output = subPath.resolve(hash);

        return () -> {
            if (Files.isRegularFile(output)) {
                return null;
            }

            Files.createDirectories(subPath);

            String fileUrl = assetsUrl + subDir + "/" + hash;

            try (InputStream input = URI.create(fileUrl).toURL().openStream()) {
                try {
                    Files.copy(input, output);
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // Another download task may have created the same object.
                }
            }

            return null;
        };
    }

    private static byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        byte[] buffer = new byte[8192];
        int read;

        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }

        return output.toByteArray();
    }
}