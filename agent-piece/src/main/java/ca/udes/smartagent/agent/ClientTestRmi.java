package ca.udes.smartagent.agent;

import ca.udes.smartagent.rmi.IAgentPiece;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.Locale;

/**
 * Petit client RMI pour valider la tâche 2.2 : lire les trois états d'une pièce,
 * puis (facultatif) modifier sa consigne de thermostat.
 *
 * <pre>
 * java -cp agent-piece-1.0-SNAPSHOT-all.jar ca.udes.smartagent.agent.ClientTestRmi salleA
 * java -cp agent-piece-1.0-SNAPSHOT-all.jar ca.udes.smartagent.agent.ClientTestRmi salleA 20.5
 * </pre>
 */
public final class ClientTestRmi {

    private ClientTestRmi() {
    }

    /**
     * @param args nom de la pièce, consigne facultative, hôte du registre facultatif (localhost)
     * @throws Exception en cas d'échec RMI, affiché tel quel pour faciliter le diagnostic
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage : ClientTestRmi <salleA|salleB|cuisine> [consigne] [hote]");
            System.exit(1);
        }
        String piece = args[0];
        String hote = args.length > 2 ? args[2] : "localhost";

        Registry registre = LocateRegistry.getRegistry(hote, LanceurRegistre.PORT_REGISTRE);
        System.out.println("Agents enregistrés : " + String.join(", ", registre.list()));

        // Le lookup renvoie une souche : chaque appel ci-dessous traverse le réseau jusqu'à l'agent.
        IAgentPiece agent = (IAgentPiece) registre.lookup("agent-" + piece);
        System.out.printf(Locale.ROOT, "Température : %.1f °C%n", agent.getTemperature());
        System.out.println("Luminosité  : " + agent.getLuminosite());
        System.out.println("Occupée     : " + agent.isOccupee());
        System.out.println("État JSON   : " + agent.getEtatJson());

        if (args.length > 1) {
            double consigne = Double.parseDouble(args[1]);
            agent.setConsigneTemperature(consigne);
            System.out.println("Consigne envoyée : " + consigne + " °C");
            System.out.println("État JSON   : " + agent.getEtatJson());
        }
    }
}
