package org.Cry_Legende.sHSlots;

import com.google.gson.JsonObject;
import org.Cry_Legende.sHSlots.network.NetBus;
import org.Cry_Legende.sHSlots.network.ShNetworkBus;
import org.Cry_Legende.sHSlots.network.SlotsDb;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SHSlots sur plusieurs serveurs de jeu (SHNetwork) : la base partagée ({@link SlotsDb}) tient les slots, la blacklist et
 * les derniers messages ; chaque serveur en garde une copie en mémoire (placeholders, menus, vérifications) que le bus
 * fait relire après chaque écriture. Un seul serveur, le meneur, lance l'annonce des slots. Seule {@link ShNetworkBus}
 * connaît SHNetwork : sans lui, aucune classe de ce paquet n'est chargée.
 *
 * <p>Messages du canal {@code reseau.canal} :</p>
 * <ul>
 *   <li>{@code changed} : la base a changé, relire slots et utilisateurs ;</li>
 *   <li>{@code await} {@code {player, slot, expiresAt}} : un slot vient d'être attribué, le joueur doit saisir son
 *       message dans le chat, où qu'il soit ;</li>
 *   <li>{@code await-clear} {@code {player}} : le joueur n'a plus de message à saisir ;</li>
 *   <li>{@code announce} {@code {slot, sponsor, message}} : chaque serveur annonce ce slot à ses joueurs.</li>
 * </ul>
 */
public final class NetworkSlots {

    private static final String TASK = "shslots";
    private static final long AWAIT_TICKS = 2400L; // 2 minutes
    private static final long RETRY_MS = 10_000L;

    private final SHSlots plugin;
    private final NetBus bus;
    private final SlotsDb db;
    private final String channel;
    private final AtomicBoolean reloadQueued = new AtomicBoolean();
    private volatile boolean loaded;
    private volatile boolean closed;
    private volatile NetBus.Leader leader;
    private volatile long lastAnnounceAt;
    private volatile int lastAnnouncedSlot;
    private volatile long lastErrorLog;
    private BukkitTask announceTask;
    private boolean retryScheduled;

    private NetworkSlots(SHSlots plugin, NetBus bus, SlotsDb db, String channel) {
        this.plugin = plugin;
        this.bus = bus;
        this.db = db;
        this.channel = channel;
    }

    /**
     * Prépare le pont vers SHNetwork et la connexion à la base partagée, sans les interroger. Une configuration
     * incomplète lève une exception.
     */
    static NetworkSlots open(SHSlots plugin) {
        FileConfiguration config = plugin.getConfig();
        String base = "reseau.base-de-donnees.";
        String user = config.getString(base + "utilisateur");
        String password = config.getString(base + "mot-de-passe");
        if (user == null || user.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException("reseau.base-de-donnees.utilisateur et mot-de-passe sont à renseigner");
        }
        SlotsDb db = new SlotsDb(
            config.getString(base + "url", "jdbc:mariadb://127.0.0.1:3306/bo_net"),
            user,
            password,
            Math.max(250, config.getInt(base + "delai-ms", 5000)));
        try {
            NetBus bus = ShNetworkBus.open();
            return new NetworkSlots(plugin, bus, db, config.getString("reseau.canal", "shslots"));
        } catch (RuntimeException | LinkageError e) {
            db.close();
            throw e;
        }
    }

    /** Écoute le bus, charge la base (avec de nouveaux essais tant qu'elle ne répond pas) et se porte candidat meneur. */
    void start() {
        bus.listen(channel, this::onMessage, true);
        Thread loader = new Thread(this::initialLoad, "SHSlots-Reseau-Demarrage");
        loader.setDaemon(true);
        loader.start();
        leader = bus.leader(TASK, this::onLeaderAcquired, this::onLeaderLost);
    }

    /** Arrêt du plugin : rend le rôle de meneur, arrête l'annonce et ferme la base. */
    void close() {
        closed = true;
        NetBus.Leader current = leader;
        if (current != null) {
            current.release();
        }
        if (announceTask != null) {
            announceTask.cancel();
        }
        db.close();
    }

    // ------------------------------------------------------------------ chargement

