package com.swissas.amos.wizard;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;

/**
 * Wizard dialog: collects user inputs for the AMOS project clone + import operation.
 * <p>
 * Credentials are NOT shown here.  They are requested automatically, on demand,
 * via {@link AmosCredentialsDialog} the first time a branch-load attempt returns an
 * authentication error.  Once stored in the OS keychain they are reused silently.
 */
public class AmosProjectWizardDialog extends DialogWrapper {

    private static final String DEFAULT_REPO_URL = "https://gitlab.swiss-as.com/amos/amos.git";

    private static final java.util.regex.Pattern BRANCH_PATTERN =
            java.util.regex.Pattern.compile("^dev-\\d+\\.\\d+$");

    /**
     * Set to {@code false} the moment the dialog is cancelled or disposed.
     * Every EDT callback checks this flag before touching UI or showing new dialogs.
     */
    private volatile boolean active = true;

    /** Debounce timer — branch reload fires 800 ms after the user stops typing the URL. */
    private Timer repoUrlDebounceTimer;

    // --- UI components ---
    private JTextField             repoUrlField;
    private ComboBox<String>       branchCombo;
    private JLabel                 branchStatusLabel;   // gray hints + red errors
    private TextFieldWithBrowseButton localRootField;
    private JTextField             projectNameField;
    private JLabel                 checkoutDirLabel;
    private TextFieldWithBrowseButton sourceProjectField;

    public AmosProjectWizardDialog() {
        super(true);
        setTitle("New AMOS Project from Branch");
        init();
    }

