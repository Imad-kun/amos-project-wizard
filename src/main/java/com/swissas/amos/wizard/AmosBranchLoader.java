package com.swissas.amos.wizard;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.ComboBox;
import git4idea.config.GitExecutableManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Loads remote AMOS branches matching the {@code dev-X.Y} pattern via {@code git ls-remote}.
 */
public class AmosBranchLoader {

    private static final Logger LOG = Logger.getInstance(AmosBranchLoader.class);
    private static final Pattern BRANCH_PATTERN = Pattern.compile("^dev-\\d+\\.\\d+$");
    private static final String REFS_HEADS_PREFIX = "refs/heads/";

    private AmosBranchLoader() {
    }

    /**
     * Synchronously fetches branch names from the remote (no credentials).
     * Must NOT be called on the EDT.
     *
     * @throws Exception with a human-readable message when git fails or cannot be found
     */
    @NotNull
    public static List<String> loadBranches(@NotNull String repoUrl) throws Exception {
        return loadBranches(repoUrl, "", "");
    }

    /**
     * Synchronously fetches branch names from the remote using the supplied credentials.
     * Must NOT be called on the EDT.
     *
     * @throws Exception with a human-readable message when git fails or cannot be found
     */
    @NotNull
    public static List<String> loadBranches(@NotNull String repoUrl,
                                             @NotNull String username,
                                             @NotNull String password) throws Exception {
        // Use the git executable path configured in IntelliJ's VCS settings so the command
        // works even when 'git' is not on the IDE process PATH (common on Windows).
        String gitExe = resolveGitExecutable();

        GeneralCommandLine cmd = new GeneralCommandLine(gitExe);

        // Disable ALL credential helpers so nothing can pop up a dialog or prompt.
        cmd.addParameter("-c");
        cmd.addParameter("credential.helper=");

        // Send Authorization header explicitly — more reliable than URL-embedded
        // credentials for some GitLab configurations and reverse-proxy setups.
        String authHeader = AmosCredentialStore.basicAuthHeaderValue(username, password);
        if (authHeader != null) {
            cmd.addParameter("-c");
            cmd.addParameter("http.extraHeader=Authorization: " + authHeader);
        }

        // Embed credentials directly in the URL as well (belt-and-suspenders).
        String effectiveUrl = AmosCredentialStore.injectCredentials(repoUrl, username, password);

        cmd.addParameters("ls-remote", "--heads", effectiveUrl);
        cmd.setRedirectErrorStream(false);
        // Prevent git from blocking on an interactive terminal prompt
        cmd.withEnvironment("GIT_TERMINAL_PROMPT", "0");
        // Git Credential Manager for Windows/macOS/Linux — prevent GUI popup
        cmd.withEnvironment("GCM_INTERACTIVE",     "never");
        cmd.withEnvironment("GIT_GCM_INTERACTIVE", "never");
        // Prevent askpass helper scripts
        cmd.withEnvironment("GIT_ASKPASS",          "");
        cmd.withEnvironment("SSH_ASKPASS",          "");

        // CapturingProcessHandler constructor throws ExecutionException if 'git' is not found
        CapturingProcessHandler handler = new CapturingProcessHandler(cmd);
        ProcessOutput output = handler.runProcess(30_000);

        if (output.isTimeout()) {
            throw new RuntimeException("git ls-remote timed out after 30 seconds");
        }
        if (output.getExitCode() != 0) {
            String stderr = output.getStderr().trim();
            String msg = stderr.isEmpty()
                    ? "git ls-remote exited with code " + output.getExitCode()
                    : stderr;
            LOG.warn("git ls-remote failed (exit " + output.getExitCode() + "): " + msg);
            throw new RuntimeException(msg);
        }

        List<String> result = new ArrayList<>();
        for (String line : output.getStdoutLines()) {
            // Each line: <sha>\trefs/heads/<branch>
            int tab = line.indexOf('\t');
            if (tab < 0) continue;
            String ref = line.substring(tab + 1).trim();
            if (!ref.startsWith(REFS_HEADS_PREFIX)) continue;
            String branchName = ref.substring(REFS_HEADS_PREFIX.length());
            if (BRANCH_PATTERN.matcher(branchName).matches()) {
                result.add(branchName);
            }
        }

        // Sort descending by version parts: dev-25.12 > dev-25.6 > dev-24.12
        result.sort(Comparator.comparingInt((String b) -> majorVersion(b))
                              .thenComparingInt(AmosBranchLoader::minorVersion)
                              .reversed());
        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the path to the git executable as configured in IntelliJ's VCS settings.
     * Falls back to plain {@code "git"} if the API is unavailable.
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

    private static int majorVersion(@NotNull String branch) {
        // branch like "dev-25.12" → 25
        String[] parts = versionParts(branch);
        if (parts == null) return 0;
        try { return Integer.parseInt(parts[0]); } catch (NumberFormatException e) { return 0; }
    }

    private static int minorVersion(@NotNull String branch) {
        // branch like "dev-25.12" → 12
        String[] parts = versionParts(branch);
        if (parts == null) return 0;
        try { return Integer.parseInt(parts[1]); } catch (NumberFormatException e) { return 0; }
    }

    @org.jetbrains.annotations.Nullable
    private static String[] versionParts(@NotNull String branch) {
        String ver = branch.replace("dev-", "");
        String[] parts = ver.split("\\.", 2);
        return parts.length == 2 ? parts : null;
    }

    /**
     * Asynchronously loads branches (no credentials) and populates the given combo box on the EDT.
     */
    public static void loadBranchesAsync(@NotNull String repoUrl,
                                          @NotNull ComboBox<String> comboBox,
                                          @NotNull Consumer<String> onError) {
        loadBranchesAsync(repoUrl, "", "", comboBox, onError);
    }

    /**
     * Asynchronously loads branches using the supplied credentials and populates the combo.
     * <p>Shows a "Loading branches…" placeholder immediately, then either populates the
     * list on success or clears the combo and invokes {@code onError} on failure.</p>
     *
     * @param repoUrl  remote URL
     * @param username git username / GitLab login (may be empty)
     * @param password git password or personal-access-token (may be empty)
     * @param comboBox combo box to populate
     * @param onError  callback invoked on the EDT with an error message if loading fails
     */
    public static void loadBranchesAsync(@NotNull String repoUrl,
                                          @NotNull String username,
                                          @NotNull String password,
                                          @NotNull ComboBox<String> comboBox,
                                          @NotNull Consumer<String> onError) {
        // ModalityState.any() is REQUIRED here because this method is called during
        // createCenterPanel() (i.e. before showAndGet() opens the modal dialog).
        // IntelliJ's invokeLater silently drops callbacks whose captured modality state
        // is lower than the currently-active modal context.  Using ModalityState.any()
        // guarantees the EDT callbacks execute regardless of which dialog is on screen.
        ModalityState modality = ModalityState.any();

        // Show a placeholder immediately so the user sees activity
        ApplicationManager.getApplication().invokeLater(() -> {
            comboBox.removeAllItems();
            comboBox.addItem("Loading branches…");
            comboBox.setSelectedIndex(0);
            comboBox.setEnabled(false);
        }, modality);

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                List<String> branches = loadBranches(repoUrl, username, password);
                ApplicationManager.getApplication().invokeLater(() -> {
                    Object current = comboBox.getEditor().getItem();
                    // Don't keep the placeholder as "current"
                    String currentStr = (current != null) ? current.toString() : "";
                    if (currentStr.equals("Loading branches…")) currentStr = "";

                    comboBox.removeAllItems();
                    for (String b : branches) {
                        comboBox.addItem(b);
                    }
                    comboBox.setEnabled(true);

                    if (!currentStr.isEmpty()) {
                        comboBox.setSelectedItem(currentStr);
                    } else if (!branches.isEmpty()) {
                        comboBox.setSelectedIndex(0);
                    }

                    if (branches.isEmpty()) {
                        onError.accept("No dev-X.Y branches found in the repository");
                    }
                }, modality);
            } catch (Exception ex) {
                LOG.warn("Async branch loading error for " + repoUrl, ex);
                String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                ApplicationManager.getApplication().invokeLater(() -> {
                    comboBox.removeAllItems();
                    comboBox.setEnabled(true);
                    onError.accept(msg);
                }, modality);
            }
        });
    }


    /**
     * Returns {@code true} when the error message looks like a Git authentication failure.
     * Used by the wizard to decide whether to show the credentials dialog.
     */
    public static boolean isAuthError(@NotNull String message) {
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("authentication failed")
                || lower.contains("invalid username or password")
                || lower.contains("could not read username")
                || lower.contains("could not read password")
                || lower.contains("http basic: access denied")
                || lower.contains("403")
                || lower.contains("401")
                || lower.contains("access denied")
                || lower.contains("unable to access");
    }
}

