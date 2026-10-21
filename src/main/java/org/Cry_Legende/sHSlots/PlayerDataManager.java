package org.Cry_Legende.sHSlots;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class PlayerDataManager {

    private final SHSlots plugin;
    private final File file;
    private FileConfiguration config;

    public PlayerDataManager(SHSlots plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "users.yml");
        load();
    }

    private void load() {
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        config = YamlConfiguration.loadConfiguration(file);
    }

    public void save() {
        try {
            config.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void setLastMessage(String playerName, String message) {
        config.set(playerName.toLowerCase() + ".last_message", message);
        save();
    }

    public String getLastMessage(String playerName) {
        return config.getString(playerName.toLowerCase() + ".last_message", "Aucun message récent");
    }

    public void setBlacklistDate(String playerName) {
        String date = new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date());
        config.set(playerName.toLowerCase() + ".blacklist_date", date);
        save();
    }

    public String getBlacklistDate(String playerName) {
        return config.getString(playerName.toLowerCase() + ".blacklist_date", "Date inconnue");
    }

    public void removeBlacklistDate(String playerName) {
        config.set(playerName.toLowerCase() + ".blacklist_date", null);
        save();
    }
}
