
package ca.udes.smartagent.coordinateur;

import ca.udes.smartagent.agent.Journalisation;
import ca.udes.smartagent.agent.LanceurRegistre;
import ca.udes.smartagent.coordinateur.PolitiqueEquilibrage.Decision;
import ca.udes.smartagent.rmi.IAgentPiece;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coordinateur central du bâtiment (tâches 2.3 et 3.1).
 *
 * À chaque cycle, il :
 * 1. Recherche les agents RMI manquants.
 * 2. Lit les températures et états des agents.
 * 3. Met à jour leurs statuts dans openHAB.
 * 4. Évalue les paires de pièces adjacentes.
 * 5. Envoie les consignes par RMI si nécessaire.
 * 6. Transmet les décisions à openHAB.
 *
 * Les agents indisponibles sont ignorés pendant
 * l'équilibrage et recherchés au cycle suivant.
 */
public class CoordinateurBatiment {

    private static final Logger LOG =
            Logger.getLogger("Coordinateur");

    /** Les trois pièces du bâtiment. */
    public static final List<String> PIECES =
            List.of("salleA", "salleB", "cuisine");

    /** Paires adjacentes évaluées à chaque cycle. */
    public static final List<List<String>> PAIRES = List.of(
            List.of("salleA", "salleB"),
            List.of("salleB", "cuisine")
    );

    /**
     * Mesure lue chez un agent pendant un cycle.
     *
     * @param temperature dernière température connue
     * @param perimee vrai si le capteur est en timeout
     * @param consigne consigne actuelle de la pièce
     */
    record Lecture(
            double temperature,
            boolean perimee,
            double consigne
    ) {
    }

    private final AnnuaireAgents annuaire;
    private final Consumer<String> notification;

    /**
     * Client REST permettant d'envoyer les statuts
     * des agents vers openHAB.
     */
    private ClientOpenHab clientOpenHab;

    /** Agents actuellement connus par le coordinateur. */
    private final Map<String, IAgentPiece> agents =
            new HashMap<>();

    /** Agents déjà signalés comme indisponibles. */
    private final Set<String> signalesIndisponibles =
            new HashSet<>();

    /** Agents ayant répondu depuis leur dernière connexion. */
    private final Set<String> joignables =
            new HashSet<>();

    private ScheduledExecutorService planificateur;

    /**
     * Constructeur conservant l'interface existante.
     *
     * @param annuaire recherche des agents RMI
     * @param notification réception des décisions
     */
    public CoordinateurBatiment(
            AnnuaireAgents annuaire,
            Consumer<String> notification
    ) {
        this.annuaire = annuaire;
        this.notification = notification;
    }

    // =====================================================
    // CYCLE D'ÉQUILIBRAGE
    // =====================================================

    /**
     * Exécute un cycle complet d'équilibrage.
     *
     * @return décisions effectivement envoyées
     */
    public List<Decision> equilibrerTemperatures() {

        connecterAgentsManquants();

        // Lecture unique de chaque pièce.
        Map<String, Lecture> lectures = new HashMap<>();

        for (String piece : PIECES) {
            lire(piece).ifPresent(
                    lecture -> lectures.put(piece, lecture)
            );
        }

        // NOUVEAU : mise à jour des statuts openHAB.
        actualiserStatutsAgents(lectures);

        // Équilibrage des températures.
        List<Decision> envoyees = new ArrayList<>();

        for (List<String> paire : PAIRES) {
            evaluerPaire(
                    paire.get(0),
                    paire.get(1),
                    lectures
            ).ifPresent(envoyees::add);
        }

        return envoyees;
    }

    // =====================================================
    // STATUTS DES AGENTS DANS OPENHAB
    // =====================================================

