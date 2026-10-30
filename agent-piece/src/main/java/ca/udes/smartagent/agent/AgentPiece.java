package ca.udes.smartagent.agent;

import ca.udes.smartagent.rmi.IAgentPiece;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.rmi.NoSuchObjectException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Agent logiciel d'une pièce : serveur RMI et abonné MQTT (tâches 2.2 et 3.1).
 *
 * <p><b>Rôle.</b> Un AgentPiece représente une seule pièce ({@code salleA}, {@code salleB} ou
 * {@code cuisine}). Il :</p>
 * <ul>
 *   <li>s'abonne uniquement aux topics de sa pièce : {@code batiment/{piece}/capteur/#} (mesures)
 *       et {@code batiment/{piece}/actuateur/thermostat} (pour connaître la consigne actuelle,
 *       même quand c'est une règle openHAB qui l'a changée) ;</li>
 *   <li>garde la dernière valeur reçue de chaque capteur ;</li>
 *   <li>répond aux appels RMI de {@link IAgentPiece} ;</li>
 *   <li>publie la consigne reçue par {@link #setConsigneTemperature(double)} sur MQTT ;</li>
 *   <li>détecte un capteur muet depuis 30 s (timeout) ;</li>
 *   <li>consulte ses pièces voisines par RMI (partage d'état entre agents).</li>
 * </ul>
 *
 * <p><b>Concurrence (thread-safety).</b> Trois sortes de threads touchent l'état :
 * le thread de Paho qui reçoit les messages MQTT (écriture), les threads RMI qui servent le
 * coordinateur (lecture), et le thread de surveillance (lecture). Chaque champ de mesure n'est
 * écrit que par le thread MQTT et ne subit pas d'opération « lire-modifier-écrire » :
 * {@code volatile} suffit alors pour que les autres threads voient toujours la dernière valeur.
 * Les horodatages sont dans une {@link ConcurrentHashMap}, sûre pour l'accès concurrent.</p>
 */
public class AgentPiece extends UnicastRemoteObject implements IAgentPiece {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger("AgentPiece");

    /** Un capteur est en timeout s'il n'a rien publié depuis 30 secondes (énoncé, tâche 3.1). */
    public static final long TIMEOUT_MS = 30_000;

    /** Les trois types de mesure publiés par chaque pièce. */
    public static final List<String> TYPES_CAPTEURS = List.of("temperature", "luminosite", "occupation");

    /** Plage acceptée pour une consigne : protège contre une valeur absurde envoyée par erreur. */
    public static final double CONSIGNE_MIN = 10.0;
    /** Borne haute de la consigne acceptée. */
    public static final double CONSIGNE_MAX = 30.0;

    /** Plan de l'étage : salleA — salleB — cuisine. Sert à la consultation des voisins. */
    public static final Map<String, List<String>> VOISINS = Map.of(
            "salleA", List.of("salleB"),
            "salleB", List.of("salleA", "cuisine"),
            "cuisine", List.of("salleB"));

    /** {@code serializeNulls} : une mesure encore inconnue apparaît comme {@code null} dans le JSON. */
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final String nom;
    private final String urlBroker;
    private final String topicCapteurs;
    private final String topicThermostat;
    /** Source de l'heure ; remplaçable dans les tests pour simuler 30 s sans attendre. */
    private final transient LongSupplier horloge;

    // ---- Dernières valeurs reçues (écrites par le thread MQTT, lues par les threads RMI) ----
    private volatile double temperature = Double.NaN;   // NaN = aucune mesure reçue
    private volatile int luminosite = -1;               // -1 = aucune mesure reçue
    private volatile boolean occupation;
    private volatile double consigne = Double.NaN;      // NaN = aucune consigne connue

    /** Heure (ms) du dernier message reçu pour chaque type de capteur. */
    private final transient Map<String, Long> dernierMessageMs = new ConcurrentHashMap<>();

    /** Utilisé seulement par le thread de surveillance, pour ne journaliser que les changements. */
    private transient boolean signaleEnTimeout = true;

    // volatile : le client est créé par le thread de surveillance et utilisé par les threads RMI.
    private transient volatile MqttClient client;
    private transient ScheduledExecutorService surveillance;

    /**
     * Crée l'agent et l'exporte comme objet RMI (sans encore se connecter à MQTT).
     *
     * @param nom       nom de la pièce : {@code salleA}, {@code salleB} ou {@code cuisine}
     * @param urlBroker adresse du broker, par exemple {@code tcp://localhost:1883}
     * @throws RemoteException si l'export RMI échoue
     */
    public AgentPiece(String nom, String urlBroker) throws RemoteException {
        this(nom, urlBroker, System::currentTimeMillis);
    }

    /**
     * Constructeur utilisé par les tests, qui fournissent une horloge contrôlée.
     */
    AgentPiece(String nom, String urlBroker, LongSupplier horloge) throws RemoteException {
        super(); // exporte l'objet : il peut maintenant recevoir des appels RMI
        if (!VOISINS.containsKey(nom)) {
            throw new IllegalArgumentException("Pièce inconnue : " + nom + " (attendu : " + VOISINS.keySet() + ")");
        }
        this.nom = nom;
        this.urlBroker = urlBroker;
        this.horloge = horloge;
        this.topicCapteurs = "batiment/" + nom + "/capteur/#";
        this.topicThermostat = "batiment/" + nom + "/actuateur/thermostat";
    }

    // =====================================================================================
    //  Méthodes distantes (IAgentPiece)
    // =====================================================================================

    /** @return dernière température reçue en °C, ou {@code NaN} si aucune mesure n'est encore arrivée */
    @Override
    public double getTemperature() throws RemoteException {
        return temperature;
    }

    /** @return dernière luminosité reçue, ou {@code -1} si aucune mesure n'est encore arrivée */
    @Override
    public int getLuminosite() throws RemoteException {
        return luminosite;
    }

    /** @return dernière occupation reçue ({@code false} tant qu'aucune mesure n'est arrivée) */
    @Override
    public boolean isOccupee() throws RemoteException {
        return occupation;
    }

    /**
     * Publie une nouvelle consigne sur {@code batiment/{piece}/actuateur/thermostat}.
     *
     * <p>La valeur est écrite avec un point décimal ({@code 21.0}) grâce à {@code Locale.ROOT} :
     * avec la langue française par défaut, {@code String.format} écrirait {@code 21,0}, ce
     * qu'openHAB ne saurait pas lire.</p>
     *
     * @param t consigne en °C, entre {@value #CONSIGNE_MIN} et {@value #CONSIGNE_MAX}
     * @throws IllegalArgumentException si la valeur est hors plage (renvoyée telle quelle au client RMI)
     * @throws RemoteException si le broker MQTT est injoignable : l'appelant sait que la commande a échoué
     */
    @Override
    public void setConsigneTemperature(double t) throws RemoteException {
        if (!Double.isFinite(t) || t < CONSIGNE_MIN || t > CONSIGNE_MAX) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Consigne %.1f °C refusée : plage acceptée [%.0f ; %.0f]", t, CONSIGNE_MIN, CONSIGNE_MAX));
        }
        MqttClient c = client;
        if (c == null || !c.isConnected()) {
            throw new RemoteException("Broker MQTT injoignable : consigne non publiée pour " + nom);
        }
        String charge = String.format(Locale.ROOT, "%.1f", t);
        try {
            // QoS 1 : le broker accuse réception, la commande n'est pas perdue en route.
            c.publish(topicThermostat, charge.getBytes(StandardCharsets.UTF_8), 1, false);
        } catch (MqttException e) {
            throw new RemoteException("Échec de publication MQTT de la consigne pour " + nom, e);
        }
        consigne = t;
        LOG.info("[" + nom + "] Consigne " + charge + " °C publiée sur " + topicThermostat);
    }

    /**
     * Résumé de l'état de la pièce en JSON. Exemple :
     * <pre>{"piece":"salleA","temperature":22.4,"luminosite":340,"occupee":true,
     *  "consigne":21.0,"timeout":false,"capteursEnTimeout":[],"horodatage":"2026-10-28T20:05:00Z"}</pre>
     * Une mesure jamais reçue vaut {@code null}. {@code timeout} est vrai si au moins un capteur
     * est muet depuis 30 s ; le coordinateur s'en sert pour savoir que la valeur est périmée.
     *
     * @return l'état au format JSON
     */
    @Override
    public String getEtatJson() throws RemoteException {
        // On lit chaque champ volatile une seule fois, puis on construit le JSON avec ces copies.
        double temp = temperature;
        double cons = consigne;
        List<String> muets = capteursEnTimeout();

        JsonObject json = new JsonObject();
        json.addProperty("piece", nom);
        json.addProperty("temperature", Double.isNaN(temp) ? null : temp);
        json.addProperty("luminosite", dernierMessageMs.containsKey("luminosite") ? luminosite : null);
        json.addProperty("occupee", dernierMessageMs.containsKey("occupation") ? occupation : null);
        json.addProperty("consigne", Double.isNaN(cons) ? null : cons);
        json.addProperty("timeout", !muets.isEmpty());
        JsonArray liste = new JsonArray();
        muets.forEach(liste::add);
        json.add("capteursEnTimeout", liste);
        json.addProperty("horodatage", Instant.ofEpochMilli(horloge.getAsLong()).toString());
        return GSON.toJson(json);
    }

    // =====================================================================================
    //  Réception MQTT et détection du timeout
    // =====================================================================================

    /**
     * Traite un message MQTT reçu. Appelée par le thread de Paho (et directement par les tests).
     * Un message mal formé est ignoré et journalisé : il ne doit jamais faire planter l'agent.
     *
     * @param topic   topic complet, par exemple {@code batiment/salleA/capteur/temperature}
     * @param charge  contenu texte du message, par exemple {@code 22.4}
     */
    void traiterMessage(String topic, String charge) {
        String valeur = charge.trim();
        try {
            if (topic.equals(topicThermostat)) {
                consigne = Double.parseDouble(valeur);
                return;
            }
            String type = topic.substring(topic.lastIndexOf('/') + 1);
            switch (type) {
                case "temperature" -> temperature = Double.parseDouble(valeur);
                // Math.round accepte aussi « 340.0 » si le simulateur envoie un décimal.
                case "luminosite" -> luminosite = (int) Math.round(Double.parseDouble(valeur));
                case "occupation" -> occupation = lireBooleen(valeur);
                default -> {
                    LOG.fine("[" + nom + "] Type de capteur inconnu ignoré : " + topic);
                    return;
                }
            }
            // L'horodatage n'est mis à jour qu'après une valeur valide.
            dernierMessageMs.put(type, horloge.getAsLong());
        } catch (IllegalArgumentException e) { // inclut NumberFormatException
            LOG.warning("[" + nom + "] Message ignoré (format invalide) : " + topic + " = '" + valeur + "'");
        }
    }

    /** Accepte {@code true}/{@code false} (contrat MQTT), sans tenir compte de la casse. */
    private static boolean lireBooleen(String valeur) {
        if (valeur.equalsIgnoreCase("true")) {
            return true;
        }
        if (valeur.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException("Booléen attendu : " + valeur);
    }

    /**
     * Liste des capteurs muets : jamais reçus, ou dont le dernier message date d'au moins 30 s.
     *
     * @return liste vide si tous les capteurs publient normalement
     */
    List<String> capteursEnTimeout() {
        long maintenant = horloge.getAsLong();
        List<String> muets = new ArrayList<>();
        for (String type : TYPES_CAPTEURS) {
            Long dernier = dernierMessageMs.get(type);
            if (dernier == null || maintenant - dernier >= TIMEOUT_MS) {
                muets.add(type);
            }
        }
        return muets;
    }

    /**
     * Vérification périodique (toutes les 5 s) : journalise le passage en timeout et le retour
     * à la normale. On ne journalise que les changements, pour garder des logs lisibles en démo.
     */
    void verifierTimeout() {
        List<String> muets = capteursEnTimeout();
        boolean enTimeout = !muets.isEmpty();
        if (enTimeout && !signaleEnTimeout) {
            LOG.warning("[" + nom + "] TIMEOUT : aucun message depuis " + TIMEOUT_MS / 1000
                    + " s pour " + muets + " (dernière valeur conservée)");
        } else if (!enTimeout && signaleEnTimeout) {
            LOG.info("[" + nom + "] Tous les capteurs publient normalement");
        }
        signaleEnTimeout = enTimeout;
    }

    // =====================================================================================
    //  Consultation des voisins par RMI (partage d'état entre agents)
    // =====================================================================================

    /**
     * Interroge un agent voisin par RMI et renvoie son état JSON.
     *
     * @param voisin nom de la pièce voisine
     * @return l'état JSON du voisin
     * @throws RemoteException   si le voisin ou le registre ne répond pas
     * @throws NotBoundException si le voisin n'est pas (ou plus) enregistré
     */
    public String consulterVoisin(String voisin) throws RemoteException, NotBoundException {
        Registry registre = LocateRegistry.getRegistry(LanceurRegistre.PORT_REGISTRE);
        IAgentPiece agentVoisin = (IAgentPiece) registre.lookup("agent-" + voisin);
        return agentVoisin.getEtatJson();
    }

    /** Tâche périodique : journalise l'état des voisins. Une panne d'un voisin n'est qu'un message. */
    private void consulterVoisins() {
        for (String voisin : VOISINS.get(nom)) {
            try {
                LOG.info("[" + nom + "] Voisin " + voisin + " consulté par RMI : " + consulterVoisin(voisin));
            } catch (RemoteException | NotBoundException e) {
                LOG.info("[" + nom + "] Voisin " + voisin + " injoignable pour l'instant ("
                        + e.getClass().getSimpleName() + ")");
            }
        }
    }

    // =====================================================================================
    //  Cycle de vie : démarrage, connexion MQTT, arrêt
    // =====================================================================================

    /**
     * Démarre la connexion MQTT et les tâches de fond (timeout et consultation des voisins).
     * Si le broker est absent, l'agent reste joignable par RMI et réessaie toutes les 5 s.
     */
    public void demarrer() {
        surveillance = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-" + nom + "-surveillance");
            t.setDaemon(true);
            return t;
        });
        surveillance.execute(this::connecterMqtt);
        // Important : une exception non attrapée annulerait silencieusement une tâche périodique ;
        // nos deux méthodes attrapent donc leurs erreurs elles-mêmes.
        surveillance.scheduleAtFixedRate(this::verifierTimeout, 5, 5, TimeUnit.SECONDS);
        surveillance.scheduleAtFixedRate(this::consulterVoisins, 15, 30, TimeUnit.SECONDS);
    }

    /** Première connexion au broker ; en cas d'échec, nouvel essai dans 5 s. */
    private void connecterMqtt() {
        try {
            if (client == null) {
                // Identifiant unique : deux clients avec le même ID s'éjecteraient mutuellement du broker.
                String idClient = "agent-" + nom + "-" + UUID.randomUUID().toString().substring(0, 8);
                MqttClient nouveau = new MqttClient(urlBroker, idClient, new MemoryPersistence());
                nouveau.setTimeToWait(15_000); // laisse à la connexion MQTT le temps de se terminer (timeout connexion : 10 s)
                nouveau.setCallback(new RappelMqtt());
                client = nouveau;
            }
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true); // Paho se reconnecte seul après une coupure
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(20);
            if (!client.isConnected()) {
                client.connect(options);
            }
            abonner();
            LOG.info("[" + nom + "] Connecté au broker " + urlBroker);
        } catch (MqttException e) {
            LOG.log(Level.WARNING,
                    "[" + nom + "] Échec MQTT (" + urlBroker + ") : nouvel essai dans 5 s", e);
            if (surveillance != null && !surveillance.isShutdown()) {
                surveillance.schedule(this::connecterMqtt, 5, TimeUnit.SECONDS);
            }
        }
    }

    /** Abonnement aux topics de la pièce (à refaire après chaque reconnexion, car cleanSession=true). */
    private void abonner() {
        try {
            client.subscribe(new String[] {topicCapteurs, topicThermostat}, new int[] {0, 1});
            LOG.info("[" + nom + "] Abonné à " + topicCapteurs + " et " + topicThermostat);
        } catch (MqttException e) {
            LOG.log(Level.WARNING, "[" + nom + "] Échec de l'abonnement MQTT", e);
        }
    }

    /** Callbacks appelés par Paho sur son propre thread. */
    private final class RappelMqtt implements MqttCallbackExtended {
        @Override
        public void connectComplete(boolean reconnexion, String serverUri) {
            if (reconnexion) {
                LOG.info("[" + nom + "] Reconnecté au broker");
                // On ne s'abonne pas directement ici : un appel bloquant depuis un callback Paho
                // peut provoquer un interblocage. On délègue au thread de surveillance.
                surveillance.execute(AgentPiece.this::abonner);
            }
        }

        @Override
        public void connectionLost(Throwable cause) {
            LOG.warning("[" + nom + "] Connexion MQTT perdue (" + cause.getMessage() + "), reconnexion automatique…");
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            traiterMessage(topic, new String(message.getPayload(), StandardCharsets.UTF_8));
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // Rien à faire : publish() est déjà bloquant jusqu'à l'accusé de réception.
        }
    }

    /**
     * Arrêt propre : retire l'agent du registre (le coordinateur le voit tout de suite absent),
     * ferme MQTT et désexporte l'objet RMI.
     *
     * @param registre registre où l'agent est enregistré (peut être {@code null})
     */
    public void arreter(Registry registre) {
        try {
            if (registre != null) {
                registre.unbind("agent-" + nom);
            }
        } catch (RemoteException | NotBoundException e) {
            // Le registre est peut-être déjà arrêté : rien d'autre à faire.
        }
        if (surveillance != null) {
            surveillance.shutdownNow();
        }
        try {
            MqttClient c = client;
            if (c != null) {
                if (c.isConnected()) {
                    c.disconnect(1_000);
                }
                c.close();
            }
        } catch (MqttException e) {
            LOG.log(Level.FINE, "[" + nom + "] Fermeture MQTT incomplète", e);
        }
        try {
            UnicastRemoteObject.unexportObject(this, true);
        } catch (NoSuchObjectException e) {
            // Déjà désexporté.
        }
        LOG.info("[" + nom + "] Agent arrêté");
    }

    /** @return nom de la pièce gérée par cet agent */
    public String getNom() {
        return nom;
    }

    // =====================================================================================
    //  Programme principal : une JVM par pièce
    // =====================================================================================

    /**
     * Lance l'agent d'une pièce.
     *
     * <pre>java -cp agent-piece-1.0-SNAPSHOT-all.jar ca.udes.smartagent.agent.AgentPiece salleA [tcp://localhost:1883]</pre>
     *
     * Adresse du broker : 2e argument, sinon variable d'environnement {@code MQTT_BROKER},
     * sinon {@code tcp://localhost:1883}.
     *
     * @param args nom de la pièce, puis adresse du broker (facultative)
     * @throws Exception si l'enregistrement RMI échoue
     */
    public static void main(String[] args) throws Exception {
        Journalisation.configurer();
        if (args.length < 1) {
            System.err.println("Usage : AgentPiece <salleA|salleB|cuisine> [tcp://hote:1883]");
            System.exit(1);
        }
        String piece = args[0];
        String broker = args.length > 1 ? args[1]
                : System.getenv().getOrDefault("MQTT_BROKER", "tcp://localhost:1883");

        // Les souches RMI contiennent l'adresse IP du serveur. Sous Windows avec Docker/WSL, Java
        // peut choisir l'IP d'une carte réseau virtuelle injoignable : on impose 127.0.0.1
        // (sauf si l'utilisateur a passé -Djava.rmi.server.hostname=... lui-même).
        if (System.getProperty("java.rmi.server.hostname") == null) {
            System.setProperty("java.rmi.server.hostname", "127.0.0.1");
        }

        AgentPiece agent = new AgentPiece(piece, broker);
        Registry registre = LanceurRegistre.obtenirOuCreer();
        registre.rebind("agent-" + piece, agent); // rebind : remplace un ancien enregistrement éventuel
        LOG.info("[" + piece + "] Enregistré dans le registre RMI sous « agent-" + piece + " » (port "
                + LanceurRegistre.PORT_REGISTRE + ")");

        agent.demarrer();
        // Ctrl+C : désinscription propre du registre.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> agent.arreter(registre)));

        // Garde le programme actif tant que l'utilisateur n'a pas demandé son arrêt (Ctrl+C).
        // Sans cette attente, exec:java peut terminer le programme et interrompre les threads MQTT.
        new CountDownLatch(1).await();
    }
}
