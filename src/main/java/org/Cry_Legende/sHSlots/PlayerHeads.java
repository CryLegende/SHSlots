package org.Cry_Legende.sHSlots;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Tête d'un joueur du réseau, connecté ou non. Le skin vient de SHSkins quand il est installé ; sans lui, la tête
 * n'a que l'UUID et le pseudo (le client affiche alors Steve ou Alex), mais le serveur ne fait jamais de recherche
 * Mojang.
 */
public final class PlayerHeads {

    private PlayerHeads() {
    }

    /**
     * @param uuid UUID du joueur, ou null pour celui de son pseudo sur le réseau
     * @param name pseudo du joueur, ou null si seul l'UUID est connu
     */
    public static ItemStack create(UUID uuid, String name) {
        if (Bukkit.getPluginManager().isPluginEnabled("SHSkins")) {
            return SHSkinsHook.head(uuid, name);
        }
        UUID id = uuid != null ? uuid : offlineUuid(name);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        head.editMeta(SkullMeta.class, meta -> meta.setPlayerProfile(Bukkit.createProfile(id, name)));
        return head;
    }

    /** UUID d'un joueur sur le réseau SkyHand (mode offline) : md5 de « OfflinePlayer:pseudo ». */
    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
