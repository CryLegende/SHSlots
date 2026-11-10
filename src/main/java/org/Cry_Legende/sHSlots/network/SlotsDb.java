package org.Cry_Legende.sHSlots.network;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Slots, blacklist et derniers messages en MariaDB, partagés par tous les serveurs. Chargée seulement avec SHNetwork
 * (HikariCP et le pilote MariaDB viennent de {@code libraries:} dans plugin.yml).
 *
 * <ul>
 *   <li>{@code slots_active} : un slot occupé par ligne ; l'absence de ligne vaut « libre ». Le slot est la clé et le
 *       pseudo est unique : la base refuse un slot déjà pris et un joueur qui en a déjà un ;</li>
 *   <li>{@code slots_users} : par pseudo en minuscules, dernier message saisi, et appartenance à la blacklist.</li>
 * </ul>
 *
 * <p>Les utilisateurs sont gardés par pseudo : les anciennes données (users.yml, blacklist) ne portent que des pseudos en
 * minuscules, or l'UUID hors ligne dépend de la casse exacte du pseudo.</p>
 *
 * <p>Toutes les méthodes bloquent : à appeler hors du fil principal, par {@link #async}.</p>
 */
public final class SlotsDb {

    /** Résultat de l'attribution d'un slot. */
    public enum AssignResult {
        OK,
        /** Le slot est occupé par un autre joueur. */
        SLOT_OCCUPIED,
        /** Le joueur a déjà un autre slot. */
        ALREADY_OWNS
    }

    /** Slot occupé ; {@code message} vaut « » tant que le joueur n'a rien saisi. */
    public record SlotRow(int slot, String player, String message, long expiresAt) {
    }

    /** Ligne de {@code slots_users} ; {@code blacklistedAt} vaut 0 hors blacklist (sinon l'heure d'ajout, qui fixe l'ordre). */
    public record UserRow(String name, String lastMessage, long blacklistedAt, String blacklistDate) {
    }

    /** Contenu de la base utile aux caches des serveurs. */
    public record Snapshot(List<SlotRow> slots, List<UserRow> users) {
    }

    /** Lignes ajoutées par une importation. */
    public record ImportCounts(int slots, int users) {
    }

    /** Une requête, exécutée sur un fil de la base. */
    @FunctionalInterface
    public interface SqlTask<T> {
        T run() throws SQLException;
    }

    private static final int DUPLICATE_ENTRY = 1062;

    private final HikariDataSource pool;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "SHSlots-Reseau");
        t.setDaemon(true);
        return t;
    });

    public SlotsDb(String url, String user, String password, int timeoutMs) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SHSlots");
        hikari.setJdbcUrl(url);
        hikari.setUsername(user);
        hikari.setPassword(password);
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.addDataSourceProperty("connectTimeout", String.valueOf(timeoutMs));
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(Math.max(timeoutMs, 10_000)));
        // les décomptes de lignes ci-dessous sont les lignes trouvées, jamais les lignes modifiées
        hikari.addDataSourceProperty("useAffectedRows", "false");
        hikari.setMaximumPoolSize(3);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(timeoutMs);
        // le serveur démarre même si la base est absente : le plugin réessaie jusqu'à ce qu'elle réponde
        hikari.setInitializationFailTimeout(-1);
        hikari.setKeepaliveTime(60_000);
        this.pool = new HikariDataSource(hikari);
    }

    /** Lance la requête sur un fil de la base ; une erreur SQL termine le futur en erreur. */
    public <T> CompletableFuture<T> async(SqlTask<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.run();
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    public void close() {
        executor.shutdownNow();
        pool.close();
    }

    /** Crée les tables si elles manquent. Une table déjà là ne demande aucun droit CREATE à l'utilisateur. */
    public void createTables() throws SQLException {
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            ensure(st, "slots_active", "CREATE TABLE IF NOT EXISTS slots_active ("
                + "slot INT NOT NULL, "
                + "player_name VARCHAR(64) NOT NULL, "
                + "message TEXT NOT NULL, "
                + "expires_at BIGINT NOT NULL, "
                + "PRIMARY KEY (slot), "
                + "UNIQUE KEY uk_player (player_name)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
            ensure(st, "slots_users", "CREATE TABLE IF NOT EXISTS slots_users ("
                + "name VARCHAR(64) NOT NULL, "
                + "last_message TEXT NULL, "
                + "blacklisted_at BIGINT NOT NULL DEFAULT 0, "
                + "blacklist_date VARCHAR(32) NULL, "
                + "PRIMARY KEY (name)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        }
    }

    private static void ensure(Statement st, String table, String ddl) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT 1 FROM " + table + " LIMIT 1")) {
            return;
        } catch (SQLException missing) {
            // la table n'existe pas encore : création ci-dessous
        }
        st.executeUpdate(ddl);
    }

    // ------------------------------------------------------------------ lecture

    /** Slots non expirés et tous les utilisateurs : de quoi remplir les caches (quelques lignes en tout). */
    public Snapshot snapshot(long now) throws SQLException {
        try (Connection c = pool.getConnection()) {
            List<SlotRow> slots = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT slot, player_name, message, expires_at FROM slots_active WHERE expires_at > ? ORDER BY slot")) {
                ps.setLong(1, now);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        slots.add(new SlotRow(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
                    }
                }
            }
            List<UserRow> users = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT name, last_message, blacklisted_at, blacklist_date FROM slots_users ORDER BY blacklisted_at, name");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    users.add(new UserRow(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4)));
                }
            }
            return new Snapshot(slots, users);
        }
    }

    // ------------------------------------------------------------------ slots

    /**
     * Attribue le slot au joueur pour une durée, message vide. Le même joueur qui reçoit son propre slot repart de zéro.
     * Les cas « slot occupé » et « joueur déjà titulaire d'un autre slot » sont tranchés par les clés de la table, donc
     * deux administrateurs sur deux serveurs ne peuvent pas donner le même slot ni deux slots au même joueur.
     */
    public AssignResult assign(int slot, String player, long expiresAt, long now) throws SQLException {
        try (Connection c = pool.getConnection()) {
            // un ancien slot expiré bloquerait sinon l'attribution d'un nouveau (purgeExpired du mode local)
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM slots_active WHERE expires_at <= ?")) {
                ps.setLong(1, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE slots_active SET message = '', expires_at = ? WHERE slot = ? AND player_name = ?")) {
                ps.setLong(1, expiresAt);
                ps.setInt(2, slot);
                ps.setString(3, player);
                if (ps.executeUpdate() == 1) {
                    return AssignResult.OK;
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO slots_active (slot, player_name, message, expires_at) VALUES (?, ?, '', ?)")) {
                ps.setInt(1, slot);
                ps.setString(2, player);
                ps.setLong(3, expiresAt);
                ps.executeUpdate();
                return AssignResult.OK;
            } catch (SQLException e) {
                if (e.getErrorCode() != DUPLICATE_ENTRY) {
                    throw e;
                }
            }
            // clé refusée : le slot a un titulaire, sinon c'est le joueur qui en a déjà un autre
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM slots_active WHERE slot = ?")) {
                ps.setInt(1, slot);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? AssignResult.SLOT_OCCUPIED : AssignResult.ALREADY_OWNS;
                }
            }
        }
    }

    /** Libère le slot d'un joueur. Vrai s'il en avait un. */
    public boolean removeByPlayer(String player, long now) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM slots_active WHERE player_name = ? AND expires_at > ?")) {
            ps.setString(1, player);
            ps.setLong(2, now);
            return ps.executeUpdate() > 0;
        }
    }

    /** Libère le slot, quel que soit son titulaire. Vrai s'il était occupé. */
    public boolean freeSlot(int slot) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM slots_active WHERE slot = ?")) {
            ps.setInt(1, slot);
            return ps.executeUpdate() > 0;
        }
    }

    /** Message du slot, écrit seulement s'il appartient toujours à ce joueur. Faux sinon. */
    public boolean setMessage(int slot, String player, String message) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE slots_active SET message = ? WHERE slot = ? AND player_name = ?")) {
            ps.setString(1, message);
            ps.setInt(2, slot);
            ps.setString(3, player);
            return ps.executeUpdate() == 1;
        }
    }

    // ------------------------------------------------------------------ utilisateurs et blacklist

    /** Dernier message saisi par le joueur, pour le menu d'administration. */
    public void setLastMessage(String lowerName, String message) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO slots_users (name, last_message) VALUES (?, ?) "
                 + "ON DUPLICATE KEY UPDATE last_message = VALUES(last_message)")) {
            ps.setString(1, lowerName);
            ps.setString(2, message);
            ps.executeUpdate();
        }
    }

    /** Ajoute le joueur à la blacklist. Vrai s'il n'y était pas : c'est ce qui décide « déjà dans la blacklist ». */
    public boolean blacklistAdd(String lowerName, long at, String date) throws SQLException {
        try (Connection c = pool.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO slots_users (name) VALUES (?)")) {
                ps.setString(1, lowerName);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE slots_users SET blacklisted_at = ?, blacklist_date = ? WHERE name = ? AND blacklisted_at = 0")) {
                ps.setLong(1, at);
                ps.setString(2, date);
                ps.setString(3, lowerName);
                return ps.executeUpdate() == 1;
            }
        }
    }

    /** Retire le joueur de la blacklist. Vrai s'il y était. */
    public boolean blacklistRemove(String lowerName) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE slots_users SET blacklisted_at = 0, blacklist_date = NULL WHERE name = ? AND blacklisted_at > 0")) {
            ps.setString(1, lowerName);
            return ps.executeUpdate() == 1;
        }
    }

    // ------------------------------------------------------------------ importation

    /**
     * Reprend les anciennes données locales. Une ligne déjà en base n'est pas modifiée : l'importation peut être
     * relancée sans rien écraser.
     */
    public ImportCounts importLegacy(List<SlotRow> slots, List<UserRow> users) throws SQLException {
        int addedSlots = 0;
        int addedUsers = 0;
        try (Connection c = pool.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT IGNORE INTO slots_active (slot, player_name, message, expires_at) VALUES (?, ?, ?, ?)")) {
                for (SlotRow row : slots) {
                    ps.setInt(1, row.slot());
                    ps.setString(2, row.player());
                    ps.setString(3, row.message());
                    ps.setLong(4, row.expiresAt());
                    if (ps.executeUpdate() == 1) {
                        addedSlots++;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT IGNORE INTO slots_users (name, last_message, blacklisted_at, blacklist_date) VALUES (?, ?, ?, ?)")) {
                for (UserRow row : users) {
                    ps.setString(1, row.name());
                    if (row.lastMessage() == null) {
                        ps.setNull(2, Types.VARCHAR);
                    } else {
                        ps.setString(2, row.lastMessage());
                    }
                    ps.setLong(3, row.blacklistedAt());
                    if (row.blacklistDate() == null) {
                        ps.setNull(4, Types.VARCHAR);
                    } else {
                        ps.setString(4, row.blacklistDate());
                    }
                    if (ps.executeUpdate() == 1) {
                        addedUsers++;
                    }
                }
            }
        }
        return new ImportCounts(addedSlots, addedUsers);
    }
}
