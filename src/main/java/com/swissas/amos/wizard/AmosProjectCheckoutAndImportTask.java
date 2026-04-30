package com.swissas.amos.wizard;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.ModifiableModuleModel;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsDirectoryMapping;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.config.GitExecutableManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Background task that performs the full AMOS project setup:
 * <ol>
 *   <li>Git clone (3-phase: skip / resume / fresh)</li>
 *   <li>Write minimal {@code .idea/} config skeleton (modules.xml, vcs.xml, misc.xml)</li>
 *   <li>Write Eclipse-linked .iml files for each module</li>
 *   <li>Open the project in the IDE</li>
 *   <li>Wait for post-startup initialization</li>
 *   <li>Load/fix-up modules via ModuleManager API and rewrite modules.xml</li>
 *   <li>Configure project settings (JDK, VCS, code style)</li>
 *   <li>Copy run configurations and compiler settings from source project</li>
 * </ol>
 * <p>
 * The {@code .idea/} skeleton and {@code .iml} files are written BEFORE opening
 * the project so that {@code openOrImport()} picks up the correct module list and
 * never auto-creates a root project module.
 */
public class AmosProjectCheckoutAndImportTask extends Task.Backgroundable {

    private static final Logger LOG = Logger.getInstance(AmosProjectCheckoutAndImportTask.class);

    private static final List<String> MODULE_NAMES = Arrays.asList(
            "amos_client", "amos_server", "amos_shared", "amos_test_junit", "amos_web");

    // ---- Static XML templates ----

    /**
     * Eclipse-linked .iml template — tells IntelliJ to read the Eclipse .classpath/.project
     * files from the module directory instead of maintaining its own source/dependency model.
     * This is equivalent to selecting "Link created IntelliJ IDEA modules to Eclipse project files"
     * in the import wizard.
     */
    private static final String ECLIPSE_IML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<module classpath=\"eclipse\" classpath-dir=\"$MODULE_DIR$\" type=\"JAVA_MODULE\" version=\"4\" />\n";

    /** .idea/.gitignore — standard IntelliJ defaults. */
    private static final String IDEA_GITIGNORE =
            "# Default ignored files\n" +
            "/shelf/\n" +
            "/workspace.xml\n";

    private static final String CODE_STYLE_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<component name=\"ProjectCodeStyleConfiguration\">\n" +
            "    <state>\n" +
            "        <option name=\"USE_PER_PROJECT_SETTINGS\" value=\"false\"/>\n" +
            "    </state>\n" +
            "</component>\n";

    /**
     * VCS mapping — pre-written so {@code openOrImport()} picks it up immediately.
     */
    private static final String VCS_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<project version=\"4\">\n" +
            "  <component name=\"VcsDirectoryMappings\">\n" +
            "    <mapping directory=\"\" vcs=\"Git\" />\n" +
            "  </component>\n" +
            "</project>\n";

    /**
     * ProjectRootManager entry — sets Java language level to JDK 21 so the IDE
     * shows the correct language level immediately after the project is opened,
     * even before the JDK is configured programmatically.
     */
    private static final String MISC_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<project version=\"4\">\n" +
            "  <component name=\"ProjectRootManager\" version=\"2\" languageLevel=\"JDK_21\"\n" +
            "             default=\"false\" project-jdk-type=\"JavaSDK\" />\n" +
            "</project>\n";

    // ---- Fields ----

    private final String repoUrl;
    private final String username;
    private final String password;
    private final String branch;
    private final Path checkoutDir;
    private final String projectName;
    @Nullable
    private final Path sourceProjectPath;

    // ---- Timeout constants ----
    /** 2 hours for clone — large monorepos can take a very long time. */
    private static final int CLONE_TIMEOUT_MS   = 2 * 60 * 60 * 1000;
    /** 2 hours for fetch (resume path). */
    private static final int FETCH_TIMEOUT_MS   = 2 * 60 * 60 * 1000;
    /** 5 minutes for checkout (local operation). */
    private static final int CHECKOUT_TIMEOUT_MS = 5 * 60 * 1000;

    // ---- Constructor ----

    public AmosProjectCheckoutAndImportTask(@NotNull String repoUrl,
                                             @NotNull String username,
                                             @NotNull String password,
                                             @NotNull String branch,
                                             @NotNull Path checkoutDir,
                                             @NotNull String projectName,
                                             @Nullable Path sourceProjectPath) {
        super(null, "Setting up AMOS project...", true);
        this.repoUrl = repoUrl;
        this.username = username;
        this.password = password;
        this.branch = branch;
        this.checkoutDir = checkoutDir;
        this.projectName = projectName;
        this.sourceProjectPath = sourceProjectPath;
    }

    // ---- Task entry point ----

