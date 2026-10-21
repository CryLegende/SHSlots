package org.Cry_Legende.sHSlots;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatListener implements Listener {

    private final SHSlots plugin;
    private final Map<String, CommandData> awaitingResponse;
    private final PlaceholderHandler placeholderHandler;
    private final LogHandler logHandler;

    public ChatListener(SHSlots plugin, Map<String, CommandData> awaitingResponse, PlaceholderHandler placeholderHandler, LogHandler logHandler) {
        this.plugin = plugin;
        this.awaitingResponse = awaitingResponse;
        this.placeholderHandler = placeholderHandler;
        this.logHandler = logHandler;
    }

    // Priorité LOWEST pour capturer avant tout le monde
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();

        if (awaitingResponse.containsKey(playerName)) {
            // On annule pour ne pas que ça sorte dans le chat
            event.setCancelled(true);

            CommandData commandData = awaitingResponse.get(playerName);

            // Couleur
            String rawMessage = event.getMessage();
            String coloredMessage = ChatColor.translateAlternateColorCodes('&', rawMessage);

            // Mise à jour Placeholder
            placeholderHandler.updateSlotMessage(commandData.getSlot(), coloredMessage);

            // Log fichier
            logHandler.logMessage(playerName, commandData.getSlot(), coloredMessage);

            // Sauvegarde "Dernier message" pour le GUI Admin
            plugin.getPlayerDataManager().setLastMessage(playerName, coloredMessage);

            // Retrait de la liste d'attente
            awaitingResponse.remove(playerName);
            commandData.cancelCountdown();

            player.sendMessage(plugin.getColoredConfigMessage("messages.slotMessageSet", "§aVotre message a été défini avec succès."));
        }
    }

    /**
     * Analyse une chaîne de durée (ex: "1h30m10s", "5m", "30") et la convertit en millisecondes.
     */
    public static long parseDuration(String durationStr) {
        Pattern pattern = Pattern.compile("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?|(\\d+)");
        Matcher matcher = pattern.matcher(durationStr);

        if (!matcher.find()) {
            return 0;
        }

        if (matcher.group(4) != null) {
            return Long.parseLong(matcher.group(4)) * 1000;
        }

        String hoursStr = matcher.group(1);
        String minutesStr = matcher.group(2);
        String secondsStr = matcher.group(3);

        long hours = hoursStr != null ? Long.parseLong(hoursStr) * 3600000 : 0;
        long minutes = minutesStr != null ? Long.parseLong(minutesStr) * 60000 : 0;
        long seconds = secondsStr != null ? Long.parseLong(secondsStr) * 1000 : 0;

        return hours + minutes + seconds;
    }
}