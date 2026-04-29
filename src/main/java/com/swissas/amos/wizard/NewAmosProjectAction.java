package com.swissas.amos.wizard;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Paths;

/**
 * Menu action entry point: File → New → Project from Amos Branch…
 */
public class NewAmosProjectAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(NewAmosProjectAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        AmosProjectWizardDialog dialog = new AmosProjectWizardDialog();
        if (!dialog.showAndGet()) {
            return;
        }

        // Persist settings for next open
        AmosPluginSettings settings = AmosPluginSettings.getInstance();
        settings.setLastRepoUrl(dialog.getRepoUrl());
        settings.setLastLocalRoot(dialog.getLocalRoot());
        String sourcePath = dialog.getSourceProjectPath();
        if (!sourcePath.isEmpty()) {
            settings.setLastSourceProjectPath(sourcePath);
        }

        AmosProjectCheckoutAndImportTask task = new AmosProjectCheckoutAndImportTask(
                dialog.getRepoUrl(),
                dialog.getUsername(),
                dialog.getPassword(),
                dialog.getBranch(),
                Paths.get(dialog.getLocalRoot()).resolve(dialog.getProjectName()),
                dialog.getProjectName(),
                sourcePath.isEmpty() ? null : Paths.get(sourcePath)
        );

        LOG.info("Starting AMOS project setup: branch=" + dialog.getBranch()
                + ", dir=" + dialog.getCheckoutDir());

        ProgressManager.getInstance().run(task);
    }
}

