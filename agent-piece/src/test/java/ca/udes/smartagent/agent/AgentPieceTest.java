package ca.udes.smartagent.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.rmi.NoSuchObjectException;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests unitaires de {@link AgentPiece}, sans broker MQTT ni registre RMI.
 * Les messages MQTT sont injectés directement avec {@code traiterMessage}, et une horloge
 * contrôlée permet de simuler 30 secondes de silence sans attendre.
 */
class AgentPieceTest {

    private final AtomicLong horloge = new AtomicLong(1_000_000);
    private AgentPiece agent;

    @BeforeEach
    void creerAgent() throws RemoteException {
        agent = new AgentPiece("salleA", "tcp://localhost:1883", horloge::get);
    }

    @AfterEach
    void desexporter() throws NoSuchObjectException {
        UnicastRemoteObject.unexportObject(agent, true);
    }

    private void publierLesTrois(String temp, String lum, String occ) {
        agent.traiterMessage("batiment/salleA/capteur/temperature", temp);
        agent.traiterMessage("batiment/salleA/capteur/luminosite", lum);
        agent.traiterMessage("batiment/salleA/capteur/occupation", occ);
    }

    @Test
    void lesMessagesMqttMettentAJourLesValeurs() throws RemoteException {
        publierLesTrois("22.4", "340", "true");

        assertEquals(22.4, agent.getTemperature(), 1e-9);
        assertEquals(340, agent.getLuminosite());
        assertTrue(agent.isOccupee());
        assertTrue(agent.capteursEnTimeout().isEmpty());
    }

    @Test
    void unMessageMalFormeEstIgnoreEtLaValeurPrecedenteConservee() throws RemoteException {
        agent.traiterMessage("batiment/salleA/capteur/temperature", "21.0");
        agent.traiterMessage("batiment/salleA/capteur/temperature", "abc");
        agent.traiterMessage("batiment/salleA/capteur/occupation", "peut-etre");

        assertEquals(21.0, agent.getTemperature(), 1e-9);
        assertFalse(agent.isOccupee());
    }

    @Test
    void avantToutMessageLesValeursSontInconnuesEtEnTimeout() throws RemoteException {
        assertTrue(Double.isNaN(agent.getTemperature()));
        assertEquals(-1, agent.getLuminosite());

        JsonObject json = JsonParser.parseString(agent.getEtatJson()).getAsJsonObject();
        assertTrue(json.get("temperature").isJsonNull());
        assertTrue(json.get("timeout").getAsBoolean());
        assertEquals(3, json.getAsJsonArray("capteursEnTimeout").size());
    }

    @Test
    void timeoutApres30SecondesSansMessage() throws RemoteException {
        publierLesTrois("22.0", "300", "false");

        horloge.addAndGet(29_999);
        assertTrue(agent.capteursEnTimeout().isEmpty(), "pas encore 30 s");

        // Seule la température continue de publier : luminosité et occupation deviennent muettes.
        agent.traiterMessage("batiment/salleA/capteur/temperature", "23.0");
        horloge.addAndGet(1);
        assertEquals(List.of("luminosite", "occupation"), agent.capteursEnTimeout());

        JsonObject json = JsonParser.parseString(agent.getEtatJson()).getAsJsonObject();
        assertTrue(json.get("timeout").getAsBoolean());
        // La dernière valeur connue reste disponible pendant la panne.
        assertEquals(300, json.get("luminosite").getAsInt());
    }

    @Test
    void retourALaNormaleQuandLesMessagesReviennent() {
        publierLesTrois("22.0", "300", "false");
        horloge.addAndGet(40_000);
        assertFalse(agent.capteursEnTimeout().isEmpty());

        publierLesTrois("22.5", "310", "true");
        assertTrue(agent.capteursEnTimeout().isEmpty());
    }

    @Test
    void laConsigneRecueSurLeTopicThermostatApparaitDansLeJson() throws RemoteException {
        // Cas d'une règle openHAB (pièce vide → 18 °C) qui publie sur le topic du thermostat.
        agent.traiterMessage("batiment/salleA/actuateur/thermostat", "18");

        JsonObject json = JsonParser.parseString(agent.getEtatJson()).getAsJsonObject();
        assertEquals(18.0, json.get("consigne").getAsDouble(), 1e-9);
    }

    @Test
    void consigneHorsPlageRefusee() {
        assertThrows(IllegalArgumentException.class, () -> agent.setConsigneTemperature(55));
        assertThrows(IllegalArgumentException.class, () -> agent.setConsigneTemperature(Double.NaN));
    }

    @Test
    void consigneSansBrokerSignaleUneRemoteException() {
        // demarrer() n'a pas été appelé : aucun client MQTT connecté.
        assertThrows(RemoteException.class, () -> agent.setConsigneTemperature(21.0));
    }

    @Test
    void pieceInconnueRefusee() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentPiece("garage", "tcp://localhost:1883"));
    }
}
