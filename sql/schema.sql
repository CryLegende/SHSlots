-- Tables de SHSlots avec SHNetwork. Le plugin les crée seul au démarrage si l'utilisateur a le droit CREATE ;
-- ce fichier sert à les créer à la main et à ne donner à l'utilisateur que les droits de lecture et d'écriture.
--
-- Utilisation (base bo_net pour les essais multi-Opale) :
--   mysql -u root < sql/schema.sql   puis remplacer CHANGE_ME par le mot de passe de config.yml

CREATE DATABASE IF NOT EXISTS bo_net;

-- Un slot occupé par ligne ; pas de ligne = slot libre. La clé du slot et l'unicité du pseudo font refuser par la base
-- un slot déjà pris et un joueur qui en a déjà un, quel que soit le serveur qui donne le slot.
CREATE TABLE IF NOT EXISTS bo_net.slots_active (
    slot        INT          NOT NULL,                  -- numéro du slot, de 1 à max_slots
    player_name VARCHAR(64)  NOT NULL,                  -- titulaire, sans tenir compte de la casse
    message     TEXT         NOT NULL,                  -- message saisi par le joueur, vide tant qu'il n'a rien écrit
    expires_at  BIGINT       NOT NULL,                  -- fin du slot, en millisecondes depuis 1970
    PRIMARY KEY (slot),
    UNIQUE KEY uk_player (player_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Par pseudo en minuscules (les anciennes données ne portent que des pseudos en minuscules, or l'UUID hors ligne
-- dépend de la casse exacte du pseudo) : dernier message saisi et appartenance à la blacklist.
CREATE TABLE IF NOT EXISTS bo_net.slots_users (
    name           VARCHAR(64) NOT NULL,                -- pseudo en minuscules
    last_message   TEXT        NULL,                    -- dernier message saisi, affiché dans le menu d'administration
    blacklisted_at BIGINT      NOT NULL DEFAULT 0,      -- 0 = pas dans la blacklist, sinon l'heure d'ajout (fixe l'ordre)
    blacklist_date VARCHAR(32) NULL,                    -- date affichée dans le menu d'administration (jj/mm/aaaa hh:mm)
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE USER IF NOT EXISTS 'shslots'@'127.0.0.1' IDENTIFIED BY 'CHANGE_ME';
GRANT SELECT, INSERT, UPDATE, DELETE ON bo_net.slots_active TO 'shslots'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON bo_net.slots_users TO 'shslots'@'127.0.0.1';
