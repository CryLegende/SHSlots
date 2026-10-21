package org.Cry_Legende.sHSlots;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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

import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SHSlots extends JavaPlugin {

    private PlaceholderHandler placeholderHandler;
    private Map<String, CommandData> awaitingResponse;
    private int maxSlots;
    private String slotsDataFile = "slots_data.json";
    private int announcementInterval;
    private List<String> announcementMessage;
    private String announcementHover;
    private String announcementClickCommand;
    private String announcementSound;
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

        awaitingResponse = new HashMap<>();

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
        announcementSound = getConfig().getString("announcement.sound", "ENTITY_PLAYER_LEVELUP");

        startAnnouncementTask();
    }

    @Override
    public void onDisable() {
        saveSlotsData();
        saveBlacklist();
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
        try {
            FileReader reader = new FileReader(getDataFolder() + "/" + slotsDataFile);
            JSONArray slotsArray = (JSONArray) parser.parse(reader);

            for (Object o : slotsArray) {
                JSONObject slotObject = (JSONObject) o;
                int slot = ((Long) slotObject.get("slot")).intValue();
                String playerName = (String) slotObject.get("playerName");
                String message = (String) slotObject.get("message");
                long time = (Long) slotObject.get("time");
                placeholderHandler.setSlotData(slot, playerName, message, time);
            }
        } catch (Exception e) {
            // Fichier non trouvé ou vide, normal au premier lancement
        }
    }

    private void saveSlotsData() {
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

        try (FileWriter file = new FileWriter(getDataFolder() + "/" + slotsDataFile)) {
            file.write(slotsArray.toJSONString());
            file.flush();
        } catch (Exception e) {
            e.printStackTrace();
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
        try {
            Sound sound = Sound.valueOf(announcementSound.toUpperCase());
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
            }
        } catch (IllegalArgumentException e) {
            getLogger().warning("Son invalide dans la configuration : " + announcementSound);
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
