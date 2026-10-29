package ca.udes.smartagent.agent;

import java.io.UnsupportedEncodingException;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Configuration commune des journaux (logs) pour les agents et le coordinateur.
 *
 * <p>On utilise {@code java.util.logging}, inclus dans le JDK, pour éviter une dépendance de plus.
 * Chaque ligne a la forme : {@code 16:05:12 INFO    [Coordinateur] message}.</p>
 */
public final class Journalisation {

    private Journalisation() {
        // Classe utilitaire : pas d'instance.
    }

    /**
     * Remplace la sortie par défaut (deux lignes par message) par un format compact d'une ligne.
     * À appeler au tout début de chaque méthode {@code main}.
     */
    public static void configurer() {
        // %1$tT = heure, %4$s = niveau, %3$s = nom du logger, %5$s = message, %6$s = exception éventuelle
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tT %4$-7s [%3$s] %5$s%6$s%n");

        Logger racine = Logger.getLogger("");
        for (Handler h : racine.getHandlers()) {
            racine.removeHandler(h);
        }
        ConsoleHandler console = new ConsoleHandler();
        console.setFormatter(new SimpleFormatter());

        // Sous Windows, la console n'est pas toujours en UTF-8 : on écrit dans l'encodage
        // de la console pour que les accents s'affichent correctement.
        String encodageConsole = System.getProperty("stderr.encoding");
        if (encodageConsole != null) {
            try {
                console.setEncoding(encodageConsole);
            } catch (UnsupportedEncodingException e) {
                // On garde l'encodage par défaut : seul l'affichage des accents est en jeu.
            }
        }
        racine.addHandler(console);
    }
}
