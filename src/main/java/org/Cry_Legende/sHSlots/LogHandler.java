package org.Cry_Legende.sHSlots;

public class LogHandler {

    private static final String LOG_FILE = "messages";

    private final SHSlots plugin;

    public LogHandler(SHSlots plugin) {
        this.plugin = plugin;
    }

    // Appelée depuis le thread asynchrone du chat
    public void logMessage(String playerName, int slot, String message) {
        ActionLog.write(plugin, LOG_FILE, "Joueur: " + playerName + ", Slot: " + slot + ", Message: " + message);
    }
}
