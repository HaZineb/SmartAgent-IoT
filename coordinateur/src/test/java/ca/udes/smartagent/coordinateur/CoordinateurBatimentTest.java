package ca.udes.smartagent.coordinateur;

import ca.udes.smartagent.coordinateur.PolitiqueEquilibrage.Decision;
import ca.udes.smartagent.rmi.IAgentPiece;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.rmi.NotBoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du coordinateur avec de faux agents : scénarios d'équilibrage et de panne,
 * exécutés en quelques millisecondes et sans réseau.
 */
class CoordinateurBatimentTest {

    /** « Registre » des tests : une pièce absente de la table provoque NotBoundException. */
    private final Map<String, IAgentPiece> registre = new HashMap<>();
    private final List<String> notifications = new ArrayList<>();
    private CoordinateurBatiment coordinateur;

    private FauxAgent salleA;
    private FauxAgent salleB;
    private FauxAgent cuisine;

    @BeforeEach
    void preparer() {
        salleA = new FauxAgent(21.0);
        salleB = new FauxAgent(21.0);
        cuisine = new FauxAgent(21.0);
        registre.put("salleA", salleA);
        registre.put("salleB", salleB);
        registre.put("cuisine", cuisine);
        AnnuaireAgents annuaire = piece -> {
            IAgentPiece agent = registre.get(piece);
            if (agent == null) {
                throw new NotBoundException("agent-" + piece);
            }
            return agent;
        };
        coordinateur = new CoordinateurBatiment(annuaire, notifications::add);
    }

    @Test
    void ecartSuperieurA4DegresEnvoieLaMoyenneALaPieceLaPlusChaude() {
        salleA.temperature = 27.0;
        salleB.temperature = 21.0;

        List<Decision> decisions = coordinateur.equilibrerTemperatures();

        assertEquals(1, decisions.size());
        assertEquals(List.of(24.0), salleA.consignesRecues);
        assertTrue(salleB.consignesRecues.isEmpty(), "la pièce froide ne reçoit rien");
        assertEquals(1, notifications.size());
    }

    @Test
    void uneConsigneDejaEnPlaceNestPasRenvoyeeAuCycleSuivant() {
        salleA.temperature = 27.0;
        salleB.temperature = 21.0;

        coordinateur.equilibrerTemperatures();
        coordinateur.equilibrerTemperatures();   // même situation, consigne 24,0 déjà appliquée

        assertEquals(List.of(24.0), salleA.consignesRecues);
    }

    @Test
    void aucunEnvoiSiLesEcartsRestentSousLeSeuil() {
        salleA.temperature = 25.0; // écart de 4,0 exactement avec salleB : pas d'action
        salleB.temperature = 21.0;
        cuisine.temperature = 18.5;

        assertTrue(coordinateur.equilibrerTemperatures().isEmpty());
        assertTrue(salleA.consignesRecues.isEmpty());
        assertTrue(salleB.consignesRecues.isEmpty());
    }

    @Test
    void salleBPlusChaudeQueSesDeuxVoisinesRecoitDeuxConsignes() {
        salleA.temperature = 20.0;
        salleB.temperature = 26.0;
        cuisine.temperature = 21.0;

        coordinateur.equilibrerTemperatures();

        assertEquals(List.of(23.0, 23.5), salleB.consignesRecues);
    }

    @Test
    void agentEnPanneLesAutresPairesContinuent() {
        salleA.enPanne = true;     // la JVM de salleA est arrêtée
        salleB.temperature = 27.0;
        cuisine.temperature = 20.0;

        List<Decision> decisions = assertDoesNotThrow(() -> coordinateur.equilibrerTemperatures());

        assertEquals(1, decisions.size(), "la paire salleB–cuisine est quand même traitée");
        assertEquals(List.of(23.5), salleB.consignesRecues);
    }

    @Test
    void agentAbsentPuisRedemarreEstReconnecte() {
        registre.remove("cuisine");          // pas encore enregistré
        salleA.temperature = 26.0;           // écart A–B de 2 °C : pas d'action sur cette paire
        salleB.temperature = 28.0;
        coordinateur.equilibrerTemperatures();
        assertTrue(salleB.consignesRecues.isEmpty(), "salleB–cuisine ignorée, salleA–salleB sous le seuil");

        FauxAgent cuisineRedemarree = new FauxAgent(21.0);
        registre.put("cuisine", cuisineRedemarree);  // l'agent redémarre et s'enregistre
        coordinateur.equilibrerTemperatures();

        assertEquals(List.of(24.5), salleB.consignesRecues);
    }

    @Test
    void agentQuiTombeEnPanneEntreDeuxCyclesEstRetrouveApresRedemarrage() {
        coordinateur.equilibrerTemperatures();       // les trois agents sont connectés
        salleA.enPanne = true;
        coordinateur.equilibrerTemperatures();       // panne détectée, salleA retirée

        FauxAgent nouvelleSalleA = new FauxAgent(30.0);
        registre.put("salleA", nouvelleSalleA);      // nouvelle souche après redémarrage
        coordinateur.equilibrerTemperatures();

        assertEquals(List.of(25.5), nouvelleSalleA.consignesRecues);
    }

    @Test
    void capteurEnTimeoutLaDerniereValeurConnueEstUtilisee() {
        salleA.temperature = 28.0;
        salleA.timeout = true;   // l'agent répond, mais son capteur est muet depuis 30 s

        List<Decision> decisions = coordinateur.equilibrerTemperatures();

        assertEquals(1, decisions.size());
        assertEquals(List.of(24.5), salleA.consignesRecues);
    }

    @Test
    void temperatureInconnueLaPaireEstIgnoree() {
        salleA.temperature = Double.NaN; // aucun message reçu par l'agent
        salleB.temperature = 30.0;
        cuisine.temperature = 21.0;

        coordinateur.equilibrerTemperatures();

        assertTrue(salleA.consignesRecues.isEmpty());
        assertEquals(List.of(25.5), salleB.consignesRecues, "seule salleB–cuisine est évaluée");
    }

    @Test
    void lectureDuChampTimeout() {
        assertTrue(CoordinateurBatiment.lireTimeout("{\"timeout\":true}"));
        assertFalse(CoordinateurBatiment.lireTimeout("{\"timeout\":false}"));
        assertFalse(CoordinateurBatiment.lireTimeout("pas du json"));
        assertEquals(21.0, CoordinateurBatiment.lireConsigne("{\"consigne\":21.0}"), 1e-9);
        assertTrue(Double.isNaN(CoordinateurBatiment.lireConsigne("{\"consigne\":null}")));
    }
}
