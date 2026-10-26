package org.Cry_Legende.sHSlots;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class AdminGuiManager implements Listener {

    private final SHSlots plugin;

    public AdminGuiManager(SHSlots plugin) {
        this.plugin = plugin;
    }

    // --- HOLDERS ---
    private static class AdminMenuHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static class BlacklistMenuHolder implements InventoryHolder {
        private final int page;

        public BlacklistMenuHolder(int page) {
            this.page = page;
        }

        public int getPage() {
            return page;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static class OnlinePlayersMenuHolder implements InventoryHolder {
        private final int page;

        public OnlinePlayersMenuHolder(int page) {
            this.page = page;
        }

        public int getPage() {
            return page;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static class SlotsListMenuHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static class SlotDetailsMenuHolder implements InventoryHolder {
        private final int slotId;

        public SlotDetailsMenuHolder(int slotId) {
            this.slotId = slotId;
        }

        public int getSlotId() {
            return slotId;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    // --- MENUS ---

    public void openMainAdminMenu(Player player) {
        String title = color(plugin.getConfig().getString("gui.main.title", "&8&lMenu &8| Gestion SHSlots"));
        Inventory inv = Bukkit.createInventory(new AdminMenuHolder(), 27, title);

        fillBorders(inv);

        inv.setItem(11, createItem(Material.RED_NETHER_BRICK_WALL,
            plugin.getConfig().getString("gui.main.blacklist_button.name"),
            plugin.getConfig().getStringList("gui.main.blacklist_button.lore")));

        inv.setItem(15, createItem(Material.BEACON,
            plugin.getConfig().getString("gui.main.slots_button.name"),
            plugin.getConfig().getStringList("gui.main.slots_button.lore")));

        player.openInventory(inv);
        playSound(player);
    }

    public void openBlacklistMenu(Player player, int page) {
        List<String> blacklistedNames = plugin.getBlacklistedPlayers();
        int[] innerSlots = getInnerSlots();
        int maxItems = innerSlots.length;
        int totalPages = (int) Math.ceil((double) blacklistedNames.size() / maxItems);
        if (totalPages == 0) totalPages = 1;
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;

        String title = color(plugin.getConfig().getString("gui.blacklist.title", "&8Blacklist")
            .replace("%page%", String.valueOf(page))
            .replace("%max_page%", String.valueOf(totalPages))); // CORRIGÉ

        Inventory inv = Bukkit.createInventory(new BlacklistMenuHolder(page), 54, title);
        fillBorders(inv);

        int startIndex = (page - 1) * maxItems;
        int endIndex = Math.min(startIndex + maxItems, blacklistedNames.size());

        List<String> loreTemplate = plugin.getConfig().getStringList("gui.blacklist.skull_lore");

        for (int i = startIndex; i < endIndex; i++) {
            String targetName = blacklistedNames.get(i);
            OfflinePlayer offPlayer = Bukkit.getOfflinePlayer(targetName);
            int slotIndex = innerSlots[i - startIndex];

            String date = plugin.getPlayerDataManager().getBlacklistDate(targetName);
            String lastMsg = plugin.getPlayerDataManager().getLastMessage(targetName);

            List<String> finalLore = new ArrayList<>();
            for (String line : loreTemplate) {
                finalLore.add(line.replace("%date%", date).replace("%last_message%", lastMsg));
            }

            plugin.getTextureService().getPlayerHead(offPlayer, (head) -> {
                ItemMeta meta = head.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(color("&c" + targetName));
                    meta.setLore(colorList(finalLore));
                    head.setItemMeta(meta);
                }
                if (player.getOpenInventory().getTopInventory().equals(inv)) inv.setItem(slotIndex, head);
            });
        }

        setNavButtons(inv, page, totalPages);
        inv.setItem(49, createItem(Material.EMERALD,
            plugin.getConfig().getString("gui.blacklist.add_button.name"),
            plugin.getConfig().getStringList("gui.blacklist.add_button.lore")));

        inv.setItem(53, createItem(Material.BARRIER, "&cRetour Menu", null));

        player.openInventory(inv);
        playSound(player);
    }

    public void openOnlinePlayersMenu(Player player, int page) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        List<String> blacklisted = plugin.getBlacklistedPlayers();
        online.removeIf(p -> blacklisted.contains(p.getName().toLowerCase()));

        int[] innerSlots = getInnerSlots();
        int maxItems = innerSlots.length;
        int totalPages = (int) Math.ceil((double) online.size() / maxItems);
        if (totalPages == 0) totalPages = 1;
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;

        String title = color(plugin.getConfig().getString("gui.online_players.title", "&8Joueurs")
            .replace("%page%", String.valueOf(page))
            .replace("%max_page%", String.valueOf(totalPages))); // CORRIGÉ (ajout %max_page%)

        Inventory inv = Bukkit.createInventory(new OnlinePlayersMenuHolder(page), 54, title);
        fillBorders(inv);

        int startIndex = (page - 1) * maxItems;
        int endIndex = Math.min(startIndex + maxItems, online.size());
        List<String> skullLore = plugin.getConfig().getStringList("gui.online_players.skull_lore");

        for (int i = startIndex; i < endIndex; i++) {
            Player target = online.get(i);
            int slotIndex = innerSlots[i - startIndex];

            // Appel TextureService qui gère maintenant correctement les joueurs en ligne sur crack
            plugin.getTextureService().getPlayerHead(target, (head) -> {
                ItemMeta meta = head.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(color("&a" + target.getName()));
                    meta.setLore(colorList(skullLore));
                    head.setItemMeta(meta);
                }
                if (player.getOpenInventory().getTopInventory().equals(inv)) inv.setItem(slotIndex, head);
            });
        }

        setNavButtons(inv, page, totalPages);
        inv.setItem(49, createItem(Material.PAPER, plugin.getConfig().getString("gui.slot_details.back_button.name"), null));

        player.openInventory(inv);
        playSound(player);
    }

    public void openSlotsListMenu(Player player) {
        String title = color(plugin.getConfig().getString("gui.slots_list.title", "&8Slots Actifs"));
        Inventory inv = Bukkit.createInventory(new SlotsListMenuHolder(), 27, title);
        fillBorders(inv);

        int maxSlots = plugin.getMaxSlots();
        PlaceholderHandler handler = plugin.getPlaceholderHandler();
        int[] slotPositions = (maxSlots > 3) ? new int[]{10, 11, 12, 13, 14, 15, 16} : new int[]{11, 13, 15};

        for (int i = 1; i <= maxSlots; i++) {
            if ((i - 1) >= slotPositions.length) break;

            final int currentSlotId = i;
            final int guiSlotIndex = slotPositions[i - 1];

            if (handler.isSlotOccupied(i)) {
                PlaceholderHandler.SlotData data = handler.getSlotDataMap().get(i);
                OfflinePlayer owner = Bukkit.getOfflinePlayer(data.getPlayerName());
                String timeFormatted = formatTime(data.getRemainingTime());

                plugin.getTextureService().getPlayerHead(owner, (head) -> {
                    ItemMeta meta = head.getItemMeta();
                    if (meta != null) {
                        String name = plugin.getConfig().getString("gui.slots_list.item_occupied.name", "&eSlot %slot%")
                            .replace("%slot%", String.valueOf(currentSlotId))
                            .replace("%player%", data.getPlayerName());
                        meta.setDisplayName(color(name));

                        List<String> rawLore = plugin.getConfig().getStringList("gui.slots_list.item_occupied.lore");
                        List<String> lore = new ArrayList<>();
                        for (String s : rawLore)
                            lore.add(s.replace("%time%", timeFormatted).replace("%player%", data.getPlayerName()));

                        meta.setLore(colorList(lore));
                        head.setItemMeta(meta);
                    }
                    if (player.getOpenInventory().getTopInventory().equals(inv)) inv.setItem(guiSlotIndex, head);
                });
            } else {
                String name = plugin.getConfig().getString("gui.slots_list.item_free.name")
                    .replace("%slot%", String.valueOf(currentSlotId));
                List<String> lore = plugin.getConfig().getStringList("gui.slots_list.item_free.lore");
                inv.setItem(guiSlotIndex, createItem(Material.LIME_STAINED_GLASS_PANE, name, lore));
            }
        }

        inv.setItem(22, createItem(Material.PAPER, plugin.getConfig().getString("gui.slot_details.back_button.name"), null));
        player.openInventory(inv);
        playSound(player);
    }

    public void openSlotDetailsMenu(Player player, int slotId) {
        PlaceholderHandler handler = plugin.getPlaceholderHandler();

        if (!handler.isSlotOccupied(slotId)) {
            player.sendMessage(color("&cCe slot n'est plus occupé."));
            openSlotsListMenu(player);
            return;
        }

        PlaceholderHandler.SlotData data = handler.getSlotDataMap().get(slotId);
        String title = color(plugin.getConfig().getString("gui.slot_details.title", "&8Gestion")
            .replace("%slot%", String.valueOf(slotId)));

        Inventory inv = Bukkit.createInventory(new SlotDetailsMenuHolder(slotId), 27, title);
        fillBorders(inv);

        String timeFormatted = formatTime(data.getRemainingTime());
        String fullMessage = String.join(" ", data.getMessageLines());

        OfflinePlayer owner = Bukkit.getOfflinePlayer(data.getPlayerName());

        plugin.getTextureService().getPlayerHead(owner, (head) -> {
            ItemMeta meta = head.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(color(plugin.getConfig().getString("gui.slot_details.info_item.name")));
                List<String> rawLore = plugin.getConfig().getStringList("gui.slot_details.info_item.lore");
                List<String> lore = new ArrayList<>();
                for (String s : rawLore) {
                    lore.add(s.replace("%player%", data.getPlayerName())
                        .replace("%time%", timeFormatted)
                        .replace("%message%", fullMessage));
                }
                meta.setLore(colorList(lore));
                head.setItemMeta(meta);
            }
            if (player.getOpenInventory().getTopInventory().equals(inv)) inv.setItem(13, head);
        });

        inv.setItem(11, createItem(Material.TNT,
            plugin.getConfig().getString("gui.slot_details.delete_button.name"),
            plugin.getConfig().getStringList("gui.slot_details.delete_button.lore")));

        inv.setItem(15, createItem(Material.REDSTONE_BLOCK,
            plugin.getConfig().getString("gui.slot_details.blacklist_button.name"),
            plugin.getConfig().getStringList("gui.slot_details.blacklist_button.lore")));

        inv.setItem(22, createItem(Material.PAPER,
            plugin.getConfig().getString("gui.slot_details.back_button.name"),
            null));

        player.openInventory(inv);
        playSound(player);
    }


    // --- CLICS ---

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        InventoryHolder holder = event.getInventory().getHolder();

        if (holder instanceof AdminMenuHolder || holder instanceof BlacklistMenuHolder ||
            holder instanceof OnlinePlayersMenuHolder || holder instanceof SlotsListMenuHolder ||
            holder instanceof SlotDetailsMenuHolder) {

            event.setCancelled(true);
            // Un clic dans l'inventaire du joueur (TNT, papier, tête...) ne doit pas déclencher d'action
            if (event.getClickedInventory() != event.getView().getTopInventory()) return;
            if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;
            Player player = (Player) event.getWhoClicked();
            ItemStack clicked = event.getCurrentItem();

            // 1. MENU PRINCIPAL
            if (holder instanceof AdminMenuHolder) {
                if (clicked.getType() == Material.RED_NETHER_BRICK_WALL) {
                    playSound(player);
                    openBlacklistMenu(player, 1);
                } else if (clicked.getType() == Material.BEACON) {
                    playSound(player);
                    openSlotsListMenu(player);
                }
            }
            // 2. BLACKLIST
            else if (holder instanceof BlacklistMenuHolder) {
                BlacklistMenuHolder menu = (BlacklistMenuHolder) holder;
                if (clicked.getType() == Material.ARROW) {
                    playSound(player);
                    if (clicked.getItemMeta().getDisplayName().contains("Suivante"))
                        openBlacklistMenu(player, menu.getPage() + 1);
                    else openBlacklistMenu(player, menu.getPage() - 1);
                } else if (clicked.getType() == Material.EMERALD) {
                    playSound(player);
                    openOnlinePlayersMenu(player, 1);
                } else if (clicked.getType() == Material.BARRIER) {
                    playSound(player);
                    openMainAdminMenu(player);
                } else if (clicked.getType() == Material.PLAYER_HEAD) {
                    String name = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
                    plugin.getBlacklistedPlayers().remove(name.toLowerCase());
                    plugin.getConfig().set("blacklist", plugin.getBlacklistedPlayers());
                    plugin.saveConfig();
                    plugin.getPlayerDataManager().removeBlacklistDate(name);
                    player.sendMessage(color(plugin.getConfig().getString("messages.blacklistRemoved")));
                    playSound(player);
                    openBlacklistMenu(player, menu.getPage());
                }
            }
            // 3. ONLINE PLAYERS
            else if (holder instanceof OnlinePlayersMenuHolder) {
                OnlinePlayersMenuHolder menu = (OnlinePlayersMenuHolder) holder;
                if (clicked.getType() == Material.ARROW) {
                    playSound(player);
                    if (clicked.getItemMeta().getDisplayName().contains("Suivante"))
                        openOnlinePlayersMenu(player, menu.getPage() + 1);
                    else openOnlinePlayersMenu(player, menu.getPage() - 1);
                } else if (clicked.getType() == Material.PAPER) {
                    playSound(player);
                    openBlacklistMenu(player, 1);
                } else if (clicked.getType() == Material.PLAYER_HEAD) {
                    String name = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
                    if (!plugin.getBlacklistedPlayers().contains(name.toLowerCase())) {
                        plugin.getBlacklistedPlayers().add(name.toLowerCase());
                        plugin.getConfig().set("blacklist", plugin.getBlacklistedPlayers());
                        plugin.saveConfig();
                        plugin.getPlayerDataManager().setBlacklistDate(name);
                        player.sendMessage(color(plugin.getConfig().getString("messages.blacklistAdded")));
                        playSound(player);
                        openOnlinePlayersMenu(player, menu.getPage());
                    }
                }
            }
            // 4. LISTE SLOTS
            else if (holder instanceof SlotsListMenuHolder) {
                if (clicked.getType() == Material.PAPER) {
                    playSound(player);
                    openMainAdminMenu(player);
                } else if (clicked.getType() == Material.PLAYER_HEAD) {
                    int slotId = -1;
                    String dn = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
                    try {
                        String number = dn.replaceAll("[^0-9]", "");
                        if (!number.isEmpty()) slotId = Integer.parseInt(number.substring(0, 1));
                    } catch (Exception ignored) {
                    }

                    if (slotId != -1 && plugin.getPlaceholderHandler().isSlotOccupied(slotId)) {
                        playSound(player);
                        openSlotDetailsMenu(player, slotId);
                    }
                }
            }
            // 5. DETAILS SLOT
            else if (holder instanceof SlotDetailsMenuHolder) {
                SlotDetailsMenuHolder menu = (SlotDetailsMenuHolder) holder;
                int slotId = menu.getSlotId();
                if (clicked.getType() == Material.PAPER) {
                    playSound(player);
                    openSlotsListMenu(player);
                } else if (clicked.getType() == Material.TNT) {
                    plugin.getPlaceholderHandler().removeSlotData(slotId);
                    plugin.saveSlotsData();
                    player.sendMessage(color("&aSlot libéré."));
                    playSound(player);
                    openSlotsListMenu(player);
                } else if (clicked.getType() == Material.REDSTONE_BLOCK) {
                    PlaceholderHandler.SlotData data = plugin.getPlaceholderHandler().getSlotDataMap().get(slotId);
                    if (data != null) {
                        String target = data.getPlayerName();
                        plugin.getPlaceholderHandler().removeSlotData(slotId);
                        plugin.saveSlotsData();
                        if (!plugin.getBlacklistedPlayers().contains(target.toLowerCase())) {
                            plugin.getBlacklistedPlayers().add(target.toLowerCase());
                            plugin.getConfig().set("blacklist", plugin.getBlacklistedPlayers());
                            plugin.saveConfig();
                            plugin.getPlayerDataManager().setBlacklistDate(target);
                        }
                        player.sendMessage(color("&c" + target + " blacklisté et slot libéré."));
                        playSound(player);
                    }
                    openSlotsListMenu(player);
                }
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof AdminMenuHolder || holder instanceof BlacklistMenuHolder ||
            holder instanceof OnlinePlayersMenuHolder || holder instanceof SlotsListMenuHolder ||
            holder instanceof SlotDetailsMenuHolder) {
            event.setCancelled(true);
        }
    }

    // --- HELPERS ---

    private ItemStack createItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (name != null) meta.setDisplayName(color(name));
            if (lore != null) meta.setLore(colorList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void fillBorders(Inventory inv) {
        ItemStack glass = createItem(Material.BLACK_STAINED_GLASS_PANE, "&r", null);
        int size = inv.getSize();
        for (int i = 0; i < 9; i++) inv.setItem(i, glass);
        for (int i = size - 9; i < size; i++) inv.setItem(i, glass);
        if (size > 27) {
            for (int i = 9; i < size - 9; i += 9) {
                inv.setItem(i, glass);
                inv.setItem(i + 8, glass);
            }
        }
    }

    private void setNavButtons(Inventory inv, int page, int totalPages) {
        int size = inv.getSize();
        if (page > 1) inv.setItem(size - 9, createItem(Material.ARROW, "&ePage Précédente", null));
        if (page < totalPages) inv.setItem(size - 1, createItem(Material.ARROW, "&ePage Suivante", null));
    }

    private void playSound(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1, 1);
    }

    private String formatTime(long timeInMillis) {
        long hours = timeInMillis / 3600000;
        long minutes = (timeInMillis % 3600000) / 60000;
        long seconds = (timeInMillis % 60000) / 1000;
        return String.format("%02dh %02dm %02ds", hours, minutes, seconds);
    }

    private String color(String s) {
        if (s == null) return "";
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private List<String> colorList(List<String> list) {
        List<String> colored = new ArrayList<>();
        if (list == null) return colored;
        for (String s : list) colored.add(color(s));
        return colored;
    }

    private int[] getInnerSlots() {
        return new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    }
}
