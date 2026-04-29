package com.swissas.amos.wizard;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Application-level persistent settings for the AMOS Project Wizard plugin.
 */
@State(name = "AmosPluginSettings", storages = @Storage("AmosPluginSettings.xml"))
public class AmosPluginSettings implements PersistentStateComponent<AmosPluginSettings.State> {

    public static class State {
        public String lastRepoUrl = "";
        public String lastLocalRoot = "";
        public String lastSourceProjectPath = "";
        /** Last username used for Git authentication (password is stored in OS keychain). */
        public String lastUsername = "";
    }

    private State myState = new State();

    public static AmosPluginSettings getInstance() {
        return ApplicationManager.getApplication().getService(AmosPluginSettings.class);
    }

    @Override
    public @Nullable State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.myState = state;
    }

    public String getLastRepoUrl() {
        return myState.lastRepoUrl;
    }

    public void setLastRepoUrl(String url) {
        myState.lastRepoUrl = url != null ? url : "";
    }

    public String getLastLocalRoot() {
        return myState.lastLocalRoot;
    }

    public void setLastLocalRoot(String path) {
        myState.lastLocalRoot = path != null ? path : "";
    }

    public String getLastSourceProjectPath() {
        return myState.lastSourceProjectPath;
    }

    public void setLastSourceProjectPath(String path) {
        myState.lastSourceProjectPath = path != null ? path : "";
    }

    public String getLastUsername() {
        return myState.lastUsername;
    }

    public void setLastUsername(String username) {
        myState.lastUsername = username != null ? username : "";
    }
}