    // -------------------------------------------------------------------------
    // Dialog lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void dispose() {
        active = false;                          // guard all pending EDT callbacks
        if (repoUrlDebounceTimer != null) {
            repoUrlDebounceTimer.stop();         // cancel any pending debounce
        }
        super.dispose();
    }

    // -------------------------------------------------------------------------
    // Panel construction
    // -------------------------------------------------------------------------

    @Override
    protected @Nullable JComponent createCenterPanel() {
        AmosPluginSettings settings = AmosPluginSettings.getInstance();

        // ---- Git Repository URL ----
        repoUrlField = new JTextField();
        String savedUrl = settings.getLastRepoUrl();
        repoUrlField.setText(savedUrl.isEmpty() ? DEFAULT_REPO_URL : savedUrl);

        // Debounce: restart timer on every keystroke; fires triggerBranchLoad() after 800 ms.
        repoUrlDebounceTimer = new Timer(800, (ActionEvent e) -> triggerBranchLoad());
        repoUrlDebounceTimer.setRepeats(false);

        repoUrlField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { repoUrlDebounceTimer.restart(); }
            @Override public void removeUpdate(DocumentEvent e)  { repoUrlDebounceTimer.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { repoUrlDebounceTimer.restart(); }
        });

        // ---- Branch combo + refresh button ----
        branchCombo = new ComboBox<>();
        branchCombo.setEditable(true);

        branchStatusLabel = new JLabel(" ");
        branchStatusLabel.setFont(branchStatusLabel.getFont().deriveFont(Font.ITALIC, 11f));

        JButton refreshButton = new JButton("\u21BA"); // ↺
        refreshButton.setToolTipText("Refresh branch list from remote");
        refreshButton.addActionListener(e -> triggerBranchLoad());


        // ---- Local root ----
        localRootField = new TextFieldWithBrowseButton();
        localRootField.setText(settings.getLastLocalRoot());
        //noinspection removal
        localRootField.addBrowseFolderListener(
                "Select Local Root Directory", null, null,
                FileChooserDescriptorFactory.createSingleFolderDescriptor());

        // ---- Derived fields ----
        projectNameField = new JTextField();
        projectNameField.setToolTipText("Auto-derived from the branch name. You can override it.");
        checkoutDirLabel = new JLabel(" ");

        // Live-update checkout dir whenever the user edits the project name manually
        projectNameField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { updateCheckoutDir(); }
            @Override public void removeUpdate(DocumentEvent e)  { updateCheckoutDir(); }
            @Override public void changedUpdate(DocumentEvent e) { updateCheckoutDir(); }
        });

        // ---- Source project ----
        sourceProjectField = new TextFieldWithBrowseButton();
        sourceProjectField.setText(settings.getLastSourceProjectPath());
        //noinspection removal
        sourceProjectField.addBrowseFolderListener(
                "Select Source AMOS Project", null, null,
                FileChooserDescriptorFactory.createSingleFolderDescriptor());

        // Live-update: branch text → project name + checkout dir
        //              local-root text → checkout dir only (don't reset a manually-edited project name)
        DocumentListener branchUpdate = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { updateDerivedFields(); }
            @Override public void removeUpdate(DocumentEvent e)  { updateDerivedFields(); }
            @Override public void changedUpdate(DocumentEvent e) { updateDerivedFields(); }
        };
        DocumentListener rootUpdate = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { updateCheckoutDir(); }
            @Override public void removeUpdate(DocumentEvent e)  { updateCheckoutDir(); }
            @Override public void changedUpdate(DocumentEvent e) { updateCheckoutDir(); }
        };
        ((JTextField) branchCombo.getEditor().getEditorComponent())
                .getDocument().addDocumentListener(branchUpdate);
        localRootField.getTextField().getDocument().addDocumentListener(rootUpdate);
        branchCombo.addItemListener(e -> updateDerivedFields());

        updateDerivedFields();

        // Silent initial probe with whatever credentials are currently stored.
        // Does NOT open the credentials dialog — the user hasn't requested anything yet.
        silentProbe(repoUrlField.getText().trim());

        // ---- Layout ----
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints lc = labelConstraints();
        GridBagConstraints fc = fieldConstraints();
        int row = 0;

        addRow(panel, lc, fc, row++, "Git Repository URL:", repoUrlField);

        JPanel branchRow = new JPanel(new BorderLayout(4, 0));
        branchRow.add(branchCombo,    BorderLayout.CENTER);
        branchRow.add(refreshButton,  BorderLayout.EAST);
        addRow(panel, lc, fc, row++, "Branch:", branchRow);

        // Status label spans the field column
        fc.gridy = row++; fc.gridx = 1;
        panel.add(branchStatusLabel, fc);

        addRow(panel, lc, fc, row++, "Local Root Directory:",    localRootField);
        addRow(panel, lc, fc, row++, "Project Name:",            projectNameField);
        addRow(panel, lc, fc, row++, "Checkout Directory:",      checkoutDirLabel);
        addRow(panel, lc, fc, row++, "Source Project for Config:", sourceProjectField);

        panel.setPreferredSize(new Dimension(680, panel.getPreferredSize().height + 20));
        return panel;
    }


    // -------------------------------------------------------------------------
    // Branch loading — three-phase logic
    // -------------------------------------------------------------------------

    /**
     * Phase 1 — silent probe on dialog open.
     * Uses stored credentials (or anonymous).  If it succeeds: populate combo.
     * If auth error: show a non-intrusive gray lock hint.  Never shows a modal dialog.
     */
    private void silentProbe(@NotNull String url) {
        if (url.isEmpty()) return;
        String username = AmosCredentialStore.loadUsername();
        String password = username.isEmpty() ? "" : AmosCredentialStore.loadPassword();
        doLoadAsync(url, username, password, false /*isRetry*/);
    }

    /**
     * Phase 2 — user-triggered load (↺ button OR URL debounce).
     * <ol>
     *   <li>First tries with stored credentials (or anonymous).</li>
     *   <li>On auth error: opens {@link AmosCredentialsDialog}.</li>
     *   <li>If user cancels credentials dialog: shows gray hint, nothing more.</li>
     *   <li>If user confirms: saves to keychain, retries once (Phase 3).</li>
     * </ol>
     */
    private void triggerBranchLoad() {
        if (!active) return;
        clearStatus();
        String url = repoUrlField.getText().trim();
        if (url.isEmpty()) return;

        String username = AmosCredentialStore.loadUsername();
        String password = username.isEmpty() ? "" : AmosCredentialStore.loadPassword();

        AmosBranchLoader.loadBranchesAsync(url, username, password, branchCombo, errMsg -> {
            if (!active) return;  // main dialog was cancelled while loading

            if (AmosBranchLoader.isAuthError(errMsg)) {
                // Auth is needed — open the credentials dialog
                AmosCredentialsDialog credsDialog = new AmosCredentialsDialog();
                if (!active) return;  // cancelled while we were about to show the dialog

                if (credsDialog.showAndGet()) {
                    // User supplied credentials — retry once (Phase 3)
                    retryWithCredentials(url, credsDialog.getUsername(), credsDialog.getPassword());
                } else {
                    // User cancelled the credentials dialog — gray hint, no red error
                    showAuthHint();
                }
            } else {
                showError(errMsg);
            }
        });
    }

    /**
     * Phase 3 — single retry after the user provided credentials.
     * On failure: show red error.  Never opens a second credentials dialog.
     */
    private void retryWithCredentials(@NotNull String url,
                                       @NotNull String username,
                                       @NotNull String password) {
        if (!active) return;
        doLoadAsync(url, username, password, true /*isRetry*/);
    }

    /**
     * Low-level async branch load.
     * On success: populates combo.
     * On auth error + not a retry: shows gray hint.
     * On auth error + retry: shows red error (second failure after entering credentials).
     * On other error: shows red error.
     */
    private void doLoadAsync(@NotNull String url,
                              @NotNull String username,
                              @NotNull String password,
                              boolean isRetry) {
        AmosBranchLoader.loadBranchesAsync(url, username, password, branchCombo, errMsg -> {
            if (!active) return;

            if (AmosBranchLoader.isAuthError(errMsg)) {
                if (isRetry) {
                    showError("Authentication failed — please check your credentials and try again.");
                } else {
                    showAuthHint();
                }
            } else {
                showError(errMsg);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Status label helpers
    // -------------------------------------------------------------------------

    private void clearStatus() {
        branchStatusLabel.setText(" ");
    }

    private void showAuthHint() {
        branchStatusLabel.setForeground(Color.GRAY);
        branchStatusLabel.setText(
                "<html><font color='gray'>🔒 Authentication required — click ↺ to connect.</font></html>");
    }

    private void showError(@NotNull String msg) {
        branchStatusLabel.setForeground(Color.RED);
        branchStatusLabel.setText("<html><font color='red'>⚠ " + msg + "</font></html>");
    }

    // -------------------------------------------------------------------------
    // Layout helpers
    // -------------------------------------------------------------------------

    private static GridBagConstraints labelConstraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets  = new Insets(4, 6, 4, 6);
        c.anchor  = GridBagConstraints.LINE_START;
        c.fill    = GridBagConstraints.NONE;
        c.weightx = 0.0;
        return c;
    }

    private static GridBagConstraints fieldConstraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets  = new Insets(4, 0, 4, 6);
        c.fill    = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        return c;
    }

    private void addRow(JPanel panel,
                        GridBagConstraints lc, GridBagConstraints fc,
                        int row, String label, JComponent field) {
        lc.gridx = 0; lc.gridy = row; lc.weightx = 0.0; lc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(label), lc);
        fc.gridx = 1; fc.gridy = row; fc.weightx = 1.0; fc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, fc);
    }

    private void updateDerivedFields() {
        String branch    = getCurrentBranch();

        if (branch.equals("Loading branches…") || !branch.startsWith("dev-")) {
            projectNameField.setText("");
            checkoutDirLabel.setText(" ");
            return;
        }

        // e.g. dev-7.5  →  amos-dev-7.5
        String projectName = "amos-" + branch;
        projectNameField.setText(projectName);
        updateCheckoutDir();
    }

    private void updateCheckoutDir() {
        String localRoot   = localRootField.getText().trim();
        String projectName = projectNameField.getText().trim();
        checkoutDirLabel.setText((localRoot.isEmpty() || projectName.isEmpty())
                ? " "
                : localRoot + File.separator + projectName);
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String branch = getCurrentBranch();
        if (!BRANCH_PATTERN.matcher(branch).matches()) {
            return new ValidationInfo("Branch must match dev-X.Y (e.g. dev-7.5)", branchCombo);
        }

        String projectName = projectNameField.getText().trim();
        if (projectName.isEmpty()) {
            return new ValidationInfo("Project name is required", projectNameField);
        }

        String localRoot = localRootField.getText().trim();
        if (localRoot.isEmpty()) {
            return new ValidationInfo("Local root directory is required", localRootField);
        }
        File localRootFile = new File(localRoot);
        if (!localRootFile.isDirectory()) {
            return new ValidationInfo("Local root must be an existing directory", localRootField);
        }
        if (!localRootFile.canWrite()) {
            return new ValidationInfo("Local root directory is not writable", localRootField);
        }

        // Checkout directory may already exist (partial/interrupted clone → resume)

        String sourcePath = sourceProjectField.getText().trim();
        if (!sourcePath.isEmpty()) {
            File sourceDir = new File(sourcePath);
            if (!sourceDir.exists() || !sourceDir.isDirectory()) {
                return new ValidationInfo("Source project directory does not exist", sourceProjectField);
            }
            if (!new File(sourceDir, ".idea").isDirectory()) {
                return new ValidationInfo(
                        "Source project directory must contain .idea/ subdirectory", sourceProjectField);
            }
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // Public getters
    // -------------------------------------------------------------------------

    public String getRepoUrl()           { return repoUrlField.getText().trim(); }
    public String getBranch()            { return getCurrentBranch(); }
    public String getLocalRoot()         { return localRootField.getText().trim(); }

    public String getProjectName() {
        return projectNameField.getText().trim();
    }

    public String getCheckoutDir() {
        String t = checkoutDirLabel.getText().trim();
        return t.equals(" ") ? "" : t;
    }

    public String getSourceProjectPath() { return sourceProjectField.getText().trim(); }

    /** Username currently stored in the OS keychain / settings. */
    public String getUsername() { return AmosCredentialStore.loadUsername(); }

    /** Password currently stored in the OS keychain. */
    public String getPassword() { return AmosCredentialStore.loadPassword(); }

    // -------------------------------------------------------------------------

    private String getCurrentBranch() {
        Object item = branchCombo.getEditor().getItem();
        return item == null ? "" : item.toString().trim();
    }
}