    /**
     * Met à jour les trois Items de statut openHAB.
     *
     * Un agent est ONLINE lorsqu'il a répondu aux
     * appels RMI de lecture pendant ce cycle.
     *
     * Sinon, il est OFFLINE.
     */
    private void actualiserStatutsAgents(
            Map<String, Lecture> lectures
    ) {

        if (clientOpenHab == null) {
            return;
        }

        for (String piece : PIECES) {

            boolean disponible =
                    lectures.containsKey(piece);

            clientOpenHab.envoyerStatutAgent(
                    piece,
                    disponible
            );
        }
    }

    // =====================================================
    // CONNEXION AUX AGENTS RMI
    // =====================================================

    /**
     * Recherche les agents absents de la table.
     */
    private void connecterAgentsManquants() {

        for (String piece : PIECES) {

            if (agents.containsKey(piece)) {
                continue;
            }

            try {

                // Un lookup réussi ne garantit pas
                // que l'agent répond encore.
                agents.put(
                        piece,
                        annuaire.trouver(piece)
                );

            } catch (RemoteException | NotBoundException e) {

                if (signalesIndisponibles.add(piece)) {

                    LOG.warning(
                            "agent-" + piece
                            + " indisponible ("
                            + e.getClass().getSimpleName()
                            + ") : nouvel essai à chaque cycle"
                    );
                }
            }
        }
    }

    // =====================================================
    // LECTURE DES AGENTS
    // =====================================================

    /**
     * Lit la température et l'état d'une pièce.
     *
     * @return vide si l'agent est indisponible
     */
    private Optional<Lecture> lire(String piece) {

        IAgentPiece agent = agents.get(piece);

        if (agent == null) {
            return Optional.empty();
        }

        try {

            double temperature =
                    agent.getTemperature();

            String etat =
                    agent.getEtatJson();

            if (joignables.add(piece)) {

                signalesIndisponibles.remove(piece);

                LOG.info(
                        "Connecté à agent-" + piece
                        + " (répond par RMI)"
                );
            }

            return Optional.of(
                    new Lecture(
                            temperature,
                            lireTimeout(etat),
                            lireConsigne(etat)
                    )
            );

        } catch (RemoteException e) {

            signalerPanne(piece, e);
            return Optional.empty();
        }
    }

    /**
     * Extrait le champ timeout du JSON de l'agent.
     */
    static boolean lireTimeout(String json) {

        try {

            JsonObject objet =
                    JsonParser.parseString(json)
                            .getAsJsonObject();

            return objet.has("timeout")
                    && objet.get("timeout").getAsBoolean();

        } catch (RuntimeException e) {

            LOG.fine(
                    "JSON d'état illisible : " + json
            );

            return false;
        }
    }

    /**
     * Extrait la consigne du JSON de l'agent.
     */
    static double lireConsigne(String json) {

        try {

            JsonObject objet =
                    JsonParser.parseString(json)
                            .getAsJsonObject();

            return objet.has("consigne")
                    && !objet.get("consigne").isJsonNull()
                    ? objet.get("consigne").getAsDouble()
                    : Double.NaN;

        } catch (RuntimeException e) {

            return Double.NaN;
        }
    }

    // =====================================================
    // ÉQUILIBRAGE DES TEMPÉRATURES
    // =====================================================

