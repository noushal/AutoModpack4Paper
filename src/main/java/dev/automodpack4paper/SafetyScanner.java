package dev.automodpack4paper;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Pre-publish check of the modpack folder. Clients auto-download whatever gets published, so anything
 * unexpected is reported and (with {@code safety.enforce}) blocks publishing until the admin fixes it.
 */
public final class SafetyScanner {

    public record Result(int files, long bytes, List<String> violations) {
        public boolean clean() {
            return violations.isEmpty();
        }
    }

    private static final Pattern WINDOWS_RESERVED = Pattern.compile("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?$");
    private static final int MAX_REPORTED = 25;

    private final Set<String> allowedDirs;
    private final Set<String> allowedRootFiles;
    private final Set<String> blockedExtensions;
    private final long maxFileBytes;

    public SafetyScanner(ConfigurationSection cfg) {
        this.allowedDirs = lower(cfg.getStringList("allowed-top-level-dirs"));
        this.allowedRootFiles = lower(cfg.getStringList("allowed-root-files"));
        this.blockedExtensions = lower(cfg.getStringList("blocked-extensions"));
        this.maxFileBytes = cfg.getLong("max-file-size-mb", 512) * 1024L * 1024L;
    }

    private static Set<String> lower(List<String> in) {
        return in.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
    }

    public Result scan(Path root) throws IOException {
        List<String> bad = new ArrayList<>();
        int[] files = {0};
        long[] bytes = {0};
        Path base = root.toAbsolutePath().normalize();

        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                if (dir.equals(base)) return FileVisitResult.CONTINUE;
                if (a.isSymbolicLink() || a.isOther()) {
                    flag(bad, base, dir, "symlink/junction not allowed");
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (base.relativize(dir).getNameCount() == 1
                    && !allowedDirs.contains(dir.getFileName().toString().toLowerCase(Locale.ROOT))) {
                    if (hasContent(dir)) flag(bad, base, dir, "top-level folder not in allowed-top-level-dirs");
                    return FileVisitResult.SKIP_SUBTREE; // empty folders publish nothing
                }
                if (WINDOWS_RESERVED.matcher(dir.getFileName().toString()).matches()) {
                    flag(bad, base, dir, "reserved device name");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                String name = file.getFileName().toString();
                String lname = name.toLowerCase(Locale.ROOT);
                Path rel = base.relativize(file);
                if (a.isSymbolicLink() || a.isOther() || Files.isSymbolicLink(file)) {
                    flag(bad, base, file, "symlink not allowed");
                    return FileVisitResult.CONTINUE;
                }
                if (rel.getNameCount() == 1 && !allowedRootFiles.contains(lname)) {
                    flag(bad, base, file, "file in modpack root not in allowed-root-files");
                }
                if (WINDOWS_RESERVED.matcher(name).matches()) flag(bad, base, file, "reserved device name");
                if (name.endsWith(".") || name.endsWith(" ")) flag(bad, base, file, "trailing dot/space in name");
                int dot = lname.lastIndexOf('.');
                String ext = dot < 0 ? "" : lname.substring(dot + 1);
                if (blockedExtensions.contains(ext)) flag(bad, base, file, "blocked extension ." + ext);
                if (ext.equals("jar") && !rel.getName(0).toString().equalsIgnoreCase("mods")) {
                    flag(bad, base, file, ".jar outside mods/");
                }
                if (a.size() > maxFileBytes) flag(bad, base, file, "larger than max-file-size-mb");
                files[0]++;
                bytes[0] += a.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return new Result(files[0], bytes[0], bad);
    }

    private static boolean hasContent(Path dir) {
        try (var walk = Files.walk(dir)) {
            return walk.anyMatch(p -> !Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        } catch (IOException e) {
            return true; // unreadable: treat as content, fail closed
        }
    }

    private static void flag(List<String> bad, Path base, Path p, String why) {
        if (bad.size() == MAX_REPORTED) bad.add("... more violations omitted");
        else if (bad.size() < MAX_REPORTED) bad.add(base.relativize(p).toString().replace('\\', '/') + ": " + why);
    }
}
