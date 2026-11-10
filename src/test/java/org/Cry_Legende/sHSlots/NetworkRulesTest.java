package org.Cry_Legende.sHSlots;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NetworkRulesTest {

    @Test
    void annonceLesSlotsActifsATourDeRole() {
        List<Integer> actifs = List.of(1, 3);
        assertEquals(1, NetworkSlots.nextSlot(actifs, 0));
        assertEquals(3, NetworkSlots.nextSlot(actifs, 1));
        assertEquals(1, NetworkSlots.nextSlot(actifs, 3));
    }

    @Test
    void unNouveauMeneurContinueApresLeDernierSlotAnnonce() {
        // le slot 2 vient d'être annoncé puis libéré : on passe au suivant, pas au premier
        assertEquals(3, NetworkSlots.nextSlot(List.of(1, 3), 2));
    }

    @Test
    void sansSlotActifRienAAnnoncer() {
        assertNull(NetworkSlots.nextSlot(List.of(), 2));
    }
}
