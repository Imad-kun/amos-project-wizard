package com.swissas.amos.wizard;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Modal dialog that prompts the user for Git credentials (username + password / PAT).
 * Shown automatically when a connection attempt to the configured repository fails
 * with an authentication error.
 * <p>
 * On OK the credentials are saved to the OS keychain via {@link AmosCredentialStore}.
 */
public class AmosCredentialsDialog extends DialogWrapper {

    private JTextField usernameField;
    private JPasswordField passwordField;

    public AmosCredentialsDialog() {
        super(true);
        setTitle("AMOS Git Credentials");
        setOKButtonText("Connect");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        // Pre-fill from settings / keychain
        String savedUsername = AmosPluginSettings.getInstance().getLastUsername();
        String savedPassword = savedUsername.isEmpty() ? "" : AmosCredentialStore.loadPassword();

        usernameField = new JTextField(savedUsername, 30);
        passwordField = new JPasswordField(savedPassword, 30);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints lc = new GridBagConstraints();
        lc.insets  = new Insets(6, 8, 6, 8);
        lc.anchor  = GridBagConstraints.LINE_END;
        lc.fill    = GridBagConstraints.NONE;
        lc.weightx = 0.0;

        GridBagConstraints fc = new GridBagConstraints();
        fc.insets  = new Insets(6, 0, 6, 8);
        fc.fill    = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1.0;

        // Row 0 — hint
        fc.gridx = 0; fc.gridy = 0; fc.gridwidth = 2;
        JLabel hint = new JLabel(
                "<html><i>Enter your GitLab credentials or a Personal Access Token.</i></html>");
        panel.add(hint, fc);
        fc.gridwidth = 1;

        // Row 1 — Username
        lc.gridx = 0; lc.gridy = 1;
        panel.add(new JLabel("Username:"), lc);
        fc.gridx = 1; fc.gridy = 1;
        panel.add(usernameField, fc);

        // Row 2 — Password / Token
        lc.gridx = 0; lc.gridy = 2;
        panel.add(new JLabel("Password / Token:"), lc);
        fc.gridx = 1; fc.gridy = 2;
        panel.add(passwordField, fc);

        // Row 3 — keychain hint
        fc.gridx = 0; fc.gridy = 3; fc.gridwidth = 2;
        JLabel keychainHint = new JLabel(
                "<html><small style='color:gray'>Credentials are stored securely in the OS keychain.</small></html>");
        panel.add(keychainHint, fc);

        panel.setPreferredSize(new Dimension(420, panel.getPreferredSize().height + 10));
        return panel;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        String saved = AmosPluginSettings.getInstance().getLastUsername();
        return saved.isEmpty() ? usernameField : passwordField;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (usernameField.getText().trim().isEmpty()) {
            return new ValidationInfo("Username is required", usernameField);
        }
        if (new String(passwordField.getPassword()).isEmpty()) {
            return new ValidationInfo("Password / token is required", passwordField);
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        String username = getUsername();
        String password = getPassword();
        AmosCredentialStore.save(username, password);
        AmosPluginSettings.getInstance().setLastUsername(username);
        super.doOKAction();
    }

    /** Returns the entered username (trimmed). */
    public String getUsername() {
        return usernameField.getText().trim();
    }

    /** Returns the entered password / PAT. */
    public String getPassword() {
        return new String(passwordField.getPassword());
    }
}

