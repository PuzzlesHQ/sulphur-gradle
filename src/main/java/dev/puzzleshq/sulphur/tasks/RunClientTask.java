package dev.puzzleshq.sulphur.tasks;

import com.google.gson.JsonElement;
import dev.puzzleshq.lunar.ConfigPair;
import dev.puzzleshq.sulphur.util.FileUtil;
import dev.puzzleshq.sulphur.VersionResolver;
import dev.puzzleshq.sulphur.minecraft.manifest.version.client.VersionData;
import dev.puzzleshq.sulphur.config.SulpherConfig;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSetContainer;

import java.io.File;
import java.util.*;

public abstract class RunClientTask extends JavaExec {

    public RunClientTask() {
        setGroup(TaskGroups.run);

        File runDir = getProject().file("run");

        if (!runDir.exists())
            runDir.mkdirs();

        classpath(getProject().getExtensions().getByType(SourceSetContainer.class).getByName("main").getRuntimeClasspath());

        ConfigPair<SulpherConfig> pair = SulpherConfig.get(getProject());
        SulpherConfig config = pair.getConfig();

        VersionData versionData = VersionResolver.getManifest(getProject(), pair).find(config.minecraft.minecraftVersion.get()).get(getProject());

        File jarsDir = new File(FileUtil.getSlimeCache(getProject()), "jars");
        File clientJar = new File(jarsDir, versionData.id + "-client.jar");

        jvmArgs(
                "-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump",
                "-Dminecraft.launcher.brand=minecraft-launcher",
                "-Dminecraft.launcher.version=2.6.16",
                "-Xss1M",
                "-Xmx2G",
                "-Djava.library.path=" + FileUtil.getVersionNativesFolder(getProject(), versionData.id).getAbsolutePath(),
                "-Dminecraft.client.jar=" + clientJar.getAbsolutePath(),
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+UseG1GC",
                "-XX:G1NewSizePercent=20",
                "-XX:G1ReservePercent=20",
                "-XX:MaxGCPauseMillis=50",
                "-XX:G1HeapRegionSize=32M",
                "-Dminecraft.api.auth.host=https://nope.invalid",
                "-Dminecraft.api.account.host=https://nope.invalid",
                "-Dminecraft.api.session.host=https://nope.invalid"
        );

        jvmArgs(
                "-Dpuzzle.core.gameProvider=dev.puzzleshq.puzzleloader.loader.provider.game.impl.MinecraftProvider"
        );

        args(
//                "--do-title-transformer", "false"
//                "--mod-folder", new File(getProject().file("runs"), "pmods").getAbsolutePath()
        );

        String assetsDir = FileUtil.getAssetsDir(getProject()).getAbsolutePath().replaceAll("\\\\", "/");
        String gameDir = runDir.getAbsolutePath().replaceAll("\\\\", "/");

        if (versionData.minecraftArguments != null) {
            String[] gameArgs = versionData.minecraftArguments.split(" ");
            List<String> newArgs = new ArrayList<>();
            for (String s : gameArgs) {
                s = s.replaceAll("\\$\\{auth_player_name}", config.minecraft.mcUsername.get());
                s = s.replaceAll("\\$\\{auth_session}", "");
                s = s.replaceAll("\\$\\{game_directory}", gameDir);
                s = s.replaceAll("\\$\\{game_assets}", assetsDir);
                s = s.replaceAll("\\$\\{assets_root}", assetsDir);
                s = s.replaceAll("\\$\\{assets_index_name}", versionData.assets);
                s = s.replaceAll("\\$\\{version_type}", "sulphur-dev");
                s = s.replaceAll("\\$\\{version_name}", versionData.id);
                s = s.replaceAll("\\$\\{user_properties}", "{}");
                newArgs.add(s);
            }
            args(newArgs);
        }

        // https://piston-meta.mojang.com/v1/packages/79b63085fb7b4805877ee719f6518ab3c87b3833/1.16.5-rc1.json
        if (versionData.arguments != null) {
            List<String> newArgs = new ArrayList<>();
            for (JsonElement elem : versionData.arguments.game) {
                if (elem.isJsonObject()) continue;
                String s = elem.getAsString();
                s = s.replaceAll("\\$\\{auth_player_name}", config.minecraft.mcUsername.get());
                s = s.replaceAll("\\$\\{auth_session}", "");
                s = s.replaceAll("\\$\\{game_directory}", gameDir);
                s = s.replaceAll("\\$\\{assets_root}", assetsDir);
                s = s.replaceAll("\\$\\{assets_index_name}", versionData.assets);
                s = s.replaceAll("\\$\\{auth_access_token}", "");
                s = s.replaceAll("\\$\\{auth_xuid}", "0");
                s = s.replaceAll("\\$\\{auth_uuid}", UUID.randomUUID().toString());
                s = s.replaceAll("\\$\\{user_type}", "msa");
                s = s.replaceAll("\\$\\{version_type}", "sulphur-dev");
                s = s.replaceAll("\\$\\{version_name}", versionData.id);
                s = s.replaceAll("\\$\\{user_properties}", "{}");
                newArgs.add(s);
            }
            args(newArgs);
        }

        getMainClass().set("dev.puzzleshq.puzzleloader.loader.launch.pieces.ClientPiece");
//        getMainClass().set(versionData.mainClass);

//        ConfigurableFileCollection collection = (ConfigurableFileCollection) getClasspath();
//        collection.from(JigsawGame.CLIENT_SOURCE_SET.getRuntimeClasspath().getFiles());
//        collection.from(JigsawGame.COMMON_SOURCE_SET.getRuntimeClasspath().getFiles());

        setStandardInput(System.in);
        workingDir(runDir);
    }

}