    /**
     * Évalue une paire de pièces et envoie
     * une consigne si nécessaire.
     */
    private Optional<Decision> evaluerPaire(
            String a,
            String b,
            Map<String, Lecture> lectures
    ) {

        String nomPaire = a + "–" + b;

        Lecture la = lectures.get(a);
        Lecture lb = lectures.get(b);

        if (la == null || lb == null) {

            LOG.info(
                    "Paire " + nomPaire
                    + " ignorée : agent-"
                    + (la == null ? a : b)
                    + " indisponible"
            );

            return Optional.empty();
        }

        if (Double.isNaN(la.temperature())
                || Double.isNaN(lb.temperature())) {

            LOG.info(
                    "Paire " + nomPaire
                    + " ignorée : aucune mesure encore reçue pour "
                    + (Double.isNaN(la.temperature()) ? a : b)
            );

            return Optional.empty();
        }

        String temperatures = String.format(
                Locale.ROOT,
                "%s=%.1f °C%s, %s=%.1f °C%s",
                a,
                la.temperature(),
                la.perimee()
                        ? " (dernière valeur connue, capteur en timeout)"
                        : "",
                b,
                lb.temperature(),
                lb.perimee()
                        ? " (dernière valeur connue, capteur en timeout)"
                        : ""
        );

        double ecart =
                Math.abs(la.temperature() - lb.temperature());

        Optional<Decision> decision =
                PolitiqueEquilibrage.evaluer(
                        a,
                        la.temperature(),
                        b,
                        lb.temperature()
                );

        if (decision.isEmpty()) {

            LOG.info(String.format(
                    Locale.ROOT,
                    "Paire %s : %s | écart %.1f °C ≤ %.1f → aucune action",
                    nomPaire,
                    temperatures,
                    ecart,
                    PolitiqueEquilibrage.SEUIL_ECART
            ));

            return Optional.empty();
        }

        Decision d = decision.get();

        // Évite de renvoyer une consigne déjà appliquée.
        double consigneActuelle =
                (d.pieceCible().equals(a) ? la : lb)
                        .consigne();

        if (Math.abs(consigneActuelle - d.consigne()) < 0.05) {

            LOG.info(String.format(
                    Locale.ROOT,
                    "Paire %s : %s | écart %.1f °C > %.1f "
                    + "→ consigne %.1f °C déjà en place pour %s, pas de renvoi",
                    nomPaire,
                    temperatures,
                    ecart,
                    PolitiqueEquilibrage.SEUIL_ECART,
                    d.consigne(),
                    d.pieceCible()
            ));

            return Optional.empty();
        }

        IAgentPiece cible =
                agents.get(d.pieceCible());

        if (cible == null) {

            LOG.info(
                    "Paire " + nomPaire
                    + " : consigne non envoyée, agent-"
                    + d.pieceCible()
                    + " vient de tomber en panne"
            );

            return Optional.empty();
        }

        try {

            // Appel RMI : l'agent publie ensuite sur MQTT.
            cible.setConsigneTemperature(
                    d.consigne()
            );

        } catch (RemoteException e) {

            LOG.warning(String.format(
                    Locale.ROOT,
                    "Paire %s : %s | écart %.1f °C > %.1f "
                    + "→ consigne %.1f °C NON envoyée à %s (%s)",
                    nomPaire,
                    temperatures,
                    ecart,
                    PolitiqueEquilibrage.SEUIL_ECART,
                    d.consigne(),
                    d.pieceCible(),
                    e.getMessage()
            ));

            signalerPanne(
                    d.pieceCible(),
                    e
            );

            return Optional.empty();

        } catch (IllegalArgumentException e) {

            LOG.warning(
                    "Consigne refusée par agent-"
                    + d.pieceCible()
                    + " : " + e.getMessage()
            );

            return Optional.empty();
        }

        String texte = String.format(
                Locale.ROOT,
                "Paire %s : %s | écart %.1f °C > %.1f "
                + "→ consigne %.1f °C envoyée à %s",
                nomPaire,
                temperatures,
                ecart,
                PolitiqueEquilibrage.SEUIL_ECART,
                d.consigne(),
                d.pieceCible()
        );

        LOG.info(texte);

        notifier(String.format(
                Locale.ROOT,
                "%s : consigne %.1f °C (écart %.1f °C avec %s)",
                d.pieceCible(),
                d.consigne(),
                ecart,
                d.pieceCible().equals(a) ? b : a
        ));

        return decision;
    }

    // =====================================================
    // GESTION DES PANNES
    // =====================================================

    /**
     * Retire un agent qui ne répond plus.
     * Une reconnexion sera tentée au cycle suivant.
     */
    private void signalerPanne(
            String piece,
            RemoteException e
    ) {

        agents.remove(piece);
        joignables.remove(piece);

        if (signalesIndisponibles.add(piece)) {

            LOG.warning(
                    "agent-" + piece
                    + " ne répond plus ("
                    + causeRacine(e)
                    + ") : retiré, nouvel essai à chaque cycle"
            );
        }
    }

