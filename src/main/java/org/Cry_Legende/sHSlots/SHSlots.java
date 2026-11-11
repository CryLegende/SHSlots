package org.Cry_Legende.sHSlots;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

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
    private volatile List<String> blacklistedPlayers;
    private BukkitTask announcementTask;

    // Mode réseau (SHNetwork présent) : null en mode local
    private NetworkSlots network;
    private volatile Messages messages;

    // Managers
    private LogHandler logHandler;
    private AdminGuiManager adminGuiManager;
    private PlayerDataManager playerDataManager;

    @Override
    public void onEnable() {
        // Chargement de la config
        saveDefaultConfig();
        reloadConfig();

        // Crée messages.yml au premier démarrage, avec les textes des anciennes sections de config.yml
        Messages.prepare(this);
        try {
            messages = Messages.load(this);
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().warning("messages.yml illisible, textes du jar utilisés : " + e.getMessage());
            messages = Messages.defaults(this);
        }

        // Lue depuis le thread du chat (asynchrone) et depuis les commandes
        awaitingResponse = new ConcurrentHashMap<>();

        // Initialisation des services (Ordre important)
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

        // Chargement des données : avec SHNetwork, la base partagée les fournit (relue par NetworkSlots)
        network = openNetwork();
        if (network == null) {
            loadSlotsData();
            loadBlacklist();
        } else {
            blacklistedPlayers = new CopyOnWriteArrayList<>();
        }

        // Annonces
        loadAnnouncement();

        // Avec SHNetwork, seul le serveur meneur annonce : sa tâche démarre quand il est désigné
        if (network == null) {
            startAnnouncementTask();
        } else {
            network.start();
        }
    }

    @Override
    public void onDisable() {
        if (network != null) {
            // les données sont dans la base : slots_data.json, users.yml et config.yml ne sont pas réécrits
            network.close();
        } else if (placeholderHandler != null && blacklistedPlayers != null) {
            // Rien à écrire si onEnable a échoué avant le chargement : on n'efface pas les données sauvegardées
            saveSlotsData();
            saveBlacklist();
        }
    }

    /** Lit l'annonce : intervalle, son et commande dans config.yml, textes dans messages.yml. */
    private void loadAnnouncement() {
        announcementInterval = getConfig().getInt("announcement.interval", 10) * 60 * 20;
        announcementMessage = getColoredStringList("announcement.message");
        announcementHover = getColoredMessage("announcement.hover", "&7Cliquez pour plus d'informations.");
        announcementClickCommand = getConfig().getString("announcement.click_command", "/msg %sponsor% Bonjour !");
        String soundName = getConfig().getString("announcement.sound", "ENTITY_PLAYER_LEVELUP");
        announcementSound = sound(soundName);
        if (announcementSound == null) {
            getLogger().warning("Son invalide dans la configuration : " + soundName);
        }
    }

    /**
     * Relit config.yml et messages.yml. Les deux sont lus à part et ne remplacent l'état en cours que s'ils sont
     * valides ; sinon l'ancienne configuration reste active. Les slots (slots_data.json ou base), les derniers
     * messages (users.yml) et la blacklist ne sont pas touchés ; la section reseau ne se recharge pas à chaud.
     */
    void reload() throws ReloadException {
        String file = "config.yml";
        Messages loaded;
        try {
            new YamlConfiguration().load(new File(getDataFolder(), file));
            file = "messages.yml";
            loaded = Messages.load(this);
        } catch (IOException | InvalidConfigurationException e) {
            throw new ReloadException(file, e);
        }
        reloadConfig();
        messages = loaded;
        // La blacklist est une donnée tenue en mémoire : on la remet dans la configuration relue
        if (network == null) {
            getConfig().set("blacklist", blacklistedPlayers);
        }
        maxSlots = getConfig().getInt("max_slots", 6);
        loadAnnouncement();
        if (network == null) {
            startAnnouncementTask();
        } else {
            network.restartAnnouncement();
        }
    }

    /** Fichier de configuration illisible pendant un reload. */
    static final class ReloadException extends Exception {

        final String file;
        final String detail;

        ReloadException(String file, Throwable cause) {
            super(file + " : " + firstLine(cause), cause);
            this.file = file;
            this.detail = firstLine(cause);
        }

        private static String firstLine(Throwable cause) {
            String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
            return message.lines().findFirst().orElse("");
        }
    }

    Messages messages() {
        return messages;
    }

    // --- GETTERS ---
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

    /** Mode réseau : remplace la blacklist par celle de la base. */
    void setBlacklist(List<String> names) {
        this.blacklistedPlayers = new CopyOnWriteArrayList<>(names);
    }

    /** Mode réseau : un joueur vient d'être ajouté à la blacklist, sa copie locale suit sans attendre la relecture. */
    void networkBlacklistAdded(String lowerName, String date) {
        if (!blacklistedPlayers.contains(lowerName)) {
            blacklistedPlayers.add(lowerName);
        }
        playerDataManager.networkSetBlacklistDate(lowerName, date);
    }

    /** Mode réseau : un joueur vient d'être retiré de la blacklist. */
    void networkBlacklistRemoved(String lowerName) {
        blacklistedPlayers.remove(lowerName);
        playerDataManager.networkSetBlacklistDate(lowerName, null);
    }

    /** Mode réseau, ou null si ce serveur tient ses slots seul. */
    NetworkSlots network() {
        return network;
    }

    Map<String, CommandData> getAwaitingResponse() {
        return awaitingResponse;
    }

    long getAnnouncementIntervalTicks() {
        return announcementInterval;
    }

    /** Exécute sur le fil principal (appelable de n'importe quel fil). */
    void runMain(Runnable task) {
        Bukkit.getScheduler().runTask(this, task);
    }

    /** Pseudos des joueurs connectés : tout le réseau avec SHNetwork, ce serveur sinon. */
    List<String> onlinePlayerNames() {
        if (network != null) {
            return network.onlineNames();
        }
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    // --- MODE RÉSEAU ---

    /**
     * Passe en mode réseau si SHNetwork est installé et que reseau.actif est vrai. Les classes qui touchent à SHNetwork
     * ne sont résolues qu'ici : sans lui, rien n'est chargé et le plugin garde son fonctionnement d'origine.
     */
    private NetworkSlots openNetwork() {
        if (!getConfig().getBoolean("reseau.actif", true)) return null;
        if (!getServer().getPluginManager().isPluginEnabled("SHNetwork")) return null;
        NetworkSlots net = null;
        try {
            net = NetworkSlots.open(this);
            playerDataManager.useSnapshot(Map.of(), Map.of());
            getLogger().info("SHNetwork détecté : slots et blacklist en base partagée, une seule annonce pour tout le réseau.");
            return net;
        } catch (RuntimeException | LinkageError e) {
            if (net != null) net.close();
            getLogger().log(Level.SEVERE, "SHNetwork présent mais mode réseau inutilisable, slots locaux à ce serveur.", e);
            return null;
        }
    }

    /** Texte de messages.yml, avec les codes couleur &. */
    String networkMessage(String key) {
        return ChatColor.translateAlternateColorCodes('&', messages.string(key, ""));
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
        if (announcementTask != null) {
            announcementTask.cancel();
        }
        announcementTask = new BukkitRunnable() {
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

                announce(data.getPlayerName(), String.join(" ", data.getMessageLines()));
            }
        }.runTaskTimer(this, 0, announcementInterval);
    }

    /** Annonce un slot dans le chat de tous les joueurs de ce serveur, avec son son. */
    void announce(String sponsor, String message) {
        List<String> formattedMessage = new ArrayList<>();
        for (String line : announcementMessage) {
            formattedMessage.add(line.replace("%sponsor%", sponsor).replace("%message%", message));
        }

        String hoverText = announcementHover.replace("%sponsor%", sponsor);
        sendAnnouncement(formattedMessage, hoverText, announcementClickCommand, sponsor);
        playAnnouncementSound();
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

    public String getColoredMessage(String path, String defaultMessage) {
        return ChatColor.translateAlternateColorCodes('&', messages.string(path, defaultMessage));
    }

    public List<String> getColoredStringList(String path) {
        List<String> list = messages.list(path);
        if (list == null) return new ArrayList<>();
        List<String> coloredList = new ArrayList<>();
        for (String item : list) {
            coloredList.add(ChatColor.translateAlternateColorCodes('&', item));
        }
        return coloredList;
    }
}
