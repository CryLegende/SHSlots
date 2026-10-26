package org.Cry_Legende.sHSlots;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SHSlots extends JavaPlugin {

    private PlaceholderHandler placeholderHandler;
    private Map<String, CommandData> awaitingResponse;
    private int maxSlots;
    private String slotsDataFile = "slots_data.json";
    private int announcementInterval;
    private List<String> announcementMessage;
    private String announcementHover;
    private String announcementClickCommand;
    private Sound announcementSound;
    private List<String> blacklistedPlayers;

    // Managers
    private LogHandler logHandler;
    private TextureService textureService;
    private AdminGuiManager adminGuiManager;
    private PlayerDataManager playerDataManager;

    @Override
    public void onEnable() {
        // Chargement de la config
        saveDefaultConfig();
        reloadConfig();

        // Lue depuis le thread du chat (asynchrone) et depuis les commandes
        awaitingResponse = new ConcurrentHashMap<>();

        // Initialisation des services (Ordre important)
        this.textureService = new TextureService(this);
        this.playerDataManager = new PlayerDataManager(this);
        this.logHandler = new LogHandler(this);
        this.adminGuiManager = new AdminGuiManager(this);

        this.maxSlots = getConfig().getInt("max_slots", 6);
        this.placeholderHandler = new PlaceholderHandler(this);

        // Listeners & Commandes
        ChatListener chatListener = new ChatListener(this, awaitingResponse, placeholderHandler, logHandler);
        MainCommand mainCommand = new MainCommand(this, awaitingResponse, placeholderHandler);

        getCommand("shslots").setExecutor(mainCommand);
        getCommand("shslots").setTabCompleter(mainCommand);

        getServer().getPluginManager().registerEvents(chatListener, this);
        getServer().getPluginManager().registerEvents(adminGuiManager, this);

        // Hook PAPI
        if (!placeholderHandler.register()) {
            getLogger().warning("Erreur lors de l'enregistrement de PlaceholderHandler.");
        }

        // Chargement des données
        loadSlotsData();
        loadBlacklist();

        // Annonces
        announcementInterval = getConfig().getInt("announcement.interval", 10) * 60 * 20;
        announcementMessage = getColoredStringList("announcement.message");
        announcementHover = getColoredConfigMessage("announcement.hover", "&7Cliquez pour plus d'informations.");
        announcementClickCommand = getConfig().getString("announcement.click_command", "/msg %sponsor% Bonjour !");
        String soundName = getConfig().getString("announcement.sound", "ENTITY_PLAYER_LEVELUP");
        announcementSound = sound(soundName);
        if (announcementSound == null) {
            getLogger().warning("Son invalide dans la configuration : " + soundName);
        }

        startAnnouncementTask();
    }

    @Override
    public void onDisable() {
        // Rien à écrire si onEnable a échoué avant le chargement : on n'efface pas les données sauvegardées
        if (placeholderHandler != null && blacklistedPlayers != null) {
            saveSlotsData();
            saveBlacklist();
        }
        if (textureService != null) {
            textureService.saveCaches();
        }
    }

    // --- GETTERS ---
    public TextureService getTextureService() {
        return textureService;
    }

    public AdminGuiManager getAdminGuiManager() {
        return adminGuiManager;
    }

    public PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }

    public PlaceholderHandler getPlaceholderHandler() {
        return placeholderHandler;
    }

    public int getMaxSlots() {
        return maxSlots;
    }

    public List<String> getBlacklistedPlayers() {
        return blacklistedPlayers;
    }

    // --- DATA LOADING/SAVING ---

    private void loadBlacklist() {
        this.blacklistedPlayers = getConfig().getStringList("blacklist");
    }

    private void saveBlacklist() {
        getConfig().set("blacklist", this.blacklistedPlayers);
        saveConfig();
    }

    private void loadSlotsData() {
        JSONParser parser = new JSONParser();
        File file = new File(getDataFolder(), slotsDataFile);
        try (FileReader reader = new FileReader(file)) {
            JSONArray slotsArray = (JSONArray) parser.parse(reader);

            for (Object o : slotsArray) {
                JSONObject slotObject = (JSONObject) o;
                int slot = ((Long) slotObject.get("slot")).intValue();
                String playerName = (String) slotObject.get("playerName");
                String message = (String) slotObject.get("message");
                long time = (Long) slotObject.get("time");
                placeholderHandler.setSlotData(slot, playerName, message, time);
            }
        } catch (FileNotFoundException e) {
            // Fichier non trouvé, normal au premier lancement
        } catch (Exception e) {
            // Fichier vide ou illisible : copie avant qu'il soit réécrit à l'arrêt
            if (file.length() > 0) {
                getLogger().warning("Lecture de " + slotsDataFile + " impossible (" + e + "), copie dans " + slotsDataFile + ".corrompu");
                try {
                    Files.copy(file.toPath(), new File(getDataFolder(), slotsDataFile + ".corrompu").toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ex) {
                    getLogger().warning("Copie de " + slotsDataFile + " impossible : " + ex.getMessage());
                }
            }
        }
    }

    /** Sauvegarde les slots (appelée à chaque modification pour ne rien perdre en cas de crash). */
    void saveSlotsData() {
        JSONArray slotsArray = new JSONArray();
        for (Map.Entry<Integer, PlaceholderHandler.SlotData> entry : placeholderHandler.getSlotDataMap().entrySet()) {
            JSONObject slotObject = new JSONObject();
            slotObject.put("slot", entry.getKey());
            PlaceholderHandler.SlotData data = entry.getValue();
            slotObject.put("playerName", data.getPlayerName());
            slotObject.put("message", String.join(" ", data.getMessageLines()));
            slotObject.put("time", data.getRemainingTime());
            slotsArray.add(slotObject);
        }

        // Écriture dans un fichier temporaire puis remplacement : un arrêt brutal ne laisse pas un fichier tronqué
        File target = new File(getDataFolder(), slotsDataFile);
        File tmp = new File(getDataFolder(), slotsDataFile + ".tmp");
        try (FileWriter file = new FileWriter(tmp, StandardCharsets.UTF_8)) {
            file.write(slotsArray.toJSONString());
        } catch (IOException e) {
            getLogger().warning("Sauvegarde de " + slotsDataFile + " impossible : " + e.getMessage());
            return;
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            getLogger().warning("Remplacement de " + slotsDataFile + " impossible : " + e.getMessage());
        }
    }

    // --- TASKS & UTILS ---

    private void startAnnouncementTask() {
        new BukkitRunnable() {
            private int currentSlotIndex = 0;

            @Override
            public void run() {
                List<Integer> activeSlots = getActiveSponsorSlots();
                if (activeSlots.isEmpty()) return;

                if (currentSlotIndex >= activeSlots.size()) {
                    currentSlotIndex = 0;
                }
                int slot = activeSlots.get(currentSlotIndex);
                currentSlotIndex++;

                PlaceholderHandler.SlotData data = placeholderHandler.getSlotDataMap().get(slot);
                if (data == null || data.getMessageLine(0).isEmpty()) return;

                String sponsor = data.getPlayerName();
                String message = String.join(" ", data.getMessageLines());

                List<String> formattedMessage = new ArrayList<>();
                for (String line : announcementMessage) {
                    formattedMessage.add(line.replace("%sponsor%", sponsor).replace("%message%", message));
                }

                String hoverText = announcementHover.replace("%sponsor%", sponsor);
                sendAnnouncement(formattedMessage, hoverText, announcementClickCommand, sponsor);
                playAnnouncementSound();
            }
        }.runTaskTimer(this, 0, announcementInterval);
    }

    private List<Integer> getActiveSponsorSlots() {
        List<Integer> activeSlots = new ArrayList<>();
        for (int i = 1; i <= maxSlots; i++) {
            if (placeholderHandler.isSlotOccupiedWithMessage(i)) {
                activeSlots.add(i);
            }
        }
        return activeSlots;
    }

    private void sendAnnouncement(List<String> messages, String hoverText, String clickCommand, String sponsor) {
        for (String message : messages) {
            TextComponent component = new TextComponent(message);
            component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(hoverText).create()));

            String formattedCommand = clickCommand.replace("%sponsor%", sponsor);
            component.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, formattedCommand));

            for (Player player : Bukkit.getOnlinePlayers()) {
                player.spigot().sendMessage(component);
            }
        }
    }

    private void playAnnouncementSound() {
        if (announcementSound == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.playSound(player.getLocation(), announcementSound, 1.0f, 1.0f);
        }
    }

    /** Son lu en config : ancien nom (ENTITY_PLAYER_LEVELUP) ou clé (minecraft:entity.player.levelup). */
    static Sound sound(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (s.indexOf(':') >= 0 || s.indexOf('.') >= 0) {
            NamespacedKey key = NamespacedKey.fromString(s.toLowerCase(Locale.ROOT));
            return key == null ? null : Registry.SOUND_EVENT.get(key);
        }
        try {
            return (Sound) Sound.class.getField(s.toUpperCase(Locale.ROOT)).get(null);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

    public String getColoredConfigMessage(String path, String defaultMessage) {
        return ChatColor.translateAlternateColorCodes('&', getConfig().getString(path, defaultMessage));
    }

    public List<String> getColoredStringList(String path) {
        List<String> list = getConfig().getStringList(path);
        if (list == null) return new ArrayList<>();
        List<String> coloredList = new ArrayList<>();
        for (String item : list) {
            coloredList.add(ChatColor.translateAlternateColorCodes('&', item));
        }
        return coloredList;
    }
}
