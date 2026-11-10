package org.Cry_Legende.sHSlots;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerDataManager {

    private final SHSlots plugin;
    private final File file;
    private FileConfiguration config;
    // Mode réseau : copie de la base partagée, lue à la place de users.yml (null en mode local)
    private volatile Map<String, String> networkLastMessages;
    private volatile Map<String, String> networkBlacklistDates;

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

    /** Mode réseau : derniers messages et dates de blacklist (par pseudo en minuscules) lus dans la base partagée. */
    public void useSnapshot(Map<String, String> lastMessages, Map<String, String> blacklistDates) {
        this.networkLastMessages = new ConcurrentHashMap<>(lastMessages);
        this.networkBlacklistDates = new ConcurrentHashMap<>(blacklistDates);
    }

    /** Mode réseau : dernier message écrit par ce serveur, visible avant la prochaine relecture de la base. */
    public void networkSetLastMessage(String lowerName, String message) {
        Map<String, String> map = networkLastMessages;
        if (map != null) map.put(lowerName, message);
    }

    /** Mode réseau : date de blacklist écrite par ce serveur (null pour l'effacer). */
    public void networkSetBlacklistDate(String lowerName, String date) {
        Map<String, String> map = networkBlacklistDates;
        if (map == null) return;
        if (date == null) map.remove(lowerName);
        else map.put(lowerName, date);
    }

    public void setLastMessage(String playerName, String message) {
        config.set(playerName.toLowerCase() + ".last_message", message);
        save();
    }

    public String getLastMessage(String playerName) {
        Map<String, String> network = networkLastMessages;
        if (network != null) {
            return network.getOrDefault(playerName.toLowerCase(), "Aucun message récent");
        }
        return config.getString(playerName.toLowerCase() + ".last_message", "Aucun message récent");
    }

    public void setBlacklistDate(String playerName) {
        String date = new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date());
        config.set(playerName.toLowerCase() + ".blacklist_date", date);
        save();
    }

    public String getBlacklistDate(String playerName) {
        Map<String, String> network = networkBlacklistDates;
        if (network != null) {
            return network.getOrDefault(playerName.toLowerCase(), "Date inconnue");
        }
        return config.getString(playerName.toLowerCase() + ".blacklist_date", "Date inconnue");
    }

    public void removeBlacklistDate(String playerName) {
        config.set(playerName.toLowerCase() + ".blacklist_date", null);
        save();
    }
}
