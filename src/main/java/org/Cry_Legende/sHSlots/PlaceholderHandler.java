package org.Cry_Legende.sHSlots;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

public class PlaceholderHandler extends PlaceholderExpansion {
    private final SHSlots plugin;
    private final Map<Integer, SlotData> slotDataMap = new HashMap<>();

    public PlaceholderHandler(SHSlots plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String getAuthor() {
        return plugin.getDescription().getAuthors().toString();
    }

    @Override
    public String getIdentifier() {
        return "shslots";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public String onPlaceholderRequest(Player player, String identifier) {
        String[] parts = identifier.split("_");
        if (parts.length < 2) return null;

        try {
            int slot = Integer.parseInt(parts[0]);
            String type = parts[1];

            SlotData data = slotDataMap.getOrDefault(slot, new SlotData("", new String[]{"", "", ""}, System.currentTimeMillis(), 0));
            if (data.getRemainingTime() <= 0) {
                slotDataMap.remove(slot);
                return getDefaultValueForPlaceholder();
            }

            switch (type) {
                case "player":
                    return data.getPlayerName();
                case "message1":
                    return data.getMessageLine(0);
                case "message2":
                    return data.getMessageLine(1);
                case "message3":
                    return data.getMessageLine(2);
                case "time":
                    return String.valueOf(data.getRemainingTime() / 1000);
                case "timeformatted":
                    return formatTime(data.getRemainingTime());
                default:
                    return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void setSlotData(int slot, String playerName, String message, long remainingTime) {
        String[] lines = splitMessageIntoLines(message);
        slotDataMap.put(slot, new SlotData(playerName, lines, System.currentTimeMillis(), remainingTime));
    }

    public void removeSlotData(int slot) {
        slotDataMap.remove(slot);
    }

    public void updateSlotMessage(int slot, String message) {
        if (slotDataMap.containsKey(slot)) {
            SlotData currentData = slotDataMap.get(slot);
            slotDataMap.put(slot, new SlotData(currentData.playerName, splitMessageIntoLines(message), currentData.startTime, currentData.duration));
        }
    }

    private String[] splitMessageIntoLines(String message) {
        String[] words = message.split(" ");
        String[] lines = new String[]{"", "", ""};
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            // Gestion des mots très longs qui dépassent 40 caractères à eux seuls
            while (word.length() > 40) {
                int spaceLeft = 40 - currentLine.length();
                if (spaceLeft > 0) {
                    currentLine.append(word.substring(0, spaceLeft));
                    word = word.substring(spaceLeft);
                }

                // CORRECTION : Persistance de la couleur lors de la coupe d'un mot long
                String lastColors = ChatColor.getLastColors(currentLine.toString());
                assignLine(lines, currentLine);
                currentLine.append(lastColors); // On applique la couleur au début de la nouvelle ligne

                if (spaceLeft <= 0) {
                    // Si la ligne était déjà pleine, on coupe brutalement le mot
                    if (currentLine.length() + 40 <= currentLine.capacity()) { // Check basique
                        // Logique originale conservée ici pour la structure,
                        // mais le mot est réduit par la boucle while
                        currentLine.append(word.substring(0, 40));
                        word = word.substring(40);
                    } else {
                        // Fallback safe si la logique précédente échoue
                        String chunk = word.substring(0, Math.min(word.length(), 40));
                        currentLine.append(chunk);
                        word = word.substring(chunk.length());
                    }
                }
            }

            if (currentLine.length() + word.length() <= 40) {
                currentLine.append(word);
                if (word.length() < 40) currentLine.append(" ");
            } else {
                // CORRECTION : Persistance de la couleur pour le saut de ligne standard
                String lastColors = ChatColor.getLastColors(currentLine.toString());

                assignLine(lines, currentLine);

                // On commence la nouvelle ligne avec les couleurs de la précédente + le mot
                currentLine.append(lastColors).append(word).append(" ");
            }
        }

        assignLine(lines, currentLine);
        return lines;
    }

    private void assignLine(String[] lines, StringBuilder currentLine) {
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                lines[i] = currentLine.toString().trim();
                currentLine.setLength(0);
                break;
            }
        }
    }

    private String formatTime(long timeInMillis) {
        long hours = timeInMillis / 3600000;
        long minutes = (timeInMillis % 3600000) / 60000;
        long seconds = (timeInMillis % 60000) / 1000;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    public boolean isSlotOccupiedByPlayer(int slot, String playerName) {
        SlotData data = slotDataMap.get(slot);
        return data != null && data.getPlayerName().equals(playerName);
    }

    private String getDefaultValueForPlaceholder() {
        return "none";
    }

    public boolean isSlotOccupied(int slot) {
        return slotDataMap.containsKey(slot) && (slotDataMap.get(slot).getRemainingTime() > 0);
    }

    public boolean isSlotOccupiedWithMessage(int slot) {
        SlotData data = slotDataMap.get(slot);
        return data != null && data.getRemainingTime() > 0 && !data.getMessageLine(0).isEmpty();
    }

    public Map<Integer, SlotData> getSlotDataMap() {
        return slotDataMap;
    }

    public boolean hasSlot(String playerName) {
        return slotDataMap.values().stream().anyMatch(data -> data.getPlayerName().equals(playerName));
    }

    public int getSlotByPlayerName(String playerName) {
        return slotDataMap.entrySet().stream()
            .filter(entry -> entry.getValue().getPlayerName().equalsIgnoreCase(playerName))
            .map(Map.Entry::getKey)
            .findFirst().orElse(-1);
    }

    public static class SlotData {
        private final String playerName;
        private final String[] messageLines;
        private final long startTime;
        private final long duration;

        public SlotData(String playerName, String[] messageLines, long startTime, long duration) {
            this.playerName = playerName;
            this.messageLines = messageLines;
            this.startTime = startTime;
            this.duration = duration;
        }

        public String[] getMessageLines() {
            return messageLines;
        }

        public String getPlayerName() {
            return playerName;
        }

        public String getMessageLine(int index) {
            return index < messageLines.length ? messageLines[index] : "";
        }

        public long getRemainingTime() {
            long elapsed = System.currentTimeMillis() - this.startTime;
            return Math.max(0, this.duration - elapsed);
        }
    }
}
