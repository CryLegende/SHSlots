package org.Cry_Legende.sHSlots;

import org.Cry_Legende.sHSlots.network.SlotsDb;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.StringUtil;
import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class MainCommand implements CommandExecutor, TabCompleter {

    private final SHSlots plugin;
    private final Map<String, CommandData> awaitingResponse;
    private final PlaceholderHandler placeholderHandler;

    public MainCommand(SHSlots plugin, Map<String, CommandData> awaitingResponse, PlaceholderHandler placeholderHandler) {
        this.plugin = plugin;
        this.awaitingResponse = awaitingResponse;
        this.placeholderHandler = placeholderHandler;
    }

    /** Sous-commandes d'administration, dans l'ordre de la ligne d'usage ; le nœud est shslots.admin.<nom>. */
    private static final List<String> SUBS = List.of("set", "remove", "blacklist", "menu", "import", "list", "reload");

    private boolean can(CommandSender sender, String sub) {
        return sender.hasPermission("shslots.admin." + sub);
    }

    /** Sous-commandes que l'expéditeur peut utiliser ; import n'existe qu'en mode réseau. */
    private List<String> allowed(CommandSender sender) {
        List<String> allowed = new ArrayList<>();
        for (String sub : SUBS) {
            if (sub.equals("import") && plugin.network() == null) continue;
            if (can(sender, sub)) allowed.add(sub);
        }
        return allowed;
    }

    /** « admin » est l'ancien nom de « menu ». */
    private static String canonical(String sub) {
        return sub.equals("admin") ? "menu" : sub;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        List<String> allowed = allowed(sender);
        if (allowed.isEmpty()) {
            sender.sendMessage(plugin.getColoredMessage("messages.noPermission", "§cVous n'avez pas la permission d'utiliser cette commande."));
            return true;
        }

        String subCommand = args.length == 0 ? "" : canonical(args[0].toLowerCase());
        if (!allowed.contains(subCommand)) {
            sendUsage(sender, allowed);
            return true;
        }
        switch (subCommand) {
            case "set":
                handleSet(sender, args);
                break;
            case "remove":
                handleRemove(sender, args);
                break;
            case "blacklist":
                handleBlacklist(sender, args);
                break;
            case "import":
                handleImport(sender);
                break;
            case "menu":
                if (sender instanceof Player) {
                    plugin.getAdminGuiManager().openMainAdminMenu((Player) sender);
                } else {
                    sender.sendMessage(plugin.getColoredMessage("messages.playerOnly", "§cSeul un joueur peut ouvrir le menu."));
                }
                break;
            case "list":
                handleList(sender);
                break;
            default:
                handleReload(sender);
                break;
        }
        return true;
    }

    /** Une ligne d'usage qui ne liste que les sous-commandes permises à l'expéditeur. */
    private void sendUsage(CommandSender sender, List<String> allowed) {
        sender.sendMessage(text("messages.usage").replace("{commands}", String.join("|", allowed)));
    }

    /** Texte coloré de messages.yml ; les clés absentes du fichier prennent le texte du jar. */
    private String text(String path) {
        String value = plugin.messages().string(path);
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    private void handleReload(CommandSender sender) {
        try {
            plugin.reload();
            sender.sendMessage(text("messages.reloaded"));
        } catch (SHSlots.ReloadException e) {
            plugin.getLogger().warning(e.getMessage() + " (l'ancienne configuration reste active)");
            sender.sendMessage(text("messages.reload-failed").replace("{file}", e.file).replace("{error}", e.detail));
        }
    }

    /** Slots occupés : numéro, joueur, temps restant et message. */
    private void handleList(CommandSender sender) {
        placeholderHandler.purgeExpired();
        List<Integer> slots = new ArrayList<>(placeholderHandler.getSlotDataMap().keySet());
        java.util.Collections.sort(slots);
        if (slots.isEmpty()) {
            sender.sendMessage(text("messages.listEmpty"));
            return;
        }
        sender.sendMessage(text("messages.listHeader").replace("{count}", String.valueOf(slots.size())));
        for (int slot : slots) {
            PlaceholderHandler.SlotData data = placeholderHandler.getSlotDataMap().get(slot);
            if (data == null) continue;
            String message = String.join(" ", data.getMessageLines()).trim();
            sender.sendMessage(text("messages.listLine")
                .replace("{slot}", String.valueOf(slot))
                .replace("{player}", data.getPlayerName())
                .replace("{time}", placeholderHandler.formatTime(data.getRemainingTime()))
                .replace("{message}", message.isEmpty() ? "-" : message));
        }
    }

    /** blacklist list : les joueurs de la blacklist avec la date de leur sanction. */
    private void handleBlacklistList(CommandSender sender) {
        List<String> blacklist = new ArrayList<>(plugin.getBlacklistedPlayers());
        if (blacklist.isEmpty()) {
            sender.sendMessage(text("messages.blacklistListEmpty"));
            return;
        }
        java.util.Collections.sort(blacklist);
        sender.sendMessage(text("messages.blacklistListHeader").replace("{count}", String.valueOf(blacklist.size())));
        for (String name : blacklist) {
            sender.sendMessage(text("messages.blacklistListLine")
                .replace("{player}", name)
                .replace("{date}", plugin.getPlayerDataManager().getBlacklistDate(name)));
        }
    }

    private void handleSet(CommandSender sender, String[] args) {
        if (args.length != 4) {
            sender.sendMessage(plugin.getColoredMessage("messages.setUsage", "§cUsage: /shslots set <slot> <pseudo> <temps>"));
            return;
        }

        String targetPlayerName = args[2];
        if (plugin.getBlacklistedPlayers().contains(targetPlayerName.toLowerCase())) {
            sender.sendMessage(plugin.getColoredMessage("messages.playerBlacklisted", "§cCe joueur est blacklisté."));
            return;
        }

        int slot;
        try {
            slot = Integer.parseInt(args[1]);
            if (slot < 1 || slot > plugin.getMaxSlots()) {
                sender.sendMessage(plugin.getColoredMessage("messages.invalidSlotNumber", "§cLe numéro de slot doit être entre 1 et " + plugin.getMaxSlots() + "."));
                return;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getColoredMessage("messages.invalidSlotNumberFormat", "§cNuméro de slot invalide."));
            return;
        }

        if (plugin.network() != null) {
            handleSetNetwork(sender, slot, targetPlayerName, args[3]);
            return;
        }

        Player targetPlayer = Bukkit.getPlayer(targetPlayerName);
        if (targetPlayer == null) {
            sender.sendMessage(plugin.getColoredMessage("messages.playerNotFound", "§cJoueur non trouvé."));
            return;
        }
        // getPlayer accepte un début de pseudo : on garde le vrai pseudo, sinon le chat ne correspond jamais
        targetPlayerName = targetPlayer.getName();
        if (plugin.getBlacklistedPlayers().contains(targetPlayerName.toLowerCase())) {
            sender.sendMessage(plugin.getColoredMessage("messages.playerBlacklisted", "§cCe joueur est blacklisté."));
            return;
        }

        placeholderHandler.purgeExpired();
        if (placeholderHandler.isSlotOccupied(slot) && !placeholderHandler.isSlotOccupiedByPlayer(slot, targetPlayerName)) {
            sender.sendMessage(plugin.getColoredMessage("messages.slotOccupied", "§cCe slot est déjà occupé."));
            return;
        }

        if ((placeholderHandler.hasSlot(targetPlayerName) || awaitingResponse.containsKey(targetPlayerName)) && !placeholderHandler.isSlotOccupiedByPlayer(slot, targetPlayerName)) {
            sender.sendMessage(plugin.getColoredMessage("messages.alreadyOwnsSlot", "§cCe joueur possède déjà un slot."));
            return;
        }

        long durationInMilliseconds = ChatListener.parseDuration(args[3]);
        placeholderHandler.setSlotData(slot, targetPlayerName, "", durationInMilliseconds);
        plugin.saveSlotsData();

        CommandData commandData = new CommandData(slot, targetPlayerName, args[3]);
        CommandData previous = awaitingResponse.put(targetPlayerName, commandData);
        // L'ancien délai retirerait sinon la nouvelle attente avant ses 2 minutes
        if (previous != null) previous.cancelCountdown();

        String waitingName = targetPlayerName;
        BukkitRunnable countdownTask = new BukkitRunnable() {
            @Override
            public void run() {
                awaitingResponse.remove(waitingName, commandData);
            }
        };
        countdownTask.runTaskLater(this.plugin, 2400L); // 2 minutes
        commandData.setCountdownTask(countdownTask);

        targetPlayer.sendMessage(plugin.getColoredMessage("messages.enterMessageForSlot", "§eEntrez le message pour le slot %slot%...").replace("%slot%", String.valueOf(slot)));
    }

    /**
     * Mode réseau : le joueur visé peut être sur un autre serveur. La base tranche « slot occupé » et « déjà un slot »,
     * puis le bus invite le joueur à saisir son message dans le chat du serveur où il se trouve.
     */
    private void handleSetNetwork(CommandSender sender, int slot, String typedName, String duration) {
        NetworkSlots net = plugin.network();
        // Ce serveur d'abord (un début de pseudo est accepté comme en mode local), puis le reste du réseau
        Player local = Bukkit.getPlayer(typedName);
        String targetPlayerName = local != null ? local.getName() : net.find(typedName).orElse(null);
        if (targetPlayerName == null) {
            sender.sendMessage(plugin.getColoredMessage("messages.playerNotFound", "§cJoueur non trouvé."));
            return;
        }
        if (plugin.getBlacklistedPlayers().contains(targetPlayerName.toLowerCase())) {
            sender.sendMessage(plugin.getColoredMessage("messages.playerBlacklisted", "§cCe joueur est blacklisté."));
            return;
        }

        long durationInMilliseconds = ChatListener.parseDuration(duration);
        net.assign(slot, targetPlayerName, durationInMilliseconds).whenComplete((result, error) -> plugin.runMain(() -> {
            if (error != null) {
                sender.sendMessage(plugin.networkMessage("database_error"));
            } else if (result == SlotsDb.AssignResult.SLOT_OCCUPIED) {
                sender.sendMessage(plugin.getColoredMessage("messages.slotOccupied", "§cCe slot est déjà occupé."));
            } else if (result == SlotsDb.AssignResult.ALREADY_OWNS) {
                sender.sendMessage(plugin.getColoredMessage("messages.alreadyOwnsSlot", "§cCe joueur possède déjà un slot."));
            }
        }));
    }

    /** /shslots import : reprend slots_data.json, users.yml et la blacklist de config.yml dans la base du réseau. */
    private void handleImport(CommandSender sender) {
        plugin.network().importLegacy().whenComplete((counts, error) -> plugin.runMain(() -> {
            if (error != null) {
                plugin.getLogger().warning("Importation impossible : " + (error.getCause() != null ? error.getCause() : error));
                sender.sendMessage(plugin.networkMessage("import_error"));
            } else {
                sender.sendMessage(plugin.networkMessage("import_success")
                    .replace("%SLOTS%", String.valueOf(counts.slots()))
                    .replace("%USERS%", String.valueOf(counts.users())));
            }
        }));
    }

    private void handleRemove(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage(plugin.getColoredMessage("messages.removeUsage", "§cUsage: /shslots remove <pseudo>"));
            return;
        }

        String targetPlayerName = args[1];
        if (plugin.network() != null) {
            plugin.network().removeByPlayer(targetPlayerName).whenComplete((removed, error) -> plugin.runMain(() -> {
                if (error != null) {
                    sender.sendMessage(plugin.networkMessage("database_error"));
                } else if (!removed) {
                    sender.sendMessage(plugin.getColoredMessage("messages.playerNotInSlot", "§cCe joueur ne possède aucun slot."));
                } else {
                    sender.sendMessage(plugin.getColoredMessage("messages.playerRemoved", "§aLe joueur a été retiré du slot."));
                }
            }));
            return;
        }
        placeholderHandler.purgeExpired();
        int slot = placeholderHandler.getSlotByPlayerName(targetPlayerName);

        if (slot == -1) {
            sender.sendMessage(plugin.getColoredMessage("messages.playerNotInSlot", "§cCe joueur ne possède aucun slot."));
            return;
        }

        PlaceholderHandler.SlotData data = placeholderHandler.getSlotDataMap().get(slot);
        placeholderHandler.removeSlotData(slot);
        plugin.saveSlotsData();
        // Plus de message à attendre pour un slot retiré
        CommandData pending = data == null ? null : awaitingResponse.remove(data.getPlayerName());
        if (pending != null) pending.cancelCountdown();
        sender.sendMessage(plugin.getColoredMessage("messages.playerRemoved", "§aLe joueur a été retiré du slot."));
    }

    private void handleBlacklist(CommandSender sender, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            handleBlacklistList(sender);
            return;
        }
        if (args.length != 3 || (!args[1].equalsIgnoreCase("add") && !args[1].equalsIgnoreCase("remove"))) {
            sender.sendMessage(plugin.getColoredMessage("messages.blacklistUsage", "§cUsage: /shslots blacklist <add|remove> <pseudo>"));
            return;
        }

        String action = args[1].toLowerCase();
        String playerName = args[2].toLowerCase();
        if (plugin.network() != null) {
            handleBlacklistNetwork(sender, action, playerName);
            return;
        }
        List<String> blacklist = plugin.getBlacklistedPlayers();

        if (action.equals("add")) {
            if (blacklist.contains(playerName)) {
                sender.sendMessage(plugin.getColoredMessage("messages.blacklistAlreadyAdded", "§eCe joueur est déjà dans la blacklist."));
            } else {
                blacklist.add(playerName);
                // Sauvegarde date blacklist
                plugin.getPlayerDataManager().setBlacklistDate(playerName);

                sender.sendMessage(plugin.getColoredMessage("messages.blacklistAdded", "§aJoueur ajouté à la blacklist."));
            }
        } else if (action.equals("remove")) {
            if (!blacklist.contains(playerName)) {
                sender.sendMessage(plugin.getColoredMessage("messages.blacklistNotPresent", "§eCe joueur n'est pas dans la blacklist."));
            } else {
                blacklist.remove(playerName);
                // Retrait date blacklist
                plugin.getPlayerDataManager().removeBlacklistDate(playerName);

                sender.sendMessage(plugin.getColoredMessage("messages.blacklistRemoved", "§aJoueur retiré de la blacklist."));
            }
        }
        plugin.getConfig().set("blacklist", blacklist);
        plugin.saveConfig();
    }


    /** Mode réseau : la blacklist est dans la base partagée, config.yml n'est pas réécrit. */
    private void handleBlacklistNetwork(CommandSender sender, String action, String playerName) {
        NetworkSlots net = plugin.network();
        boolean add = action.equals("add");
        (add ? net.blacklistAdd(playerName) : net.blacklistRemove(playerName)).whenComplete((changed, error) -> plugin.runMain(() -> {
            if (error != null) {
                sender.sendMessage(plugin.networkMessage("database_error"));
            } else if (add) {
                sender.sendMessage(changed
                    ? plugin.getColoredMessage("messages.blacklistAdded", "§aJoueur ajouté à la blacklist.")
                    : plugin.getColoredMessage("messages.blacklistAlreadyAdded", "§eCe joueur est déjà dans la blacklist."));
            } else {
                sender.sendMessage(changed
                    ? plugin.getColoredMessage("messages.blacklistRemoved", "§aJoueur retiré de la blacklist.")
                    : plugin.getColoredMessage("messages.blacklistNotPresent", "§eCe joueur n'est pas dans la blacklist."));
            }
        }));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        final List<String> completions = new ArrayList<>();
        List<String> allowed = allowed(sender);
        if (allowed.isEmpty()) {
            return completions;
        }

        // /shslots <subcommand>
        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], allowed, completions);
            return completions;
        }
        String sub = canonical(args[0].toLowerCase());
        if (!allowed.contains(sub)) {
            return completions;
        }
        // /shslots set <slot> <player>
        if (args.length == 3 && sub.equals("set")) {
            StringUtil.copyPartialMatches(args[2], plugin.onlinePlayerNames(), completions);
        }
        // /shslots remove <player>
        else if (args.length == 2 && sub.equals("remove")) {
            List<String> playersWithSlots = placeholderHandler.getSlotDataMap().values().stream()
                .map(PlaceholderHandler.SlotData::getPlayerName)
                .collect(Collectors.toList());
            StringUtil.copyPartialMatches(args[1], playersWithSlots, completions);
        }
        // /shslots blacklist <add|remove|list>
        else if (args.length == 2 && sub.equals("blacklist")) {
            StringUtil.copyPartialMatches(args[1], Arrays.asList("add", "remove", "list"), completions);
        }
        // /shslots blacklist add <player>
        else if (args.length == 3 && sub.equals("blacklist") && args[1].equalsIgnoreCase("add")) {
            StringUtil.copyPartialMatches(args[2], plugin.onlinePlayerNames(), completions);
        }
        // /shslots blacklist remove <player>
        else if (args.length == 3 && sub.equals("blacklist") && args[1].equalsIgnoreCase("remove")) {
            StringUtil.copyPartialMatches(args[2], plugin.getBlacklistedPlayers(), completions);
        }

        return completions;
    }
}
