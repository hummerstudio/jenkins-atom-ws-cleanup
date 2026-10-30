package com.jenkinschina.atom.wscleanup;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Glob;
import java.nio.file.Files;
import java.nio.file.Path;
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
        // the plugin's deleteDirs: without it only files are removed and the empty tree stays
        boolean deleteDirs = context.getBooleanParam("deleteDirs", true);
        boolean notFailBuild = context.getBooleanParam("notFailBuild", false);

        // Glob from the SDK: **/ means zero or more directories, the way Ant and Jenkins read it
        String delete = sanitize(patterns);
        String protect = sanitize(keep);
        if (delete.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001, "no patterns given: nothing would be deleted");
        }

        List<Path> candidates = new ArrayList<>();
        try (var walk = Files.walk(workspace, 16)) {
            walk.filter(path -> !path.equals(workspace))
                    .filter(path -> Files.isRegularFile(path)
                            || (deleteDirs && Files.isDirectory(path)))
                    .filter(path -> Glob.matches(delete, workspace.relativize(path)))
                    .filter(path -> !Glob.matches(protect, workspace.relativize(path)))
                    .filter(path -> !forbidden(workspace, path))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(200_000)
                    .forEach(candidates::add);
        }

        long bytes = 0;
        int deleted = 0;
        int deletedDirs = 0;
        int failed = 0;
        for (Path file : candidates) {
            try {
                long size = Files.isRegularFile(file) ? Files.size(file) : 0;
                if (!dryRun) {
                    if (Files.isDirectory(file)) {
                        deleteTree(file);
                    } else {
                        Files.deleteIfExists(file);
                    }
                }
                bytes += size;
                if (Files.isDirectory(file)) {
                    deletedDirs++;
                } else {
                    deleted++;
                }
            } catch (Exception e) {
                failed++;
                context.log("[ws-cleanup] could not delete " + file + ": " + e.getMessage());
            }
        }

        context.log("[ws-cleanup] " + (dryRun ? "would delete " : "deleted ") + deleted + " files, "
                + deletedDirs + " directories, " + human(bytes) + " in " + workspace);

        AtomResult result = AtomResult.success()
                .data("deletedFiles", String.valueOf(deleted))
                .data("failed", String.valueOf(failed))
                .data("deletedDirs", String.valueOf(deletedDirs))
                .data("freedBytes", String.valueOf(bytes))
                .data("freedHuman", human(bytes));
        if (dryRun) {
            result.data("dryRun", "true");
        }
        if (failed > 0) {
            // the plugin's notFailBuild: a file we cannot remove is a warning, not a red build
            if (notFailBuild) {
                return result.markUnstable();
            }
            return AtomResult.failure(failed + " file(s) could not be deleted");
        }
        return result;
    }

    private static void deleteTree(Path root) throws java.io.IOException {
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static boolean forbidden(Path workspace, Path path) {
        for (Path part : workspace.relativize(path)) {
            if (FORBIDDEN.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kib = bytes / 1024.0;
        if (kib < 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KiB", kib);
        }
        double mib = kib / 1024.0;
        return String.format(java.util.Locale.ROOT, "%.1f MiB", mib);
    }

    /** A pattern that could climb out of the workspace is not a pattern this step will honour. */
    private static String sanitize(String spec) {
        StringBuilder kept = new StringBuilder();
        for (String part : spec.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty() || trimmed.contains("..")) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append(',');
            }
            kept.append(trimmed);
        }
        return kept.toString();
    }
}