    private static String causeRacine(Throwable e) {

        Throwable c = e;

        while (c.getCause() != null) {
            c = c.getCause();
        }

        return c.getClass().getSimpleName();
    }

    // =====================================================
    // NOTIFICATIONS OPENHAB
    // =====================================================

    /**
     * Envoie une décision à openHAB sans interrompre
     * le cycle en cas d'erreur.
     */
    private void notifier(String texte) {

        try {

            notification.accept(texte);

        } catch (RuntimeException e) {

            LOG.log(
                    Level.WARNING,
                    "Notification openHAB impossible",
                    e
            );
        }
    }

    // =====================================================
    // EXÉCUTION PÉRIODIQUE
    // =====================================================

    /**
     * Démarre les cycles d'équilibrage.
     */
    public void demarrer(long periodeSecondes) {

        planificateur =
                Executors.newSingleThreadScheduledExecutor(
                        r -> new Thread(
                                r,
                                "coordinateur-cycle"
                        )
                );

        planificateur.scheduleWithFixedDelay(() -> {

            try {

                equilibrerTemperatures();

            } catch (RuntimeException e) {

                LOG.log(
                        Level.SEVERE,
                        "Erreur inattendue pendant le cycle "
                        + "(le coordinateur continue)",
                        e
                );
            }

        }, 0, periodeSecondes, TimeUnit.SECONDS);

        LOG.info(
                "Coordinateur démarré : un cycle toutes les "
                + periodeSecondes
                + " s, seuil "
                + PolitiqueEquilibrage.SEUIL_ECART
                + " °C"
        );
    }

    /**
     * Arrête les cycles.
     */
    public void arreter() {

        if (planificateur != null) {
            planificateur.shutdownNow();
        }
    }

    // =====================================================
    // PROGRAMME PRINCIPAL
    // =====================================================

    /**
     * Lance le coordinateur.
     *
     * Arguments :
     * - hôte du registre RMI
     * - période entre les cycles
     *
     * Variables d'environnement :
     * - OPENHAB_URL
     * - OPENHAB_TOKEN (facultatif)
     */
    public static void main(String[] args)
            throws Exception {

        Journalisation.configurer();

        // Délais RMI.
        System.setProperty(
                "sun.rmi.transport.tcp.responseTimeout",
                "5000"
        );

        System.setProperty(
                "sun.rmi.transport.connectionTimeout",
                "3000"
        );

        String hote =
                args.length > 0
                        ? args[0]
                        : "localhost";

        long periode =
                args.length > 1
                        ? Long.parseLong(args[1])
                        : 10;

        Registry registre =
                LocateRegistry.getRegistry(
                        hote,
                        LanceurRegistre.PORT_REGISTRE
                );

        AnnuaireAgents annuaireRmi =
                piece -> (IAgentPiece) registre.lookup(
                        "agent-" + piece
                );

        String urlOpenHab =
                System.getenv("OPENHAB_URL");

        Consumer<String> notification =
                texte -> { };

        if (urlOpenHab != null
                && !urlOpenHab.isBlank()) {

            notification = new ClientOpenHab(
                    urlOpenHab,
                    System.getenv("OPENHAB_TOKEN")
            );

            LOG.info(
                    "Décisions et statuts envoyés à openHAB : "
                    + urlOpenHab
            );
        }

        CoordinateurBatiment coordinateur =
                new CoordinateurBatiment(
                        annuaireRmi,
                        notification
                );

        // NOUVEAU : réutilisation du client REST
        // pour les statuts des trois agents.
        if (notification instanceof ClientOpenHab) {

            coordinateur.clientOpenHab =
                    (ClientOpenHab) notification;
        }

        coordinateur.demarrer(periode);

        Runtime.getRuntime().addShutdownHook(
                new Thread(coordinateur::arreter)
        );
    }
}
