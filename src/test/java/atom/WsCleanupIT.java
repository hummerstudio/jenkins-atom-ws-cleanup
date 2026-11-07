package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ws-cleanup atom, started the way the runner starts it against a throwaway workspace.
 *
 * <p>These are the checks {@code tools/smoke.sh} made: patterns delete and keep protects, {@code .git} survives, a dry
 * run reports the same counts without deleting, keep wins over delete, and empty patterns are an error rather than a
 * silent no-op.
 */
class WsCleanupIT {

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    /** A workspace with the usual junk: a build tree, a jar, a repository, sources and a log. */
    private Atom workspace() {
        return atom.file("module/target/classes/A.class", "class\n")
                .file("build/app.jar", "jar\n")
                .file(".git/HEAD", "ref\n")
                .file("src/Main.java", "keep me\n")
                .file("logs/build.log", "log\n");
    }

    @Test
    @DisplayName("patterns delete, keep protects, .git is never touched")
    void deletesWhatThePatternsMatch() throws Exception {
        AtomResult run = workspace()
                .input("patterns", "**/target, **/target/**, build/**, logs/*.log")
                .input("keep", "**/*.class.keep")
                .input("dryRun", "false")
                .run();

        run.assertOk();
        assertFalse(run.exists("module/target/classes/A.class"), "target was not cleaned");
        assertFalse(run.exists("build/app.jar"), "build was not cleaned");
        assertFalse(run.exists("logs/build.log"), "log pattern did not match");
        assertTrue(run.exists("src/Main.java"), "unmatched file was deleted");
        assertTrue(run.exists(".git/HEAD"), ".git must survive");
        assertEquals("success", run.status(), run.toString());
        assertTrue(Integer.parseInt(run.field("deletedFiles")) >= 1,
                "nothing was reported as deleted:\n" + run.toString());
        assertFalse(run.exists("module/target"), "deleteDirs=true must remove the directory too");
        assertTrue(run.document().toString().contains("freedBytes"), "freed bytes missing:\n" + run.toString());
    }

    @Test
    @DisplayName("dryRun reports the same numbers and deletes nothing")
    void dryRunDeletesNothing() throws Exception {
        AtomResult run = workspace()
                .input("patterns", "**/target/**, build/**")
                .input("keep", "")
                .input("dryRun", "true")
                .run();

        run.assertOk();
        assertTrue(run.exists("module/target/classes/A.class"), "dry run deleted a file");
        assertEquals("2", run.field("deletedFiles"), "dry run count:\n" + run.toString());
        assertEquals("true", run.field("dryRun"), "dry run marker missing:\n" + run.toString());
    }

    @Test
    @DisplayName("keep wins over delete")
    void keepWins() throws Exception {
        AtomResult run = workspace()
                .input("patterns", "**/*.log, **/*.jar")
                .input("keep", "build/*.jar")
                .input("dryRun", "false")
                .run();

        run.assertOk();
        assertTrue(run.exists("build/app.jar"), "keep did not protect the jar");
        assertFalse(run.exists("logs/build.log"), "log should still be deleted");
    }

    @Test
    @DisplayName("no patterns is an error, not a silent no-op")
    void refusesEmptyPatterns() throws Exception {
        AtomResult run = workspace()
                .input("patterns", "")
                .input("keep", "")
                .input("dryRun", "false")
                .run();

        run.assertRefused();
        assertNotNull(run.field("message"));
        assertTrue(run.field("message").contains("no patterns given"),
                "message is not readable:\n" + run.toString());
    }
}
