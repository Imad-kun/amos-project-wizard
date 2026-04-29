package com.swissas.amos.wizard;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Thin wrapper around IntelliJ's {@link PasswordSafe} (OS keychain) for storing
 * AMOS Git credentials. Credentials survive IDE restarts and are never written to disk
 * in plain text.
 */
public final class AmosCredentialStore {

    /** Stable key used to look up the credential entry in the OS keychain. */
    private static final String SERVICE_KEY = "git";

    private AmosCredentialStore() {
    }

    private static CredentialAttributes attrs() {
        return new CredentialAttributes(
                CredentialAttributesKt.generateServiceName("AMOS Project Wizard", SERVICE_KEY));
    }

    /**
     * Persists {@code username} and {@code password} in the OS keychain.
     * Both must be non-null and non-empty.
     */
    public static void save(@NotNull String username, @NotNull String password) {
        PasswordSafe.getInstance().set(attrs(), new Credentials(username, password));
    }

    /**
     * Returns the previously saved {@link Credentials}, or {@code null} if none exist.
     */
    @Nullable
    public static Credentials load() {
        return PasswordSafe.getInstance().get(attrs());
    }

    /**
     * Returns the saved username, or {@code ""} if none.
     */
    @NotNull
    public static String loadUsername() {
        Credentials c = load();
        String u = (c != null) ? c.getUserName() : null;
        return u != null ? u : "";
    }

    /**
     * Returns the saved password, or {@code ""} if none.
     */
    @NotNull
    public static String loadPassword() {
        Credentials c = load();
        String p = (c != null) ? c.getPasswordAsString() : null;
        return p != null ? p : "";
    }

    /**
     * Injects {@code username:password} into an HTTPS/HTTP URL so that git can
     * authenticate non-interactively.
     * <p>
     * Credentials are percent-encoded according to RFC 3986 (URI userinfo rules)
     * and injected directly into the URL string.  The {@code java.net.URI} class
     * is used only to parse the original URL — the final URL is built manually to
     * avoid the double-encoding bug that occurs when pre-encoded strings are passed
     * to the multi-arg {@code URI} constructor.
     * <p>
     * SSH URLs (starting with {@code git@} or {@code ssh://}) are returned unchanged.
     * If either credential is empty the original URL is returned unchanged.
     *
     * @param repoUrl  original repository URL
     * @param username git username / GitLab login
     * @param password git password or personal-access-token
     * @return URL with embedded credentials, or the original URL on any error
     */
    @NotNull
    public static String injectCredentials(@NotNull String repoUrl,
                                            @NotNull String username,
                                            @NotNull String password) {
        if (username.isEmpty() || password.isEmpty()) return repoUrl;
        if (!repoUrl.startsWith("https://") && !repoUrl.startsWith("http://")) {
            return repoUrl; // SSH — no injection needed
        }
        try {
            java.net.URI uri = new java.net.URI(repoUrl);
            String encodedUser = percentEncodeUserInfo(username);
            String encodedPass = percentEncodeUserInfo(password);

            // Build URL manually using raw (already-encoded) components from the
            // original URI to avoid any decode → re-encode round-trip issues.
            StringBuilder sb = new StringBuilder();
            sb.append(uri.getScheme()).append("://");
            sb.append(encodedUser).append(':').append(encodedPass).append('@');
            sb.append(uri.getHost());
            if (uri.getPort() != -1) sb.append(':').append(uri.getPort());
            String rawPath = uri.getRawPath();
            if (rawPath != null) sb.append(rawPath);
            String rawQuery = uri.getRawQuery();
            if (rawQuery != null) sb.append('?').append(rawQuery);
            String rawFragment = uri.getRawFragment();
            if (rawFragment != null) sb.append('#').append(rawFragment);
            return sb.toString();
        } catch (Exception e) {
            return repoUrl; // fallback — git will prompt (or fail gracefully)
        }
    }

    /**
     * Returns a {@code Basic} Authorization header value for the given credentials,
     * suitable for use with {@code git -c http.extraHeader=Authorization: Basic …}.
     * <p>
     * Returns {@code null} if either credential is empty.
     */
    @Nullable
    public static String basicAuthHeaderValue(@NotNull String username,
                                               @NotNull String password) {
        if (username.isEmpty() || password.isEmpty()) return null;
        String plain = username + ":" + password;
        return "Basic " + Base64.getEncoder()
                .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Percent-encodes a string for use in the <em>userinfo</em> component of a URI
     * according to RFC 3986 §3.2.1.
     * <p>
     * Only characters that are <b>unreserved</b> ({@code A-Z a-z 0-9 - . _ ~}) or
     * <b>sub-delims</b> ({@code ! $ & ' ( ) * + , ; =}) are left unencoded.
     * Everything else (including {@code @ : / ? # [ ] %} and non-ASCII) is
     * percent-encoded as UTF-8 octets.
     */
    @NotNull
    static String percentEncodeUserInfo(@NotNull String s) {
        StringBuilder sb = new StringBuilder(s.length() * 2);
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (isUnreservedOrSubDelim(c)) {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit(c >> 4, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    private static boolean isUnreservedOrSubDelim(int c) {
        // unreserved: ALPHA / DIGIT / "-" / "." / "_" / "~"
        // sub-delims: "!" / "$" / "&" / "'" / "(" / ")" / "*" / "+" / "," / ";" / "="
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~'
                || c == '!' || c == '$' || c == '&' || c == '\'' || c == '(' || c == ')'
                || c == '*' || c == '+' || c == ',' || c == ';' || c == '=';
    }
}

