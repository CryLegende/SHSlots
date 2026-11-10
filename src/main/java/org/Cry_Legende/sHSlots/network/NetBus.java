package org.Cry_Legende.sHSlots.network;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Ce dont le mode réseau a besoin de SHNetwork. Seule {@link ShNetworkBus} connaît ses classes : sans lui, aucune
 * classe de ce paquet n'est chargée.
 */
public interface NetBus {

    /** Candidature à une tâche de meneur. */
    interface Leader {

        boolean isLeader();

        void release();
    }

    /** Publie à destination des autres serveurs (pas de celui-ci). Ne bloque jamais. */
    void publish(String channel, JsonObject message);

    /**
     * Écoute un canal.
     *
     * @param mainThread vrai pour recevoir sur le fil principal
     */
    void listen(String channel, Consumer<JsonObject> handler, boolean mainThread);

    /** Pseudo, avec sa casse, d'un joueur connecté sur ce serveur ou sur un autre, retrouvé par son pseudo. */
    Optional<String> findName(String name);

    /** Pseudos de tous les joueurs connectés au réseau. */
    List<String> onlineNames();

    /** Se porte candidat pour une tâche qui ne tourne que sur un serveur à la fois. */
    Leader leader(String task, Runnable onAcquire, Runnable onLose);
}
