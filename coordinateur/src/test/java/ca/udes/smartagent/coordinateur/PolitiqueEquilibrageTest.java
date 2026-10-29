package ca.udes.smartagent.coordinateur;

import ca.udes.smartagent.coordinateur.PolitiqueEquilibrage.Decision;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests de la règle d'équilibrage seule (aucun réseau). */
class PolitiqueEquilibrageTest {

    @Test
    void pasDActionJusquAuSeuilInclus() {
        assertTrue(PolitiqueEquilibrage.evaluer("salleA", 21.0, "salleB", 25.0).isEmpty());
        assertTrue(PolitiqueEquilibrage.evaluer("salleA", 21.0, "salleB", 22.0).isEmpty());
    }

    @Test
    void auDelaDuSeuilLaPlusChaudeRecoitLaMoyenne() {
        Decision d = PolitiqueEquilibrage.evaluer("salleA", 27.0, "salleB", 21.0).orElseThrow();
        assertEquals("salleA", d.pieceCible());
        assertEquals(24.0, d.consigne(), 1e-9);
        assertEquals(6.0, d.ecart(), 1e-9);
    }

    @Test
    void laDecisionNeDependPasDeLOrdreDeLaPaire() {
        Decision d1 = PolitiqueEquilibrage.evaluer("salleB", 20.0, "cuisine", 26.0).orElseThrow();
        Decision d2 = PolitiqueEquilibrage.evaluer("cuisine", 26.0, "salleB", 20.0).orElseThrow();
        assertEquals("cuisine", d1.pieceCible());
        assertEquals(d1, d2);
    }

    @Test
    void consigneArrondieAuDemiDegre() {
        // moyenne 23,6 → 23,5 ; moyenne 23,8 → 24,0
        assertEquals(23.5, PolitiqueEquilibrage.evaluer("a", 26.2, "b", 21.0).orElseThrow().consigne(), 1e-9);
        assertEquals(24.0, PolitiqueEquilibrage.evaluer("a", 26.6, "b", 21.0).orElseThrow().consigne(), 1e-9);
    }

    @Test
    void consigneBorneeALaPlageAccepteeParLesAgents() {
        Optional<Decision> d = PolitiqueEquilibrage.evaluer("a", 45.0, "b", 30.0);
        assertEquals(30.0, d.orElseThrow().consigne(), 1e-9);
    }

    @Test
    void temperatureInconnueAucuneDecision() {
        assertTrue(PolitiqueEquilibrage.evaluer("a", Double.NaN, "b", 30.0).isEmpty());
    }
}
