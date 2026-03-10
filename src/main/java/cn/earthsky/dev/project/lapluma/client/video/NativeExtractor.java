package cn.earthsky.dev.project.lapluma.client.video;

import cn.earthsky.dev.project.lapluma.LaPluma;
import net.minecraft.client.Minecraft;

import java.io.*;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLDecoder;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;

/**
 * Extracts JavaCPP/FFmpeg native libraries from the mod JAR to a cache directory
 * and configures the JVM to find them. Forge's LaunchClassLoader prevents
 * javacpp's built-in extraction from working correctly.
 */
public class NativeExtractor {

    private static boolean extracted = false;
    private static File cacheDir;

    public static synchronized void ensureExtracted() {
        if (extracted) return;

        cacheDir = new File(Minecraft.getMinecraft().gameDir, "lapluma" + File.separator + "natives");
        cacheDir.mkdirs();

        try {
            extractNativesFromClasspath();
            addToJavaLibraryPath(cacheDir.getAbsolutePath());
            System.setProperty("org.bytedeco.javacpp.cachedir", cacheDir.getAbsolutePath());
            System.setProperty("org.bytedeco.javacpp.pathsfirst", "true");
            extracted = true;
            LaPluma.getLogger().log(Level.INFO, "[NativeExtractor] Natives ready at: " + cacheDir.getAbsolutePath());
        } catch (Exception e) {
            LaPluma.getLogger().log(Level.SEVERE, "[NativeExtractor] Failed to extract natives", e);
        }
    }

    /**
     * Force-adds a path to java.library.path at runtime by resetting
     * the cached sys_paths field in ClassLoader.
     */
    private static void addToJavaLibraryPath(String path) {
        try {
            String current = System.getProperty("java.library.path", "");
            if (!current.contains(path)) {
                System.setProperty("java.library.path", path + File.pathSeparator + current);
            }
            Field sysPathsField = ClassLoader.class.getDeclaredField("sys_paths");
            sysPathsField.setAccessible(true);
            sysPathsField.set(null, null);
            LaPluma.getLogger().log(Level.INFO, "[NativeExtractor] Updated java.library.path");
        } catch (Exception e) {
            LaPluma.getLogger().log(Level.WARNING, "[NativeExtractor] Could not update java.library.path via reflection, relying on cachedir", e);
        }
    }

    private static void extractNativesFromClasspath() throws IOException {
        String osName = System.getProperty("os.name", "").toLowerCase();
        String osArch = System.getProperty("os.arch", "").toLowerCase();

        String platform;
        if (osName.contains("win")) {
            platform = "windows-x86_64";
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            if (osArch.contains("aarch64") || osArch.contains("arm")) {
                platform = "macosx-arm64";
            } else {
                platform = "macosx-x86_64";
            }
        } else {
            platform = "linux-x86_64";
        }

        LaPluma.getLogger().log(Level.INFO, "[NativeExtractor] Detected platform: " + platform);

        String[] searchPrefixes = {
                "org/bytedeco/ffmpeg/" + platform + "/",
                "org/bytedeco/javacpp/" + platform + "/",
        };

        Set<String> processedJars = new HashSet<>();
        ClassLoader cl = NativeExtractor.class.getClassLoader();

        for (String prefix : searchPrefixes) {
            Enumeration<URL> resources = cl.getResources(prefix);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                processUrl(url, prefix, processedJars);
            }
        }

        try {
            Enumeration<URL> allRoots = cl.getResources("");
            while (allRoots.hasMoreElements()) {
                URL url = allRoots.nextElement();
                if ("jar".equals(url.getProtocol())) {
                    for (String prefix : searchPrefixes) {
                        processUrl(url, prefix, processedJars);
                    }
                }
            }
        } catch (Exception ignored) {}

        File[] extracted = cacheDir.listFiles();
        if (extracted != null) {
            LaPluma.getLogger().log(Level.INFO, "[NativeExtractor] Total native files in cache: " + extracted.length);
            for (File f : extracted) {
                LaPluma.getLogger().log(Level.INFO, "[NativeExtractor]   " + f.getName() + " (" + f.length() + " bytes)");
            }
        }
    }

    private static void processUrl(URL url, String prefix, Set<String> processedJars) {
        String proto = url.getProtocol();
        if ("jar".equals(proto)) {
            String jarPath = getJarPath(url);
            if (jarPath != null && processedJars.add(jarPath + "|" + prefix)) {
                extractFromJar(jarPath, prefix);
            }
        } else if ("file".equals(proto)) {
            try {
                extractFromFileDir(new File(url.toURI()), prefix);
            } catch (Exception ignored) {}
        }
    }

    private static String getJarPath(URL jarUrl) {
        String path = jarUrl.getPath();
        int bangIdx = path.indexOf('!');
        if (bangIdx < 0) return null;
        String jarPath = path.substring(0, bangIdx);
        if (jarPath.startsWith("file:")) jarPath = jarPath.substring(5);
        try {
            jarPath = URLDecoder.decode(jarPath, "UTF-8");
        } catch (Exception ignored) {}
        return jarPath;
    }

    private static void extractFromJar(String jarPath, String prefix) {
        try (JarFile jar = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(prefix) || entry.isDirectory()) continue;
                if (!isNativeLib(name)) continue;

                String fileName = name.substring(name.lastIndexOf('/') + 1);
                File outFile = new File(cacheDir, fileName);

                if (outFile.exists() && outFile.length() == entry.getSize()) continue;

                try (InputStream is = jar.getInputStream(entry);
                     FileOutputStream fos = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) > 0) {
                        fos.write(buf, 0, len);
                    }
                }
                LaPluma.getLogger().log(Level.INFO, "[NativeExtractor] Extracted: " + fileName + " from " + new File(jarPath).getName());
            }
        } catch (Exception e) {
            LaPluma.getLogger().log(Level.WARNING, "[NativeExtractor] Error reading jar: " + jarPath, e);
        }
    }

    private static void extractFromFileDir(File dir, String prefix) {
        if (!dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && isNativeLib(f.getName())) {
                File outFile = new File(cacheDir, f.getName());
                if (outFile.exists() && outFile.length() == f.length()) continue;
                try (InputStream is = new FileInputStream(f);
                     FileOutputStream fos = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) > 0) {
                        fos.write(buf, 0, len);
                    }
                } catch (IOException e) {
                    LaPluma.getLogger().log(Level.WARNING, "[NativeExtractor] Failed to copy: " + f.getName(), e);
                }
            }
        }
    }

    private static boolean isNativeLib(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib")
                || lower.endsWith(".jnilib");
    }
}
