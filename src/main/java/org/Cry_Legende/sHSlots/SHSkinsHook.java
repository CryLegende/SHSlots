package org.Cry_Legende.sHSlots;

import org.Cry_Legende.sHSkins.api.SHSkinsAPI;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** Pont vers SHSkins. Cette classe n'est chargée que si SHSkins est installé : seule {@link PlayerHeads} l'appelle. */
final class SHSkinsHook {

    private SHSkinsHook() {
    }

    static ItemStack head(UUID uuid, String name) {
        return SHSkinsAPI.createHead(uuid, name);
    }
}