    @Override
    public void run(@NotNull ProgressIndicator indicator) {
        indicator.setIndeterminate(false);

        // ----------------------------------------------------------------
        // Step 1 — Clone the repository (skip / resume / fresh clone).
        //
        // The project is NOT opened yet — we clone into a plain directory
        // first, so git has an empty (or non-existent) target to work with.
        // ----------------------------------------------------------------
        boolean alreadyCheckedOut = isAlreadyCheckedOut();
        if (alreadyCheckedOut) {
            LOG.info("Repository already checked out on correct branch — skipping clone step");
            indicator.setText("Repository already checked out — skipping clone…");
        } else {
            boolean resuming = Files.isDirectory(this.checkoutDir.resolve(".git"));
            indicator.setText(resuming ? "Resuming repository checkout…" : "Cloning repository…");
            indicator.setFraction(0.05);
            if (!step1Clone(indicator)) return;
        }
        if (indicator.isCanceled()) return;
        indicator.setFraction(0.55);

        // ----------------------------------------------------------------
        // Step 2 — Write the .idea/ skeleton BEFORE opening the project.
        //
        // modules.xml lists only the 5 AMOS modules (no root module).
        // vcs.xml maps the project root to Git.
        // misc.xml sets the language level to JDK 21.
        // ----------------------------------------------------------------
        indicator.setText("Writing project configuration…");
        try {
            writeIdeaConfigFiles();
        } catch (IOException e) {
            LOG.error("Failed to write .idea config files", e);
            ApplicationManager.getApplication().invokeLater(() ->
                    Messages.showErrorDialog(
                            "Failed to write IntelliJ project files:\n" + e.getMessage(),
                            "Configuration Error"), ModalityState.any());
            return;
        }
        if (indicator.isCanceled()) return;
        indicator.setFraction(0.58);

        // ----------------------------------------------------------------
        // Step 3 — Write Eclipse-linked .iml files.
        //
        // Requires .classpath/.project files that only exist after the clone.
        // Written before opening so openOrImport() finds them on disk.
        // ----------------------------------------------------------------
        indicator.setText("Writing module configuration files…");
        try {
            writeImlFiles();
        } catch (IOException e) {
            LOG.error("Failed to write .iml files", e);
            ApplicationManager.getApplication().invokeLater(() ->
                    Messages.showErrorDialog(
                            "Failed to write IntelliJ module files:\n" + e.getMessage(),
                            "Configuration Error"), ModalityState.any());
            return;
        }
        if (indicator.isCanceled()) return;
        indicator.setFraction(0.62);

        // ----------------------------------------------------------------
        // Step 4 — Open the project.
        //
        // At this point the directory contains the full repository plus the
        // .idea/ skeleton and .iml files, so IntelliJ opens it correctly.
        // ----------------------------------------------------------------
        indicator.setText("Opening project \"" + this.projectName + "\"…");
        final Project[] projectRef = {null};
        ApplicationManager.getApplication().invokeAndWait(() -> {
            try {
                //noinspection deprecation
                projectRef[0] = com.intellij.ide.impl.ProjectUtil.openOrImport(
                        this.checkoutDir.toString(), null, false);
            } catch (Exception e) {
                LOG.error("Failed to open project at " + this.checkoutDir, e);
            }
        }, ModalityState.any());

        if (projectRef[0] == null) {
            LOG.error("ProjectUtil.openOrImport returned null for " + this.checkoutDir);
            ApplicationManager.getApplication().invokeLater(() ->
                    Messages.showErrorDialog(
                            "IntelliJ could not open the project at:\n" + this.checkoutDir +
                            "\n\nThe repository was cloned successfully — you can open it manually.",
                            "Project Open Failed"), ModalityState.any());
            return;
        }
        indicator.setFraction(0.70);

        // ----------------------------------------------------------------
        // Step 5 — Wait for post-startup initialization.
        // ----------------------------------------------------------------
        indicator.setText("Waiting for project initialization…");
        waitForProjectReady(projectRef[0], indicator);
        if (indicator.isCanceled()) return;
        indicator.setFraction(0.75);

        // ----------------------------------------------------------------
        // Step 6 — Rewrite modules.xml and load/fix-up modules.
        //
        // loadModules() removes any auto-created root module, loads the 5
        // AMOS modules, and rewrites modules.xml to the correct state.
        // ----------------------------------------------------------------
        indicator.setText("Loading modules…");
        loadModules(projectRef[0]);
        indicator.setFraction(0.82);

        // ----------------------------------------------------------------
        // Step 7 — Configure project settings (JDK, VCS, code style).
        // ----------------------------------------------------------------
        indicator.setText("Configuring project settings…");
        configureProjectSettings(projectRef[0]);
        indicator.setFraction(0.88);

        // ----------------------------------------------------------------
        // Step 8 — Copy Run Configurations & Compiler Settings.
        // ----------------------------------------------------------------
        if (this.sourceProjectPath != null) {
            indicator.setText("Copying run configurations…");
            step5CopyRunConfigs();
            indicator.setText("Copying compiler settings…");
            step6CopyCompilerSettings();
        }
        if (indicator.isCanceled()) return;
        indicator.setFraction(0.95);

        // ----------------------------------------------------------------
        // Step 9 — Verify that all expected modules were loaded.
        // ----------------------------------------------------------------
        indicator.setText("Verifying module configuration…");
        verifyModules(projectRef[0]);

        indicator.setFraction(1.0);
        LOG.info("AMOS project setup complete: " + this.checkoutDir);
    }

    // =========================================================================
    // Step 1 — Create empty project
    // =========================================================================

    /**
     * Creates the checkout directory and writes a minimal {@code .idea/} skeleton
     * so that IntelliJ recognises the directory as a valid project and opens it
     * immediately — before the repository is cloned.
     * <p>
     * Files written:
     * <ul>
     *   <li>{@code .idea/modules.xml} — lists the 5 AMOS module {@code .iml} paths
     *       (with {@code $PROJECT_DIR$} tokens).  Pre-writing this prevents
     *       {@code openOrImport()} from auto-creating a root project module.</li>
     *   <li>{@code .idea/vcs.xml} — registers the project root as a Git repository.</li>
     *   <li>{@code .idea/misc.xml} — sets the Java language level to JDK 21.</li>
     *   <li>{@code .idea/.gitignore} — standard IntelliJ defaults.</li>
     * </ul>
     */
    private void createEmptyProject() throws IOException {
        Files.createDirectories(this.checkoutDir);
        writeIdeaConfigFiles();
        LOG.info("Created empty project skeleton at: " + this.checkoutDir);
    }

    // =========================================================================
    // Step 2b — Pre-write .idea/ config files
    // =========================================================================

    /**
     * Writes the minimal {@code .idea/} files needed for IntelliJ to open the
     * project correctly.  Called from {@link #createEmptyProject()} before
     * the repository is cloned, and also re-invokable at any time (idempotent).
     */
    private void writeIdeaConfigFiles() throws IOException {
        Path ideaDir = this.checkoutDir.resolve(".idea");
        Files.createDirectories(ideaDir);

        // modules.xml — only the 5 Eclipse-linked modules, no root module
        Files.writeString(ideaDir.resolve("modules.xml"), buildModulesXml());
        LOG.info("Written .idea/modules.xml");

        // vcs.xml — project root mapped to Git
        Files.writeString(ideaDir.resolve("vcs.xml"), VCS_XML);
        LOG.info("Written .idea/vcs.xml");

        // misc.xml — sets JDK 21 language level immediately on project open
        Files.writeString(ideaDir.resolve("misc.xml"), MISC_XML);
        LOG.info("Written .idea/misc.xml");

        // .gitignore
        Path gitignore = ideaDir.resolve(".gitignore");
        if (!Files.exists(gitignore)) {
            Files.writeString(gitignore, IDEA_GITIGNORE);
            LOG.info("Written .idea/.gitignore");
        }
    }