    private void initialLoad() {
        while (!closed) {
            try {
                db.createTables();
                applySnapshot(db.snapshot(System.currentTimeMillis()));
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("Base du réseau injoignable, nouvel essai dans " + RETRY_MS / 1000 + " s : " + e.getMessage());
                try {
                    Thread.sleep(RETRY_MS);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }

    /** Relit la base en arrière-plan ; plusieurs demandes rapprochées ne font qu'une lecture. */
    private void reload() {
        if (closed || !reloadQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            db.async(() -> {
                reloadQueued.set(false);
                applySnapshot(db.snapshot(System.currentTimeMillis()));
                return null;
            }).whenComplete((ok, error) -> {
                if (error != null) {
                    logError("Lecture de la base du réseau impossible", error);
                }
            });
        } catch (RejectedExecutionException e) {
            // arrêt du plugin en cours
        }
    }

    /** Remplace les caches du plugin par le contenu de la base (appelée hors du fil principal). */
    private void applySnapshot(SlotsDb.Snapshot snapshot) {
        PlaceholderHandler handler = plugin.getPlaceholderHandler();
        long now = System.currentTimeMillis();
        Set<Integer> keep = new HashSet<>();
        for (SlotsDb.SlotRow row : snapshot.slots()) {
            long remaining = row.expiresAt() - now;
            if (remaining > 0) {
                handler.setSlotData(row.slot(), row.player(), row.message(), remaining);
                keep.add(row.slot());
            }
        }
        handler.retainSlots(keep);

        Map<String, String> lastMessages = new HashMap<>();
        Map<String, String> blacklistDates = new HashMap<>();
        List<String> blacklist = new ArrayList<>();
        for (SlotsDb.UserRow user : snapshot.users()) {
            if (user.lastMessage() != null) {
                lastMessages.put(user.name(), user.lastMessage());
            }
            if (user.blacklistedAt() > 0) {
                blacklist.add(user.name());
                if (user.blacklistDate() != null) {
                    blacklistDates.put(user.name(), user.blacklistDate());
                }
            }
        }
        plugin.getPlayerDataManager().useSnapshot(lastMessages, blacklistDates);
        plugin.setBlacklist(blacklist);
        loaded = true;
    }

    private void logError(String what, Throwable error) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLog > 60_000L) {
            lastErrorLog = now;
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            plugin.getLogger().warning(what + " : " + cause.getMessage());
        }
    }

    // ------------------------------------------------------------------ bus

    /** Envoie aux autres serveurs, et traite tout de suite ici (sur le fil principal) sans attendre le bus. */
    private void broadcast(JsonObject message) {
        if (closed) {
            return;
        }
        bus.publish(channel, message);
        Bukkit.getScheduler().runTask(plugin, () -> onMessage(message));
    }

    private static JsonObject message(String type) {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        return json;
    }

    private void onMessage(JsonObject m) {
        try {
            String type = m.get("type").getAsString();
            switch (type) {
                case "changed" -> reload();
                case "await" -> onAwait(m.get("player").getAsString(), m.get("slot").getAsInt(), m.get("expiresAt").getAsLong());
                case "await-clear" -> onAwaitClear(m.get("player").getAsString());
                case "announce" -> {
                    lastAnnounceAt = System.currentTimeMillis();
                    lastAnnouncedSlot = m.get("slot").getAsInt();
                    plugin.announce(m.get("sponsor").getAsString(), m.get("message").getAsString());
                }
                default -> {
                }
            }
        } catch (RuntimeException e) {
            plugin.getLogger().fine("Message du réseau ignoré : " + e);
        }
    }

    /** Un slot vient d'être attribué : le joueur, où qu'il soit, doit saisir son message dans le chat du serveur où il se trouve. */
    private void onAwait(String player, int slot, long expiresAt) {
        // le slot est connu tout de suite, sans attendre la relecture de la base : la saisie du joueur en dépend
        plugin.getPlaceholderHandler().setSlotData(slot, player, "", Math.max(0, expiresAt - System.currentTimeMillis()));
        registerAwaiting(player, slot);
        Player target = Bukkit.getPlayerExact(player);
        if (target != null) {
            target.sendMessage(plugin.getColoredConfigMessage("messages.enterMessageForSlot", "§eEntrez le message pour le slot %slot%...")
                .replace("%slot%", String.valueOf(slot)));
        }
    }

    private void onAwaitClear(String player) {
        Map<String, CommandData> awaiting = plugin.getAwaitingResponse();
        for (String name : new ArrayList<>(awaiting.keySet())) {
            if (name.equalsIgnoreCase(player)) {
                CommandData removed = awaiting.remove(name);
                if (removed != null) {
                    removed.cancelCountdown();
                }
            }
        }
    }

    /** Attend le message du joueur pendant 2 minutes, comme en mode local. */
    void registerAwaiting(String player, int slot) {
        Map<String, CommandData> awaiting = plugin.getAwaitingResponse();
        CommandData data = new CommandData(slot, player, "");
        CommandData previous = awaiting.put(player, data);
        // l'ancien délai retirerait sinon la nouvelle attente avant ses 2 minutes
        if (previous != null) {
            previous.cancelCountdown();
        }
        BukkitRunnable countdown = new BukkitRunnable() {
            @Override
            public void run() {
                awaiting.remove(player, data);
            }
        };
        countdown.runTaskLater(plugin, AWAIT_TICKS);
        data.setCountdownTask(countdown);
    }

    // ------------------------------------------------------------------ annonce

    private void onLeaderAcquired() {
        if (closed) {
            return;
        }
        if (announceTask != null) {
            announceTask.cancel();
        }
        // un nouveau meneur attend la fin de l'intervalle de l'annonce précédente au lieu d'annoncer aussitôt
        long periodTicks = plugin.getAnnouncementIntervalTicks();
        long sinceLast = lastAnnounceAt == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastAnnounceAt;
        long delayTicks = sinceLast >= periodTicks * 50L ? 0L : (periodTicks * 50L - sinceLast) / 50L;
        announceTask = new BukkitRunnable() {
            @Override
            public void run() {
                announceNext();
            }
        }.runTaskTimer(plugin, delayTicks, periodTicks);
    }

    private void onLeaderLost() {
        if (announceTask != null) {
            announceTask.cancel();
            announceTask = null;
        }
    }

    private void announceNext() {
        NetBus.Leader current = leader;
        if (current == null || !current.isLeader()) {
            return;
        }
        if (!loaded) {
            // la base n'a pas encore répondu : on n'attend pas tout l'intervalle pour la première annonce
            if (!retryScheduled && !closed) {
                retryScheduled = true;
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    retryScheduled = false;
                    announceNext();
                }, 40L);
            }
            return;
        }
        PlaceholderHandler handler = plugin.getPlaceholderHandler();
        List<Integer> active = new ArrayList<>();
        for (int i = 1; i <= plugin.getMaxSlots(); i++) {
            if (handler.isSlotOccupiedWithMessage(i)) {
                active.add(i);
            }
        }
        Integer slot = nextSlot(active, lastAnnouncedSlot);
        if (slot == null) {
            return;
        }
        PlaceholderHandler.SlotData data = handler.getSlotDataMap().get(slot);
        if (data == null || data.getMessageLine(0).isEmpty()) {
            return;
        }
        JsonObject m = message("announce");
        m.addProperty("slot", slot);
        m.addProperty("sponsor", data.getPlayerName());
        m.addProperty("message", String.join(" ", data.getMessageLines()));
        broadcast(m);
    }

    /** Slot à annoncer après le dernier annoncé : le premier numéro plus grand, sinon on repart du premier. Null sans slot actif. */
    static Integer nextSlot(List<Integer> activeAscending, int lastAnnounced) {
        for (Integer slot : activeAscending) {
            if (slot > lastAnnounced) {
                return slot;
            }
        }
        return activeAscending.isEmpty() ? null : activeAscending.get(0);
    }

    // ------------------------------------------------------------------ opérations d'administration

    /** Joueur connecté sur le réseau (pseudo avec sa casse), s'il l'est. */
    Optional<String> find(String name) {
        return bus.findName(name);
    }

    /** Pseudos de tous les joueurs connectés au réseau. */
    List<String> onlineNames() {
        return bus.onlineNames();
    }

    /** Attribue le slot, puis prévient tous les serveurs : le joueur est invité à saisir son message. */
    CompletableFuture<SlotsDb.AssignResult> assign(int slot, String player, long durationMs) {
        long now = System.currentTimeMillis();
        long expiresAt = now + durationMs;
        return db.async(() -> db.assign(slot, player, expiresAt, now)).thenApply(result -> {
            if (result == SlotsDb.AssignResult.OK) {
                JsonObject await = message("await");
                await.addProperty("player", player);
                await.addProperty("slot", slot);
                await.addProperty("expiresAt", expiresAt);
                broadcast(await);
                broadcast(message("changed"));
            }
            return result;
        });
    }

    /** Libère le slot d'un joueur. Vrai s'il en avait un. */
    CompletableFuture<Boolean> removeByPlayer(String player) {
        long now = System.currentTimeMillis();
        return db.async(() -> db.removeByPlayer(player, now)).thenApply(removed -> {
            if (removed) {
                int slot = plugin.getPlaceholderHandler().getSlotByPlayerName(player);
                if (slot != -1) {
                    plugin.getPlaceholderHandler().removeSlotData(slot);
                }
                broadcast(message("changed"));
            }
            broadcastAwaitClear(player);
            return removed;
        });
    }

    /** Libère le slot, quel que soit son titulaire ({@code owner}, s'il est connu, n'a plus de message à saisir). */
    CompletableFuture<Boolean> freeSlot(int slot, String owner) {
        return db.async(() -> db.freeSlot(slot)).thenApply(freed -> {
            if (freed) {
                plugin.getPlaceholderHandler().removeSlotData(slot);
                broadcast(message("changed"));
            }
            if (owner != null) {
                broadcastAwaitClear(owner);
            }
            return freed;
        });
    }

    /** Message saisi par le joueur pour son slot. Faux si le slot ne lui appartient plus. */
    CompletableFuture<Boolean> setMessage(int slot, String player, String text) {
        return db.async(() -> {
            boolean written = db.setMessage(slot, player, text);
            if (written) {
                db.setLastMessage(player.toLowerCase(), text);
            }
            return written;
        }).thenApply(written -> {
            if (written) {
                plugin.getPlaceholderHandler().updateSlotMessage(slot, text);
                plugin.getPlayerDataManager().networkSetLastMessage(player.toLowerCase(), text);
                broadcast(message("changed"));
            }
            broadcastAwaitClear(player);
            return written;
        });
    }

    /** Ajoute à la blacklist. Vrai si le joueur n'y était pas. */
    CompletableFuture<Boolean> blacklistAdd(String lowerName) {
        long now = System.currentTimeMillis();
        String date = new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date());
        return db.async(() -> db.blacklistAdd(lowerName, now, date)).thenApply(added -> {
            if (added) {
                plugin.networkBlacklistAdded(lowerName, date);
                broadcast(message("changed"));
            }
            return added;
        });
    }

    /** Retire de la blacklist. Vrai si le joueur y était. */
    CompletableFuture<Boolean> blacklistRemove(String lowerName) {
        return db.async(() -> db.blacklistRemove(lowerName)).thenApply(removed -> {
            if (removed) {
                plugin.networkBlacklistRemoved(lowerName);
                broadcast(message("changed"));
            }
            return removed;
        });
    }

    private void broadcastAwaitClear(String player) {
        JsonObject clear = message("await-clear");
        clear.addProperty("player", player);
        broadcast(clear);
    }

    // ------------------------------------------------------------------ importation

    /**
     * Reprend slots_data.json, users.yml et la blacklist de config.yml dans la base. Ce qui y est déjà n'est pas
     * modifié : la commande peut être relancée.
     */
    CompletableFuture<SlotsDb.ImportCounts> importLegacy() {
        return CompletableFuture.supplyAsync(this::readLegacy)
            .thenCompose(legacy -> db.async(() -> db.importLegacy(legacy.slots(), legacy.users())))
            .thenApply(counts -> {
                broadcast(message("changed"));
                return counts;
            });
    }

    private record Legacy(List<SlotsDb.SlotRow> slots, List<SlotsDb.UserRow> users) {
    }

    private Legacy readLegacy() {
        long now = System.currentTimeMillis();
        List<SlotsDb.SlotRow> slots = new ArrayList<>();
        File json = new File(plugin.getDataFolder(), "slots_data.json");
        try (FileReader reader = new FileReader(json, StandardCharsets.UTF_8)) {
            JSONArray array = (JSONArray) new JSONParser().parse(reader);
            for (Object o : array) {
                JSONObject slot = (JSONObject) o;
                long remaining = (Long) slot.get("time");
                if (remaining > 0) {
                    slots.add(new SlotsDb.SlotRow(((Long) slot.get("slot")).intValue(), (String) slot.get("playerName"),
                        (String) slot.get("message"), now + remaining));
                }
            }
        } catch (FileNotFoundException e) {
            // pas de slot local : rien à reprendre
        } catch (Exception e) {
            throw new IllegalStateException("slots_data.json illisible : " + e.getMessage(), e);
        }

        // la blacklist de config.yml fixe l'ordre d'ajout ; users.yml donne les dates et les derniers messages
        List<String> blacklist = plugin.getConfig().getStringList("blacklist");
        Map<String, SlotsDb.UserRow> users = new HashMap<>();
        YamlConfiguration usersFile = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "users.yml"));
        for (String name : usersFile.getKeys(false)) {
            ConfigurationSection section = usersFile.getConfigurationSection(name);
            if (section == null) {
                continue;
            }
            String lastMessage = section.getString("last_message");
            int position = blacklist.indexOf(name);
            if (lastMessage == null && position < 0) {
                continue;
            }
            users.put(name, new SlotsDb.UserRow(name, lastMessage, position < 0 ? 0 : now + position,
                position < 0 ? null : section.getString("blacklist_date")));
        }
        for (int i = 0; i < blacklist.size(); i++) {
            users.putIfAbsent(blacklist.get(i), new SlotsDb.UserRow(blacklist.get(i), null, now + i, null));
        }
        return new Legacy(slots, new ArrayList<>(users.values()));
    }
}
