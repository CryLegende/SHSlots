package org.Cry_Legende.sHSlots;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class LogHandler {

    private final SHSlots plugin;
    private final File logFile;
    private final FileConfiguration logConfig;

    public LogHandler(SHSlots plugin) {
        this.plugin = plugin;
        this.logFile = new File(plugin.getDataFolder(), "logs.yml");
        if (!logFile.exists()) {
            try {
                logFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        this.logConfig = YamlConfiguration.loadConfiguration(logFile);
    }

    // Appelée depuis le thread asynchrone du chat
    public synchronized void logMessage(String playerName, int slot, String message) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String logEntry = "[" + timestamp + "] Joueur: " + playerName + ", Slot: " + slot + ", Message: " + message;

        List<String> logs = logConfig.getStringList("messages");
        logs.add(logEntry);
        logConfig.set("messages", logs);

        try {
            logConfig.save(logFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}