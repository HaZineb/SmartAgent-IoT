package ca.udes.smartagent.agent;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.logging.Logger;

/**
 * Accès au registre RMI (port 2199) et programme qui le lance seul.
 *
 * <p><b>Pourquoi un programme séparé ?</b> Un registre créé par {@code createRegistry} vit dans
 * la JVM qui l'a créé. Si l'agent salleA crée le registre puis tombe en panne, le registre
 * disparaît avec lui et les agents salleB et cuisine ne sont plus trouvables, alors qu'ils
 * fonctionnent encore. Pour la démonstration de panne, on lance donc d'abord ce registre
 * indépendant, puis les trois agents.</p>
 *
 * <p>Le registre doit connaître l'interface {@code IAgentPiece} pour recevoir les souches
 * (stubs) des agents : c'est pourquoi on le lance depuis notre JAR plutôt qu'avec la commande
 * {@code rmiregistry} du JDK.</p>
 */
public final class LanceurRegistre {

    /** Port du registre RMI fixé par l'énoncé. */
    public static final int PORT_REGISTRE = 2199;

    private static final Logger LOG = Logger.getLogger("Registre");

    private LanceurRegistre() {
    }

    /**
     * Renvoie le registre RMI déjà lancé sur le port 2199, ou le crée dans la JVM courante
     * s'il n'existe pas encore. L'ordre de lancement des programmes n'a donc pas d'importance.
     *
     * @return le registre utilisable pour {@code rebind}
     * @throws RemoteException si le registre ne peut être ni joint ni créé
     */
    public static Registry obtenirOuCreer() throws RemoteException {
        try {
            Registry existant = LocateRegistry.getRegistry(PORT_REGISTRE);
            // getRegistry ne contacte pas le réseau : list() vérifie que le registre répond vraiment.
            existant.list();
            return existant;
        } catch (RemoteException absent) {
            LOG.info("Aucun registre sur le port " + PORT_REGISTRE + " : création dans cette JVM");
            return LocateRegistry.createRegistry(PORT_REGISTRE);
        }
    }

    /**
     * Lance un registre RMI autonome et attend indéfiniment (Ctrl+C pour arrêter).
     *
     * @param args non utilisés
     * @throws Exception si le port 2199 est déjà occupé
     */
    public static void main(String[] args) throws Exception {
        Journalisation.configurer();
        // Champ statique : garde une référence forte au registre pendant toute la vie du programme.
        registreAutonome = LocateRegistry.createRegistry(PORT_REGISTRE);
        LOG.info("Registre RMI démarré sur le port " + PORT_REGISTRE + " (Ctrl+C pour arrêter)");
        // Le thread principal attend sans consommer de CPU ; le registre répond sur ses propres threads.
        synchronized (LanceurRegistre.class) {
            while (registreAutonome != null) {
                LanceurRegistre.class.wait();
            }
        }
    }

    /** Registre créé par {@link #main(String[])}. */
    private static Registry registreAutonome;
}