    // =========================================================================
    // Step 5 — Refresh VFS after clone
    // =========================================================================

    /**
     * Performs a synchronous, recursive VFS refresh of the checkout directory.
     * <p>
     * When IntelliJ opened the (empty) project, its Virtual File System indexed
     * only the {@code .idea/} skeleton.  After {@code git clone} fills the
     * directory, the VFS must be told to rescan so that subsequent API calls
     * (e.g. {@link LocalFileSystem#findFileByPath}) find the new files.
     */
    private void refreshProjectVfs() {
        VirtualFile vDir = LocalFileSystem.getInstance()
                .refreshAndFindFileByPath(this.checkoutDir.toString().replace('\\', '/'));
        if (vDir != null) {
            vDir.refresh(false /* synchronous */, true /* recursive */);
            LOG.info("VFS refreshed for: " + this.checkoutDir);
        } else {
            LOG.warn("VFS could not find checkout dir after clone: " + this.checkoutDir);
        }
    }

    /**
     * Builds a {@code modules.xml} document that references exactly the 5 AMOS module
     * {@code .iml} files using {@code $PROJECT_DIR$} tokens.
     * No root project module is included.
     */
    @NotNull
    private String buildModulesXml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<project version=\"4\">\n");
        sb.append("  <component name=\"ProjectModuleManager\">\n");
        sb.append("    <modules>\n");
        for (String name : MODULE_NAMES) {
            sb.append("      <module fileurl=\"file://$PROJECT_DIR$/").append(name)
              .append("/").append(name).append(".iml\"");
            sb.append(" filepath=\"$PROJECT_DIR$/").append(name)
              .append("/").append(name).append(".iml\" />\n");
        }
        sb.append("    </modules>\n");
        sb.append("  </component>\n");
        sb.append("</project>\n");
        return sb.toString();
    }

    // =========================================================================
    // Step 2 — Write .iml files
    // =========================================================================

    /**
     * Writes Eclipse-linked {@code .iml} files into each module directory.
     * These files tell IntelliJ to read the Eclipse {@code .classpath}/{@code .project}
     * files directly.
     * <p>
     * These are written BEFORE opening the project because they live in module
     * directories (not in {@code .idea/}), so {@code openOrImport()} won't touch them.
     */
    private void writeImlFiles() throws IOException {
        List<String> written = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String moduleName : MODULE_NAMES) {
            Path moduleDir = this.checkoutDir.resolve(moduleName);
            Path imlPath = moduleDir.resolve(moduleName + ".iml");

            // Only write if the module directory has Eclipse project files
            if (!Files.exists(moduleDir.resolve(".classpath")) ||
                    !Files.exists(moduleDir.resolve(".project"))) {
                LOG.warn("Skipping .iml for " + moduleName + " — missing .classpath or .project");
                skipped.add(moduleName);
                continue;
            }

            Files.writeString(imlPath, ECLIPSE_IML);
            written.add(moduleName);
        }

        LOG.info("Written Eclipse-linked .iml files: " + written);
        if (!skipped.isEmpty()) {
            LOG.warn("Skipped .iml files (missing Eclipse files): " + skipped);
        }
    }

    // =========================================================================
    // Step 5 — Load modules programmatically
    // =========================================================================

    /**
     * Loads all module .iml files into the project using the ModuleManager API,
     * then removes any auto-created root project module that {@code openOrImport()}
     * may have injected.
     * <p>
     * IntelliJ's {@code openOrImport()} sometimes creates a root-level module
     * (named after the checkout directory) whose content root is the entire
     * checkout directory.  This pollutes the module list and makes the Project
     * view show the whole directory tree instead of only the module sources.
     * We remove it here and rewrite {@code modules.xml} to ensure the file on
     * disk also contains only the 5 AMOS modules — matching the reference project.
     */
    private void loadModules(@NotNull Project project) {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            // Refresh VFS so IntelliJ sees the .iml files we wrote to disk
            VirtualFile projectVDir = LocalFileSystem.getInstance()
                    .refreshAndFindFileByPath(this.checkoutDir.toString().replace('\\', '/'));
            if (projectVDir != null) {
                projectVDir.refresh(false, true);
            }

            WriteAction.run(() -> {
                ModifiableModuleModel model = ModuleManager.getInstance(project).getModifiableModel();
                List<String> loaded  = new ArrayList<>();
                List<String> failed  = new ArrayList<>();
                List<String> removed = new ArrayList<>();

                // ---- Step A: load the 5 AMOS modules ----
                for (String moduleName : MODULE_NAMES) {
                    Path imlPath = this.checkoutDir.resolve(moduleName).resolve(moduleName + ".iml");
                    if (!Files.exists(imlPath)) {
                        LOG.warn("Skipping module " + moduleName + " — .iml file not found at " + imlPath);
                        failed.add(moduleName);
                        continue;
                    }
                    try {
                        // loadModule is idempotent: if the module is already registered
                        // (because openOrImport respected modules.xml), it just returns it.
                        String imlUrl = imlPath.toString().replace('\\', '/');
                        model.loadModule(imlUrl);
                        loaded.add(moduleName);
                    } catch (Exception e) {
                        LOG.warn("Failed to load module " + moduleName + " from " + imlPath, e);
                        failed.add(moduleName);
                    }
                }

                // ---- Step B: remove any module NOT in MODULE_NAMES ----
                // This disposes the root project module that openOrImport() auto-creates.
                Set<String> expected = new HashSet<>(MODULE_NAMES);
                for (com.intellij.openapi.module.Module m : model.getModules()) {
                    if (!expected.contains(m.getName())) {
                        LOG.info("Removing unexpected module: " + m.getName());
                        model.disposeModule(m);
                        removed.add(m.getName());
                    }
                }

                model.commit();
                LOG.info("Loaded modules: " + loaded
                        + (failed.isEmpty()  ? "" : "; failed: "  + failed)
                        + (removed.isEmpty() ? "" : "; removed: " + removed));
            });
        }, ModalityState.any());

        // ---- Step C: delete any root-level .iml openOrImport() may have written ----
        // (e.g. <checkoutDir>/amos-dev-7.5.iml)
        try (java.util.stream.Stream<Path> rootFiles = Files.list(this.checkoutDir)) {
            rootFiles.filter(p -> p.toString().endsWith(".iml") && !Files.isDirectory(p))
                     .forEach(p -> {
                         try {
                             Files.delete(p);
                             LOG.info("Deleted auto-created root .iml: " + p);
                         } catch (IOException e) {
                             LOG.warn("Could not delete root .iml: " + p, e);
                         }
                     });
        } catch (IOException e) {
            LOG.warn("Could not scan checkout dir for root .iml files", e);
        }

        // ---- Step D: rewrite modules.xml to enforce the correct module list ----
        try {
            Path modulesXml = this.checkoutDir.resolve(".idea").resolve("modules.xml");
            Files.writeString(modulesXml, buildModulesXml());
            LOG.info("Rewrote .idea/modules.xml with only AMOS modules");
        } catch (IOException e) {
            LOG.warn("Could not rewrite modules.xml after module cleanup", e);
        }
    }

    // =========================================================================
    // Step 6 — Configure project settings
    // =========================================================================

    /**
     * Configures project-level settings AFTER the project is opened:
     * JDK, VCS mapping, code style, .gitignore.
     */
    private void configureProjectSettings(@NotNull Project project) {
        // --- JDK / Language Level ---
        ApplicationManager.getApplication().invokeAndWait(() -> {
            WriteAction.run(() -> {
                try {
                    ProjectRootManager prm = ProjectRootManager.getInstance(project);

                    // Try to find JDK 21 in the configured JDK table
                    Sdk jdk21 = null;
                    for (Sdk sdk : ProjectJdkTable.getInstance().getAllJdks()) {
                        String name = sdk.getName();
                        String version = sdk.getVersionString();
                        if (name.contains("21") || (version != null && version.contains("21"))) {
                            jdk21 = sdk;
                            break;
                        }
                    }

                    if (jdk21 != null) {
                        prm.setProjectSdk(jdk21);
                        LOG.info("Set project JDK to: " + jdk21.getName());
                    } else {
                        LOG.warn("No JDK 21 found in JDK table — project JDK not set. " +
                                 "Available JDKs: " + Arrays.toString(
                                     Arrays.stream(ProjectJdkTable.getInstance().getAllJdks())
                                           .map(Sdk::getName).toArray()));
                    }

                    // Set language level to JDK_21
                    com.intellij.pom.java.LanguageLevel level = com.intellij.pom.java.LanguageLevel.JDK_21;
                    com.intellij.openapi.roots.LanguageLevelProjectExtension ext =
                            com.intellij.openapi.roots.LanguageLevelProjectExtension.getInstance(project);
                    if (ext != null) {
                        ext.setLanguageLevel(level);
                        LOG.info("Set project language level to JDK_21");
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to configure JDK settings", e);
                }
            });
        }, ModalityState.any());

        // --- VCS Mapping (Git) ---
        // vcs.xml was already pre-written before openOrImport(); this API call ensures
        // the in-memory VcsManager also reflects the mapping.
        ApplicationManager.getApplication().invokeAndWait(() -> {
            try {
                ProjectLevelVcsManager vcsManager = ProjectLevelVcsManager.getInstance(project);
                List<VcsDirectoryMapping> mappings = new ArrayList<>();
                mappings.add(new VcsDirectoryMapping("", "Git"));
                vcsManager.setDirectoryMappings(mappings);
                LOG.info("Set VCS mapping to Git");
            } catch (Exception e) {
                LOG.warn("Failed to configure VCS mapping", e);
            }
        }, ModalityState.any());

        // --- Write code style (file-based, after .idea/ exists) ---
        try {
            Path ideaDir = this.checkoutDir.resolve(".idea");
            Files.createDirectories(ideaDir);

            Path codeStylesDir = ideaDir.resolve("codeStyles");
            Files.createDirectories(codeStylesDir);
            Files.writeString(codeStylesDir.resolve("codeStyleConfig.xml"), CODE_STYLE_XML);
            LOG.info("Written codeStyleConfig.xml");
        } catch (IOException e) {
            LOG.warn("Failed to write .idea code style config", e);
        }
    }

    // =========================================================================
    // Post-open verification
    // =========================================================================

    /**
     * Verifies that all expected modules were loaded by IntelliJ after opening.
     * Logs warnings for any missing modules and shows a notification if needed.
     */
    private void verifyModules(@NotNull Project project) {
        com.intellij.openapi.module.Module[] loadedModules =
                com.intellij.openapi.module.ModuleManager.getInstance(project).getModules();

        Set<String> loadedNames = new HashSet<>();
        for (com.intellij.openapi.module.Module m : loadedModules) {
            loadedNames.add(m.getName());
        }

        List<String> missing = new ArrayList<>();
        for (String expected : MODULE_NAMES) {
            if (!loadedNames.contains(expected)) {
                missing.add(expected);
            }
        }

        if (missing.isEmpty()) {
            LOG.info("All " + MODULE_NAMES.size() + " modules loaded successfully: " + loadedNames);
        } else {
            LOG.warn("Missing modules after project open: " + missing + " (loaded: " + loadedNames + ")");
            ApplicationManager.getApplication().invokeLater(() ->
                    NotificationGroupManager.getInstance()
                            .getNotificationGroup("AMOS Wizard")
                            .createNotification(
                                    "AMOS Project Wizard",
                                    "Some modules were not loaded: " + String.join(", ", missing) +
                                    "\n\nYou may need to re-import them manually via File → New → Module from Existing Sources…",
                                    NotificationType.WARNING)
                            .notify(project));
        }
    }

    // =========================================================================
    // Wait for project ready
    // =========================================================================

    /**
     * Waits until the project has finished its post-startup initialization.
     */
    private void waitForProjectReady(@NotNull Project project,
                                      @NotNull ProgressIndicator indicator) {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        com.intellij.openapi.startup.StartupManager.getInstance(project)
                .runAfterOpened(latch::countDown);
        try {
            int waited = 0;
            while (!latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (indicator.isCanceled()) return;
                waited += 500;
                if (waited >= 60_000) {
                    LOG.warn("Timed out waiting for project post-startup (60s) — continuing anyway");
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while waiting for project post-startup", e);
        }
    }

    // =========================================================================
    // Step 1 — Clone
    // =========================================================================

    /**
     * Checks whether the checkout directory already contains a valid git repo
     * on the expected branch — in which case the entire clone/resume step
     * can be skipped and we proceed straight to AMOS configuration.
     * <p>
     * A repo is considered "already checked out" when:
     * <ol>
     *   <li>{@code .git/} directory exists</li>
     *   <li>{@code git rev-parse --abbrev-ref HEAD} returns the expected branch name</li>
     *   <li>At least one of the expected module directories exists (sanity check)</li>
     * </ol>
     */
    private boolean isAlreadyCheckedOut() {
        if (!Files.isDirectory(this.checkoutDir.resolve(".git"))) return false;

        // Sanity check: at least one expected module directory should exist
        boolean hasModuleDir = MODULE_NAMES.stream()
                .anyMatch(m -> Files.isDirectory(this.checkoutDir.resolve(m)));
        if (!hasModuleDir) return false;

        // Check the current branch
        try {
            String gitExe = resolveGitExecutable();
            GeneralCommandLine cmd = new GeneralCommandLine(gitExe);
            cmd.setWorkDirectory(this.checkoutDir.toFile());
            cmd.addParameters("rev-parse", "--abbrev-ref", "HEAD");
            cmd.withEnvironment("GIT_TERMINAL_PROMPT", "0");

            CapturingProcessHandler handler = new CapturingProcessHandler(cmd);
            ProcessOutput output = handler.runProcess(10_000);
            if (output.getExitCode() == 0) {
                String currentBranch = output.getStdout().trim();
                if (this.branch.equals(currentBranch)) {
                    LOG.info("Checkout dir already on branch '" + currentBranch + "' with module dirs present");
                    return true;
                }
                LOG.info("Checkout dir on branch '" + currentBranch + "' but expected '" + this.branch + "' — will resume");
            }
        } catch (Exception e) {
            LOG.warn("Could not determine current branch — will proceed with clone/resume", e);
        }
        return false;
    }

    private boolean step1Clone(@NotNull ProgressIndicator indicator) {
        String gitExe = resolveGitExecutable();

        // ----------------------------------------------------------------
        // Credential resolution — single prompt at most.
        //
        // 1. Use constructor-provided credentials (from the wizard dialog,
        //    which reads from the OS keychain at dialog-close time).
        // 2. If those are empty, read fresh from the keychain.
        // 3. If still empty, prompt the user ONCE.
        // 4. If clone fails after that, show an error — do NOT re-prompt.
        //    Re-prompting is confusing and pointless when the same
        //    credentials already worked for branch loading.
        // ----------------------------------------------------------------
        String user = this.username;
        String pass = this.password;

        // Fallback: fresh read from keychain (constructor params may be stale)
        if (user.isEmpty() || pass.isEmpty()) {
            user = AmosCredentialStore.loadUsername();
            pass = user.isEmpty() ? "" : AmosCredentialStore.loadPassword();
        }

        // Still no credentials → prompt the user ONCE
        if (user.isEmpty() || pass.isEmpty()) {
            LOG.info("No cached credentials — prompting user before clone");
            String[] prompted = promptForCredentials();
            if (prompted == null) {
                ApplicationManager.getApplication().invokeLater(() ->
                        Messages.showErrorDialog(
                                "Git clone requires authentication.\nPlease provide your credentials.",
                                "Clone Cancelled"), ModalityState.any());
                return false;
            }
            user = prompted[0];
            pass = prompted[1];
        }

        // ------------------------------------------------------------------
        // Decide which git strategy to use:
        //
        //  A) .git/ exists  → resume: git fetch + git checkout -B
        //     (handles interrupted / partial clones from a previous run)
        //
        //  B) directory exists but no .git/  → init+fetch: git init, remote add,
        //     git fetch, git checkout -B.
        //     This is the normal case after createEmptyProject() pre-creates the
        //     checkout dir with .idea/ — git clone refuses to run into a
        //     non-empty directory, so we must seed the repo ourselves.
        //
        //  C) directory does not exist  → traditional git clone
        //     (legacy / fallback path, kept for safety)
        // ------------------------------------------------------------------
        boolean isResume   = Files.isDirectory(this.checkoutDir.resolve(".git"));
        boolean dirExists  = Files.isDirectory(this.checkoutDir);
        boolean useInitFetch = !isResume && dirExists;

        if (isResume) {
            LOG.info("Existing .git found — resuming (fetch + checkout) for branch " + this.branch
                     + " (user=" + user + ")");
        } else if (useInitFetch) {
            LOG.info("Project dir exists (pre-created) but no .git — using init+fetch strategy for branch "
                     + this.branch + " (user=" + user + ")");
        } else {
            LOG.info("Fresh clone (user=" + user + ")");
        }

        ProcessOutput output;
        if (isResume) {
            output = runResume(gitExe, user, pass, indicator);
        } else if (useInitFetch) {
            output = runInitAndFetch(gitExe, user, pass, indicator);
        } else {
            output = runClone(gitExe, user, pass, indicator);
        }

        if (output == null) return false;   // exception — already reported
        if (output.isCancelled() || indicator.isCanceled()) return false;

        if (output.getExitCode() != 0) {
            // Genuine failure — report the error.
            // Do NOT delete the checkout dir: it may contain a valid partial clone
            // that the user can retry later.
            String stderr   = output.getStderr().trim();
            String stdout   = output.getStdout().trim();
            String errorMsg = stderr.isEmpty() ? stdout : stderr;
            if (errorMsg.isEmpty()) errorMsg = "git exited with code " + output.getExitCode();

            String opLabel = isResume ? "resume" : (useInitFetch ? "init+fetch" : "clone");
            LOG.warn(opLabel + " failed (exit " + output.getExitCode() + "): " + errorMsg);
            final String errorMsgFinal = errorMsg;
            final String title = isResume ? "Resume Failed" : "Clone Failed";
            ApplicationManager.getApplication().invokeLater(() ->
                    Messages.showErrorDialog("Git " + opLabel + " failed:\n\n"
                            + errorMsgFinal, title),
                    ModalityState.any());
            return false;
        }


        return true;
    }

    /**
     * Shows the credentials dialog on the EDT and waits for the result.
     * Uses {@link ModalityState#any()} so the dialog appears even while the
     * progress indicator (modal) is on screen.
     *
     * @return {@code [username, password]} or {@code null} if the user cancelled
     */
    @Nullable
    private String[] promptForCredentials() {
        final String[] creds = {null, null};
        ApplicationManager.getApplication().invokeAndWait(() -> {
            AmosCredentialsDialog dlg = new AmosCredentialsDialog();
            if (dlg.showAndGet()) {
                creds[0] = dlg.getUsername();
                creds[1] = dlg.getPassword();
                // Save immediately — AmosCredentialsDialog.doOKAction already saves,
                // but we also save here in case the dialog implementation changes.
                AmosCredentialStore.save(creds[0], creds[1]);
            }
        }, ModalityState.any());
        return (creds[0] != null && !creds[0].isEmpty()) ? creds : null;
    }

    /**
     * Deletes {@code dir} and all its contents silently (best-effort).
     * Used to clean up a partial clone before retrying.
     */
    private static void deleteDirectoryQuietly(@NotNull Path dir) {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                  .forEach(p -> {
                      try { Files.delete(p); } catch (IOException ignored) { }
                  });
        } catch (IOException ignored) {
            LOG.warn("Could not fully delete partial clone dir: " + dir);
        }
    }

    /**
     * Builds and executes {@code git clone} with the given credentials.
     * <p>
     * Credentials are injected by embedding them directly in the clone URL
     * ({@code https://user:pass@host/repo.git}) via
     * {@link AmosCredentialStore#injectCredentials}.  This is the most reliable
     * method — it works with every git version, every TLS backend (SChannel,
     * OpenSSL), and survives HTTP redirects (unlike {@code http.extraHeader}).
     * <p>
     * All credential helpers and interactive prompts are aggressively disabled
     * via environment variables so git can <b>never</b> block waiting for input:
     * <ul>
     *   <li>{@code GIT_TERMINAL_PROMPT=0} — disables built-in terminal prompt</li>
     *   <li>{@code GCM_INTERACTIVE=never} — disables Git Credential Manager GUI dialog</li>
     *   <li>{@code GIT_ASKPASS=} (empty) — prevents askpass helper scripts</li>
     *   <li>{@code SSH_ASKPASS=} (empty) — prevents SSH askpass dialogs</li>
     *   <li>{@code -c credential.helper=} — clears the credential helper chain</li>
     * </ul>
     *
     * @return the process output, or {@code null} if the process could not be started
     */
    @Nullable
    private ProcessOutput runClone(@NotNull String gitExe,
                                    @NotNull String user,
                                    @NotNull String pass,
                                    @NotNull ProgressIndicator indicator) {

        // Build the authenticated URL (credentials embedded in the URL itself).
        String cloneUrl = AmosCredentialStore.injectCredentials(this.repoUrl, user, pass);
        String authHeader = AmosCredentialStore.basicAuthHeaderValue(user, pass);

        GeneralCommandLine cmd = buildGitCmd(gitExe, authHeader);

        cmd.addParameters("clone",
                          "--branch", this.branch,
                          "--single-branch",  // only fetch the selected branch — much faster for monorepos
                          "--origin", "origin",
                          "--progress",       // force progress output even when stderr is not a TTY
                          cloneUrl, this.checkoutDir.toString());

        cmd.setRedirectErrorStream(false);

        LOG.info("Clone command: git clone --single-branch --branch " + this.branch + " " + this.repoUrl
                 + " → " + this.checkoutDir + " (credentials " + (user.isEmpty() ? "NONE" : "provided") + ")");

        return runGitProcess(cmd, indicator, CLONE_TIMEOUT_MS, "Clone");
    }

    // =========================================================================
    // Init+Fetch: seed an existing directory as a new git repo
    // =========================================================================

    /**
     * Initialises {@code checkoutDir} as a new Git repository and fetches the
     * target branch.  Used when the directory was pre-created by
     * {@link #createEmptyProject()} and already contains {@code .idea/}, which
     * prevents {@code git clone} from running (git refuses to clone into a
     * non-empty directory).
     * <p>
     * Equivalent steps:
     * <ol>
     *   <li>{@code git init}</li>
     *   <li>{@code git remote add origin <url>}</li>
     *   <li>{@code git fetch origin <branch> --depth=... --progress}</li>
     *   <li>{@code git checkout -B <branch> origin/<branch>}</li>
     * </ol>
     *
     * @return the process output of the last command that ran,
     *         or {@code null} if a process could not be started
     */
    @Nullable
    private ProcessOutput runInitAndFetch(@NotNull String gitExe,
                                          @NotNull String user,
                                          @NotNull String pass,
                                          @NotNull ProgressIndicator indicator) {
        String authHeader = AmosCredentialStore.basicAuthHeaderValue(user, pass);
        String fetchUrl   = AmosCredentialStore.injectCredentials(this.repoUrl, user, pass);

        // ---- Step 1: git init ----
        indicator.setText2("Initialising git repository…");
        {
            GeneralCommandLine initCmd = buildGitCmd(gitExe, authHeader);
            initCmd.setWorkDirectory(this.checkoutDir.toFile());
            initCmd.addParameter("init");
            ProcessOutput initOut = runGitProcess(initCmd, indicator, 30_000, "Init");
            if (initOut == null) return null;
            if (initOut.getExitCode() != 0 || initOut.isCancelled() || indicator.isCanceled()) {
                return initOut;
            }
        }

        // ---- Step 2: git remote add origin <url> ----
        indicator.setText2("Adding remote origin…");
        {
            GeneralCommandLine remoteCmd = buildGitCmd(gitExe, authHeader);
            remoteCmd.setWorkDirectory(this.checkoutDir.toFile());
            remoteCmd.addParameters("remote", "add", "origin", fetchUrl);
            ProcessOutput remoteOut = runGitProcess(remoteCmd, indicator, 30_000, "Remote add");
            if (remoteOut == null) return null;
            if (remoteOut.getExitCode() != 0 || remoteOut.isCancelled() || indicator.isCanceled()) {
                return remoteOut;
            }
        }

        // ---- Step 3: git fetch origin <branch> --progress ----
        indicator.setText2("Fetching branch " + this.branch + " from remote…");
        {
            GeneralCommandLine fetchCmd = buildGitCmd(gitExe, authHeader);
            fetchCmd.setWorkDirectory(this.checkoutDir.toFile());
            fetchCmd.addParameters("fetch", "origin", this.branch, "--progress");
            fetchCmd.setRedirectErrorStream(false);
            ProcessOutput fetchOut = runGitProcess(fetchCmd, indicator, FETCH_TIMEOUT_MS, "Fetch");
            if (fetchOut == null) return null;
            if (fetchOut.getExitCode() != 0 || fetchOut.isCancelled() || indicator.isCanceled()) {
                return fetchOut;
            }
        }

        // ---- Step 4: git checkout -B <branch> origin/<branch> ----
        indicator.setText2("Checking out branch " + this.branch + "…");
        {
            GeneralCommandLine checkoutCmd = buildGitCmd(gitExe, authHeader);
            checkoutCmd.setWorkDirectory(this.checkoutDir.toFile());
            checkoutCmd.addParameters("checkout", "-B", this.branch, "origin/" + this.branch);
            checkoutCmd.setRedirectErrorStream(false);
            return runGitProcess(checkoutCmd, indicator, CHECKOUT_TIMEOUT_MS, "Checkout");
        }
    }

    // =========================================================================
    // Resume: git fetch + git checkout (for existing partial clones)
    // =========================================================================

    /**
     * Resumes an interrupted clone by running {@code git fetch} followed by
     * {@code git checkout <branch>} inside the existing checkout directory.
     * <p>
     * The same credential / prompt-suppression strategy as {@link #runClone} is used.
     *
     * @return the process output of the <b>last</b> command that ran,
     *         or {@code null} if the process could not be started
     */
    @Nullable
    private ProcessOutput runResume(@NotNull String gitExe,
                                    @NotNull String user,
                                    @NotNull String pass,
                                    @NotNull ProgressIndicator indicator) {

        String authHeader = AmosCredentialStore.basicAuthHeaderValue(user, pass);
        String cloneUrl   = AmosCredentialStore.injectCredentials(this.repoUrl, user, pass);

        // --- Step 1: ensure the remote URL is correct (may have changed) ---
        try {
            GeneralCommandLine setUrl = buildGitCmd(gitExe, authHeader);
            setUrl.setWorkDirectory(this.checkoutDir.toFile());
            setUrl.addParameters("remote", "set-url", "origin", cloneUrl);
            new CapturingProcessHandler(setUrl).runProcess(30_000);
        } catch (Exception e) {
            LOG.warn("Could not set remote URL — continuing anyway", e);
        }

        // --- Step 2: git fetch (only the target branch) ---
        indicator.setText2("Fetching from remote…");
        {
            GeneralCommandLine fetchCmd = buildGitCmd(gitExe, authHeader);
            fetchCmd.setWorkDirectory(this.checkoutDir.toFile());
            fetchCmd.addParameters("fetch", "origin", this.branch, "--progress");
            fetchCmd.setRedirectErrorStream(false);

            ProcessOutput fetchOutput = runGitProcess(fetchCmd, indicator, FETCH_TIMEOUT_MS, "Fetch");
            if (fetchOutput == null) return null;
            if (fetchOutput.getExitCode() != 0 || fetchOutput.isCancelled() || indicator.isCanceled()) {
                return fetchOutput;
            }
        }

        // --- Step 3: git checkout <branch> ---
        indicator.setText2("Checking out branch " + this.branch + "…");
        {
            GeneralCommandLine checkoutCmd = buildGitCmd(gitExe, authHeader);
            checkoutCmd.setWorkDirectory(this.checkoutDir.toFile());
            // Use -B so it works whether or not the local branch already exists
            checkoutCmd.addParameters("checkout", "-B", this.branch, "origin/" + this.branch);
            checkoutCmd.setRedirectErrorStream(false);

            return runGitProcess(checkoutCmd, indicator, CHECKOUT_TIMEOUT_MS, "Checkout");
        }
    }

    /**
     * Creates a {@link GeneralCommandLine} pre-configured with credential suppression
     * environment variables, LFS skip, and an optional {@code http.extraHeader}.
     * <p>
     * {@code GIT_LFS_SKIP_SMUDGE=1} is set so that git never invokes the LFS smudge
     * filter during clone/fetch/checkout — LFS-tracked files are left as pointer files.
     */
    @NotNull
    private static GeneralCommandLine buildGitCmd(@NotNull String gitExe,
                                                   @Nullable String authHeader) {
        GeneralCommandLine cmd = new GeneralCommandLine(gitExe);
        cmd.addParameter("-c");
        cmd.addParameter("credential.helper=");
        if (authHeader != null) {
            cmd.addParameter("-c");
            cmd.addParameter("http.extraHeader=Authorization: " + authHeader);
        }
        cmd.withEnvironment("GIT_TERMINAL_PROMPT", "0");
        cmd.withEnvironment("GCM_INTERACTIVE",     "never");
        cmd.withEnvironment("GIT_GCM_INTERACTIVE", "never");
        cmd.withEnvironment("GIT_ASKPASS",          "");
        cmd.withEnvironment("SSH_ASKPASS",          "");
        // Skip LFS smudge filter — prevents clone/checkout failures on LFS-tracked files
        cmd.withEnvironment("GIT_LFS_SKIP_SMUDGE", "1");
        return cmd;
    }

    /**
     * Runs a git command using {@link OSProcessHandler} which <b>streams</b> output
     * instead of buffering it all in RAM (as {@link CapturingProcessHandler} does).
     * <p>
     * For large operations like {@code git clone}, the progress output (object counting,
     * receiving objects, resolving deltas) can produce hundreds of megabytes of text.
     * Using a capturing handler would cause extreme memory pressure or OOM.
     * <p>
     * Only the <b>last few lines</b> of stderr are retained for error reporting purposes.
     *
     * @param cmd       the git command to run
     * @param indicator progress indicator — updated with each line of output; cancellation is respected
     * @param timeoutMs maximum time to wait (milliseconds)
     * @param label     human-readable label for error dialogs (e.g. "Clone", "Fetch")
     * @return a synthetic {@link ProcessOutput} with exit code and last stderr lines,
     *         or {@code null} if the process could not be started
     */
    @Nullable
    private ProcessOutput runGitProcess(@NotNull GeneralCommandLine cmd,
                                        @NotNull ProgressIndicator indicator,
                                        int timeoutMs,
                                        @NotNull String label) {
        try {
            OSProcessHandler handler = new OSProcessHandler(cmd);

            // Circular buffer: keep only the last N lines of stderr for error reporting
            final int MAX_STDERR_LINES = 50;
            final java.util.LinkedList<String> stderrTail = new java.util.LinkedList<>();
            final StringBuilder lastStdout = new StringBuilder();

            handler.addProcessListener(new ProcessAdapter() {
                @Override
                public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                    String text = event.getText();
                    if (text == null || text.isEmpty()) return;
                    String trimmed = text.trim();

                    // Update progress indicator with latest line
                    if (!trimmed.isEmpty()) {
                        indicator.setText2(trimmed);
                    }

                    // Keep last lines of stderr for error reporting
                    if (outputType == ProcessOutputTypes.STDERR) {
                        synchronized (stderrTail) {
                            stderrTail.add(text);
                            while (stderrTail.size() > MAX_STDERR_LINES) {
                                stderrTail.removeFirst();
                            }
                        }
                    } else if (outputType == ProcessOutputTypes.STDOUT) {
                        synchronized (lastStdout) {
                            // Only keep last 4KB of stdout
                            if (lastStdout.length() > 4096) {
                                lastStdout.delete(0, lastStdout.length() - 2048);
                            }
                            lastStdout.append(text);
                        }
                    }
                }
            });

            handler.startNotify();

            // Poll-wait so we can respect cancellation
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!handler.waitFor(2000)) {
                if (indicator.isCanceled()) {
                    LOG.info(label + " cancelled by user — destroying process");
                    handler.destroyProcess();
                    ProcessOutput cancelled = new ProcessOutput();
                    cancelled.setCancelled();
                    return cancelled;
                }
                if (System.currentTimeMillis() > deadline) {
                    LOG.warn(label + " timed out after " + (timeoutMs / 1000) + "s — destroying process");
                    handler.destroyProcess();
                    ProcessOutput timedOut = new ProcessOutput();
                    timedOut.setCancelled();
                    return timedOut;
                }
            }

            // Build a ProcessOutput from what we collected
            int exitCode = handler.getExitCode() != null ? handler.getExitCode() : -1;
            String stderr;
            synchronized (stderrTail) {
                stderr = String.join("", stderrTail);
            }
            String stdout;
            synchronized (lastStdout) {
                stdout = lastStdout.toString();
            }

            ProcessOutput result = new ProcessOutput(stdout, stderr, exitCode, false, false);
            return result;

        } catch (Exception e) {
            LOG.error("Exception starting git " + label.toLowerCase(), e);
            final String msg = e.getMessage();
            ApplicationManager.getApplication().invokeLater(() ->
                    Messages.showErrorDialog("Could not start git " + label.toLowerCase()
                            + ": " + msg, label + " Failed"),
                    ModalityState.any());
            return null;
        }
    }

    // =========================================================================
    // Step 5 — Copy Run Configurations
    // =========================================================================

    private void step5CopyRunConfigs() {
        assert this.sourceProjectPath != null;

        Path srcRunConfigs = this.sourceProjectPath.resolve(".idea").resolve("runConfigurations");
        if (!Files.exists(srcRunConfigs) || !Files.isDirectory(srcRunConfigs)) {
            LOG.info("No runConfigurations directory found in source project — skipping");
            return;
        }

        Path destRunConfigs = this.checkoutDir.resolve(".idea").resolve("runConfigurations");
        try {
            Files.createDirectories(destRunConfigs);
        } catch (IOException e) {
            LOG.warn("Could not create runConfigurations directory: " + destRunConfigs, e);
            return;
        }

        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(srcRunConfigs, "*.xml")) {
            for (Path srcFile : stream) {
                try {
                    String content = Files.readString(srcFile);
                    content = substituteProjectDir(content);
                    Path destFile = destRunConfigs.resolve(srcFile.getFileName());
                    Files.writeString(destFile, content);
                    LOG.info("Copied run config: " + srcFile.getFileName());
                } catch (IOException e) {
                    LOG.warn("Failed to copy run config " + srcFile.getFileName(), e);
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to list run configurations in " + srcRunConfigs, e);
        }
    }

    // =========================================================================
    // Step 6 — Copy Compiler Settings
    // =========================================================================

    private void step6CopyCompilerSettings() {
        assert this.sourceProjectPath != null;

        String[] configFiles = {"compiler.xml", "java-compiler.xml", "encodings.xml"};
        Path srcIdeaDir = this.sourceProjectPath.resolve(".idea");
        Path destIdeaDir = this.checkoutDir.resolve(".idea");

        for (String fileName : configFiles) {
            Path srcFile = srcIdeaDir.resolve(fileName);
            if (!Files.exists(srcFile)) {
                // Silently skip — not an error
                continue;
            }
            try {
                String content = Files.readString(srcFile);
                content = substituteProjectDir(content);
                Files.writeString(destIdeaDir.resolve(fileName), content);
                LOG.info("Copied compiler config: " + fileName);
            } catch (IOException e) {
                LOG.warn("Failed to copy compiler setting " + fileName, e);
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Replaces all occurrences of the source project root path with {@code $PROJECT_DIR$}
     * in the given XML content, handling both OS-native separators and forward slashes.
     */
    private String substituteProjectDir(@NotNull String content) {
        assert this.sourceProjectPath != null;
        // Use replaceAll with Pattern.quote so special regex characters in the path are escaped.
        // The replacement "\\$PROJECT_DIR\\$" produces the literal string "$PROJECT_DIR$".
        String nativePath = this.sourceProjectPath.toString();
        String forwardSlashPath = nativePath.replace('\\', '/');

        content = content.replaceAll(Pattern.quote(nativePath), "\\$PROJECT_DIR\\$");
        if (!forwardSlashPath.equals(nativePath)) {
            content = content.replaceAll(Pattern.quote(forwardSlashPath), "\\$PROJECT_DIR\\$");
        }
        return content;
    }

    /**
     * Returns the git executable path from IntelliJ's VCS settings.
     * Falls back to plain {@code "git"} so behaviour is unchanged on systems where
     * the IDE process already has git on its PATH.
     */
    @NotNull
    private static String resolveGitExecutable() {
        try {
            String path = GitExecutableManager.getInstance().getPathToGit(null);
            if (path != null && !path.isEmpty()) {
                return path;
            }
        } catch (Exception ignored) {
        }
        return "git";
    }
}

