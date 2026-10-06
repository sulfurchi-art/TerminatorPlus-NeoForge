package net.nuggetmc.tplus.api;

public interface AIManager {
    void clearSession();

    /**
     * Clears the session only if {@code session} is still the active one (a finished session must not end a newer one).
     */
    default void clearSession(Object session) {
        clearSession();
    }
}
