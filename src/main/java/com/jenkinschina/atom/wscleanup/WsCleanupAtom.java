package com.jenkinschina.atom.wscleanup;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ws-cleanup as an atom: delete what a pattern matches inside the workspace, keep what another pattern protects.
 *
 * <p>Pipeline has {@code cleanWs()}; this is for jobs that run an atom instead of a pipeline, and for the cases where
 * "everything" is too blunt — {@code **}{@code /target}, {@code **}{@code /*.log}, but never {@code .git} or the
 * directory the next atom reads.
 */
public class WsCleanupAtom implements AtomTask {

    /** Anything outside the workspace is refused; these are refused even inside it. */
    private static final List<String> FORBIDDEN = List.of(".git", ".atoms");

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String patterns = context.getParam("patterns", "");
        String keep = context.getParam("keep", "");
        boolean dryRun = context.getBooleanParam("dryRun", false);

        List<PathMatcher> delete = matchers(patterns);
        List<PathMatcher> protect = matchers(keep);
        if (delete.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001, "no patterns given: nothing would be deleted");
        }

        List<Path> candidates = new ArrayList<>();
        try (var walk = Files.walk(workspace, 16)) {
            walk.filter(path -> !path.equals(workspace))
                    .filter(Files::isRegularFile)
                    .filter(path -> matches(workspace, path, delete))
                    .filter(path -> !matches(workspace, path, protect))
                    .filter(path -> !forbidden(workspace, path))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(200_000)
                    .forEach(candidates::add);
        }

        long bytes = 0;
        int deleted = 0;
        for (Path file : candidates) {
            try {
                long size = Files.size(file);
                if (!dryRun) {
                    Files.deleteIfExists(file);
                }
                bytes += size;
                deleted++;
            } catch (Exception e) {
                context.log("[ws-cleanup] could not delete " + file + ": " + e.getMessage());
            }
        }

        // directories the deletions emptied, deepest first
        int directories = 0;
        if (!dryRun) {
            List<Path> empty = new ArrayList<>();
            try (var walk = Files.walk(workspace, 16)) {
                walk.filter(Files::isDirectory)
                        .filter(path -> !path.equals(workspace))
                        .filter(path -> !forbidden(workspace, path))
                        .filter(path -> matches(workspace, path, delete))
                        .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                        .forEach(empty::add);
            }
            for (Path dir : empty) {
                try (var entries = Files.list(dir)) {
                    if (entries.findAny().isEmpty()) {
                        Files.delete(dir);
                        directories++;
                    }
                } catch (Exception ignored) {
                    // the directory disappeared or is not ours to remove
                }
            }
        }

        context.log("[ws-cleanup] " + (dryRun ? "would delete " : "deleted ") + deleted + " files, "
                + directories + " directories, " + human(bytes) + " in " + workspace);

        AtomResult result = AtomResult.success()
                .data("deletedFiles", String.valueOf(deleted))
                .data("deletedDirs", String.valueOf(directories))
                .data("freedBytes", String.valueOf(bytes))
                .data("freedHuman", human(bytes));
        if (dryRun) {
            result.data("dryRun", "true");
        }
        return result;
    }

    private static boolean forbidden(Path workspace, Path path) {
        for (Path part : workspace.relativize(path)) {
            if (FORBIDDEN.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(Path workspace, Path path, List<PathMatcher> matchers) {
        Path relative = workspace.relativize(path);
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(relative) || matcher.matches(path.getFileName())) {
                return true;
            }
        }
        return false;
    }

    private static List<PathMatcher> matchers(String spec) {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String part : spec.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.contains("..")) {
                continue;
            }
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + trimmed));
        }
        return matchers;
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        for (String unit : List.of("KiB", "MiB", "GiB")) {
            value /= 1024;
            if (value < 1024) {
                return String.format(java.util.Locale.ROOT, "%.1f %s", value, unit);
            }
        }
        return String.format(java.util.Locale.ROOT, "%.1f TiB", value / 1024);
    }
}
