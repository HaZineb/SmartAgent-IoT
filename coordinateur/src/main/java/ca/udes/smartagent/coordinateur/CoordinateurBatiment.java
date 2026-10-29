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
 * <p>À chaque cycle (toutes les 10 s par défaut), il :</p>
 * <ol>
 *   <li>se (re)connecte par RMI aux agents qui ne sont pas encore joignables ;</li>
 *   <li>lit une fois la température et l'état de chaque pièce ;</li>
 *   <li>évalue les paires adjacentes salleA–salleB et salleB–cuisine avec
 *       {@link PolitiqueEquilibrage} ;</li>
 *   <li>envoie la consigne par RMI ({@code setConsigneTemperature}) ; l'agent la publie sur MQTT ;</li>
 *   <li>journalise la paire, les deux températures, la décision et la consigne.</li>
 * </ol>
 *
 * <p><b>Tolérance aux pannes.</b></p>
 * <ul>
 *   <li>Agent injoignable ({@code RemoteException}, {@code NotBoundException}) : il est retiré de
 *       la table, les paires qui l'impliquent sont ignorées pour ce cycle, les autres paires sont
 *       traitées normalement, et la reconnexion est tentée au cycle suivant.</li>
 *   <li>Capteur en timeout (champ {@code timeout} du JSON de l'agent) : l'agent répond encore,
 *       on utilise donc sa dernière valeur connue et on la signale « périmée » dans le journal.</li>
 *   <li>Aucune exception ne sort d'un cycle : une exception non attrapée arrêterait
 *       définitivement la tâche périodique.</li>
 * </ul>
 */
public class CoordinateurBatiment {

    private static final Logger LOG = Logger.getLogger("Coordinateur");

    /** Les trois pièces de l'étage. */
    public static final List<String> PIECES = List.of("salleA", "salleB", "cuisine");

    /** Paires de pièces adjacentes évaluées à chaque cycle (énoncé : au minimum ces deux-là). */
    public static final List<List<String>> PAIRES = List.of(
            List.of("salleA", "salleB"),
            List.of("salleB", "cuisine"));

    /**
     * Mesure lue chez un agent pendant un cycle.
     *
     * @param temperature dernière température connue (°C), {@code NaN} si jamais reçue
     * @param perimee     vrai si l'agent signale un capteur en timeout
     * @param consigne    consigne actuelle de la pièce (°C), {@code NaN} si inconnue
     */
    record Lecture(double temperature, boolean perimee, double consigne) {
    }

    private final AnnuaireAgents annuaire;
    private final Consumer<String> notification;

    /**
     * Agents actuellement joignables. Seul le thread du cycle y accède : une HashMap suffit,
     * pas besoin de structure concurrente.
     */
    private final Map<String, IAgentPiece> agents = new HashMap<>();

    /** Agents déjà signalés indisponibles : évite de répéter le même message à chaque cycle. */
    private final Set<String> signalesIndisponibles = new HashSet<>();

    /** Agents qui ont effectivement répondu depuis leur dernière (re)connexion. */
    private final Set<String> joignables = new HashSet<>();

    private ScheduledExecutorService planificateur;

    /**
     * @param annuaire     moyen de trouver les agents (registre RMI en production)
     * @param notification reçoit le texte de chaque décision (openHAB), ou ne fait rien
     */
    public CoordinateurBatiment(AnnuaireAgents annuaire, Consumer<String> notification) {
        this.annuaire = annuaire;
        this.notification = notification;
    }

    // =====================================================================================
    //  Un cycle d'équilibrage
    // =====================================================================================

    /**
     * Exécute un cycle complet d'équilibrage. Ne lance jamais d'exception liée à RMI :
     * les pannes sont journalisées et le cycle continue avec les agents disponibles.
     *
     * @return les décisions effectivement envoyées aux agents pendant ce cycle
     */
    public List<Decision> equilibrerTemperatures() {
        connecterAgentsManquants();

        // Lecture unique de chaque pièce : salleB, présente dans deux paires, n'est lue qu'une fois
        // et les deux paires utilisent la même valeur.
        Map<String, Lecture> lectures = new HashMap<>();
        for (String piece : PIECES) {
            lire(piece).ifPresent(lecture -> lectures.put(piece, lecture));
        }

        List<Decision> envoyees = new ArrayList<>();
        for (List<String> paire : PAIRES) {
            evaluerPaire(paire.get(0), paire.get(1), lectures).ifPresent(envoyees::add);
        }
        return envoyees;
    }

    /** Tente un lookup RMI pour chaque pièce absente de la table des agents. */
    private void connecterAgentsManquants() {
        for (String piece : PIECES) {
            if (agents.containsKey(piece)) {
                continue;
            }
            try {
                // Le lookup peut réussir même si l'agent est mort : après un arrêt brutal (kill -9),
                // son ancien nom reste dans le registre. On n'annonce donc la connexion que lorsque
                // l'agent a vraiment répondu, dans lire().
                agents.put(piece, annuaire.trouver(piece));
            } catch (RemoteException | NotBoundException e) {
                if (signalesIndisponibles.add(piece)) { // vrai seulement la première fois
                    LOG.warning("agent-" + piece + " indisponible (" + e.getClass().getSimpleName()
                            + ") : nouvel essai à chaque cycle");
                }
            }
        }
    }

    /**
     * Lit la température et l'indicateur de timeout d'une pièce.
     *
     * @return vide si l'agent n'est pas connecté ou vient de tomber en panne
     */
    private Optional<Lecture> lire(String piece) {
        IAgentPiece agent = agents.get(piece);
        if (agent == null) {
            return Optional.empty();
        }
        try {
            double temperature = agent.getTemperature();
            String etat = agent.getEtatJson();
            if (joignables.add(piece)) { // vrai seulement au premier succès après une absence
                signalesIndisponibles.remove(piece);
                LOG.info("Connecté à agent-" + piece + " (répond par RMI)");
            }
            return Optional.of(new Lecture(temperature, lireTimeout(etat), lireConsigne(etat)));
        } catch (RemoteException e) {
            signalerPanne(piece, e);
            return Optional.empty();
        }
    }

    /** Extrait le champ {@code timeout} du JSON de l'agent ; un JSON illisible compte comme « pas de timeout ». */
    static boolean lireTimeout(String json) {
        try {
            JsonObject objet = JsonParser.parseString(json).getAsJsonObject();
            return objet.has("timeout") && objet.get("timeout").getAsBoolean();
        } catch (RuntimeException e) {
            LOG.fine("JSON d'état illisible : " + json);
            return false;
        }
    }

    /** Extrait le champ {@code consigne} du JSON de l'agent ; {@code NaN} s'il est absent, nul ou illisible. */
    static double lireConsigne(String json) {
        try {
            JsonObject objet = JsonParser.parseString(json).getAsJsonObject();
            return objet.has("consigne") && !objet.get("consigne").isJsonNull()
                    ? objet.get("consigne").getAsDouble() : Double.NaN;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    /** Évalue une paire, envoie la consigne si nécessaire et journalise la décision. */
    private Optional<Decision> evaluerPaire(String a, String b, Map<String, Lecture> lectures) {
        String nomPaire = a + "–" + b;
        Lecture la = lectures.get(a);
        Lecture lb = lectures.get(b);
        if (la == null || lb == null) {
            LOG.info("Paire " + nomPaire + " ignorée : agent-" + (la == null ? a : b) + " indisponible");
            return Optional.empty();
        }
        if (Double.isNaN(la.temperature()) || Double.isNaN(lb.temperature())) {
            LOG.info("Paire " + nomPaire + " ignorée : aucune mesure encore reçue pour "
                    + (Double.isNaN(la.temperature()) ? a : b));
            return Optional.empty();
        }

        String temperatures = String.format(Locale.ROOT, "%s=%.1f °C%s, %s=%.1f °C%s",
                a, la.temperature(), la.perimee() ? " (dernière valeur connue, capteur en timeout)" : "",
                b, lb.temperature(), lb.perimee() ? " (dernière valeur connue, capteur en timeout)" : "");
        double ecart = Math.abs(la.temperature() - lb.temperature());

        Optional<Decision> decision = PolitiqueEquilibrage.evaluer(a, la.temperature(), b, lb.temperature());
        if (decision.isEmpty()) {
            LOG.info(String.format(Locale.ROOT, "Paire %s : %s | écart %.1f °C ≤ %.1f → aucune action",
                    nomPaire, temperatures, ecart, PolitiqueEquilibrage.SEUIL_ECART));
            return Optional.empty();
        }

        Decision d = decision.get();
        // Si la pièce applique déjà cette consigne, on ne la renvoie pas à chaque cycle :
        // on évite d'inonder MQTT et openHAB de commandes identiques.
        double consigneActuelle = (d.pieceCible().equals(a) ? la : lb).consigne();
        if (Math.abs(consigneActuelle - d.consigne()) < 0.05) {
            LOG.info(String.format(Locale.ROOT, "Paire %s : %s | écart %.1f °C > %.1f → consigne %.1f °C déjà en place "
                    + "pour %s, pas de renvoi", nomPaire, temperatures, ecart, PolitiqueEquilibrage.SEUIL_ECART,
                    d.consigne(), d.pieceCible()));
            return Optional.empty();
        }
        IAgentPiece cible = agents.get(d.pieceCible());
        if (cible == null) { // l'agent a pu tomber en panne pendant la paire précédente du même cycle
            LOG.info("Paire " + nomPaire + " : consigne non envoyée, agent-" + d.pieceCible() + " vient de tomber en panne");
            return Optional.empty();
        }
        try {
            cible.setConsigneTemperature(d.consigne()); // appel RMI → l'agent publie sur MQTT
        } catch (RemoteException e) {
            LOG.warning(String.format(Locale.ROOT, "Paire %s : %s | écart %.1f °C > %.1f → consigne %.1f °C "
                    + "NON envoyée à %s (%s)", nomPaire, temperatures, ecart, PolitiqueEquilibrage.SEUIL_ECART,
                    d.consigne(), d.pieceCible(), e.getMessage()));
            signalerPanne(d.pieceCible(), e);
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            LOG.warning("Consigne refusée par agent-" + d.pieceCible() + " : " + e.getMessage());
            return Optional.empty();
        }

        String texte = String.format(Locale.ROOT, "Paire %s : %s | écart %.1f °C > %.1f → consigne %.1f °C envoyée à %s",
                nomPaire, temperatures, ecart, PolitiqueEquilibrage.SEUIL_ECART, d.consigne(), d.pieceCible());
        LOG.info(texte);
        notifier(String.format(Locale.ROOT, "%s : consigne %.1f °C (écart %.1f °C avec %s)",
                d.pieceCible(), d.consigne(), ecart, d.pieceCible().equals(a) ? b : a));
        return decision;
    }

    /**
     * Retire un agent en panne de la table : sa souche ne sert plus à rien, et au prochain cycle
     * {@link #connecterAgentsManquants()} refera un lookup (l'agent a peut-être redémarré avec
     * une nouvelle souche).
     */
    private void signalerPanne(String piece, RemoteException e) {
        agents.remove(piece);
        joignables.remove(piece);
        if (signalesIndisponibles.add(piece)) { // on ne répète pas l'avertissement à chaque cycle
            LOG.warning("agent-" + piece + " ne répond plus (" + causeRacine(e)
                    + ") : retiré, nouvel essai à chaque cycle");
        }
    }

    private static String causeRacine(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName();
    }

    /** La notification (openHAB) ne doit jamais interrompre le cycle. */
    private void notifier(String texte) {
        try {
            notification.accept(texte);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Notification openHAB impossible", e);
        }
    }

    // =====================================================================================
    //  Exécution périodique
    // =====================================================================================

    /**
     * Lance les cycles d'équilibrage en arrière-plan.
     *
     * @param periodeSecondes délai entre la fin d'un cycle et le début du suivant
     */
    public void demarrer(long periodeSecondes) {
        planificateur = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "coordinateur-cycle"));
        planificateur.scheduleWithFixedDelay(() -> {
            try {
                equilibrerTemperatures();
            } catch (RuntimeException e) {
                // Filet de sécurité : sans ce catch, une erreur inattendue arrêterait tous les cycles suivants.
                LOG.log(Level.SEVERE, "Erreur inattendue pendant le cycle (le coordinateur continue)", e);
            }
        }, 0, periodeSecondes, TimeUnit.SECONDS);
        LOG.info("Coordinateur démarré : un cycle toutes les " + periodeSecondes + " s, seuil "
                + PolitiqueEquilibrage.SEUIL_ECART + " °C");
    }

    /** Arrête les cycles. */
    public void arreter() {
        if (planificateur != null) {
            planificateur.shutdownNow();
        }
    }

    /**
     * Programme principal.
     *
     * <pre>java -cp coordinateur-1.0-SNAPSHOT-all.jar ca.udes.smartagent.coordinateur.CoordinateurBatiment [hote] [periode]</pre>
     *
     * <ul>
     *   <li>{@code hote} : machine du registre RMI (défaut {@code localhost}) ;</li>
     *   <li>{@code periode} : secondes entre deux cycles (défaut 10) ;</li>
     *   <li>variable {@code OPENHAB_URL} (ex. {@code http://localhost:8080}) : active l'envoi des
     *       décisions à openHAB ; {@code OPENHAB_TOKEN} : jeton d'API facultatif.</li>
     * </ul>
     *
     * @param args voir ci-dessus
     * @throws Exception si le registre RMI ne peut pas être localisé
     */
    public static void main(String[] args) throws Exception {
        Journalisation.configurer();
        // Délais RMI : sans eux, un appel vers un agent figé pourrait bloquer le cycle indéfiniment.
        // Ces propriétés doivent être fixées avant le tout premier appel RMI.
        System.setProperty("sun.rmi.transport.tcp.responseTimeout", "5000");
        System.setProperty("sun.rmi.transport.connectionTimeout", "3000");

        String hote = args.length > 0 ? args[0] : "localhost";
        long periode = args.length > 1 ? Long.parseLong(args[1]) : 10;

        Registry registre = LocateRegistry.getRegistry(hote, LanceurRegistre.PORT_REGISTRE);
        AnnuaireAgents annuaireRmi = piece -> (IAgentPiece) registre.lookup("agent-" + piece);

        String urlOpenHab = System.getenv("OPENHAB_URL");
        Consumer<String> notification = texte -> { };
        if (urlOpenHab != null && !urlOpenHab.isBlank()) {
            notification = new ClientOpenHab(urlOpenHab, System.getenv("OPENHAB_TOKEN"));
            LOG.info("Décisions envoyées à openHAB : " + urlOpenHab + " (Item " + ClientOpenHab.ITEM_DECISION + ")");
        }

        CoordinateurBatiment coordinateur = new CoordinateurBatiment(annuaireRmi, notification);
        coordinateur.demarrer(periode);
        Runtime.getRuntime().addShutdownHook(new Thread(coordinateur::arreter));
    }
}
