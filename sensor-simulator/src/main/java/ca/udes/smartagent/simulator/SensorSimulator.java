
package ca.udes.smartagent.simulator;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulateur IoT du projet SmartAgent-IoT.
 *
 * Simule trois pièces possédant chacune :
 * - un capteur de température ;
 * - un capteur de luminosité ;
 * - un capteur d'occupation.
 *
 * Publie les neuf mesures toutes les cinq secondes.
 *
 * Le simulateur reçoit aussi les consignes des thermostats
 * via MQTT. La température évolue progressivement vers
 * la consigne demandée.
 */
public class SensorSimulator {

    // Adresse de Mosquitto depuis notre programme Java local.
    private static final String BROKER = "tcp://localhost:1883";

    // Durée entre deux cycles de simulation.
    private static final long INTERVALLE_MS = 5000;

    // Températures minimales et maximales acceptées.
    private static final double TEMP_MIN = 18.0;
    private static final double TEMP_MAX = 28.0;

    // Vitesse maximale de régulation : 0,2 °C par cycle.
    private static final double PAS_THERMOSTAT = 0.2;

    // Trois pièces avec leurs valeurs initiales.
    private static final Piece[] PIECES = {
            new Piece("salleA", 22.0, 450, true),
            new Piece("salleB", 20.0, 350, false),
            new Piece("cuisine", 25.0, 600, true)
    };

    /**
     * Représente l'état des capteurs et du thermostat
     * d'une pièce.
     *
     * synchronized protège les données partagées entre
     * le thread de simulation et le callback MQTT.
     */
    private static class Piece {

        final String nom;

        private double temperature;
        private int luminosite;
        private boolean occupation;

        // Consigne du thermostat. null signifie qu'aucune
        // consigne n'a encore été reçue.
        private Double consigne = null;

        Piece(String nom, double temperature,
              int luminosite, boolean occupation) {

            this.nom = nom;
            this.temperature = temperature;
            this.luminosite = luminosite;
            this.occupation = occupation;
        }

        /**
         * Enregistre une nouvelle température cible.
         */
        synchronized void definirConsigne(double valeur) {
            consigne = valeur;
        }

        /**
         * Fait évoluer les mesures puis retourne un instantané.
         * L'instantané permet de publier des valeurs cohérentes
         * sans conserver le verrou pendant l'envoi MQTT.
         */
        synchronized Mesures actualiser() {

            ThreadLocalRandom hasard =
                    ThreadLocalRandom.current();

            if (consigne != null) {

                // Écart entre la température actuelle et la cible.
                double ecart = consigne - temperature;

                // Déplacement progressif vers la consigne.
                // Si l'écart est petit, on applique uniquement
                // l'écart restant pour ne pas dépasser la cible.
                double variation = Math.max(
                        -PAS_THERMOSTAT,
                        Math.min(PAS_THERMOSTAT, ecart)
                );

                temperature += variation;

            } else {

                // Sans consigne, la température subit une
                // petite variation aléatoire naturelle.
                temperature += hasard.nextDouble(-0.3, 0.3);
            }

            // Respect des limites de température.
            temperature = Math.max(
                    TEMP_MIN,
                    Math.min(TEMP_MAX, temperature)
            );

            // Évolution progressive de la luminosité.
            luminosite += hasard.nextInt(-30, 31);

            luminosite = Math.max(
                    100,
                    Math.min(800, luminosite)
            );

            // La présence change avec une probabilité de 10 %
            // à chaque cycle.
            if (hasard.nextDouble() < 0.10) {
                occupation = !occupation;
            }

            return new Mesures(
                    temperature, luminosite, occupation
            );
        }
    }

    /**
     * Instantané des trois mesures d'une pièce.
     */
    private static class Mesures {

        final double temperature;
        final int luminosite;
        final boolean occupation;

        Mesures(double temperature, int luminosite,
                boolean occupation) {

            this.temperature = temperature;
            this.luminosite = luminosite;
            this.occupation = occupation;
        }
    }

    /**
     * Recherche une pièce à partir de son nom.
     */
    private static Piece trouverPiece(String nom) {

        for (Piece piece : PIECES) {
            if (piece.nom.equals(nom)) {
                return piece;
            }
        }

        return null;
    }

    public static void main(String[] args) throws Exception {

        // Création du client MQTT avec un identifiant unique.
        MqttClient client = new MqttClient(
                BROKER,
                MqttClient.generateClientId()
        );

        try {

            // Le callback est exécuté lorsqu'un événement MQTT
            // survient, notamment la réception d'une commande.
            client.setCallback(new MqttCallback() {

                @Override
                public void connectionLost(Throwable cause) {

                    System.err.println(
                            "Connexion MQTT perdue : "
                                    + cause.getMessage()
                    );
                }

                @Override
                public void messageArrived(
                        String topic,
                        MqttMessage message
                ) {

                    // Lecture du contenu du message MQTT.
                    String contenu = new String(
                            message.getPayload(),
                            StandardCharsets.UTF_8
                    ).trim();

                    // Format attendu :
                    // batiment/{piece}/actuateur/thermostat
                    String[] parties = topic.split("/");

                    if (parties.length != 4
                            || !parties[0].equals("batiment")
                            || !parties[2].equals("actuateur")
                            || !parties[3].equals("thermostat")) {

                        System.err.println(
                                "Topic de commande inconnu : " + topic
                        );
                        return;
                    }

                    Piece piece = trouverPiece(parties[1]);

                    if (piece == null) {
                        System.err.println(
                                "Pièce inconnue : " + parties[1]
                        );
                        return;
                    }

                    try {

                        // La valeur MQTT représente une température
                        // cible, par exemple "24.0".
                        double cible = Double.parseDouble(contenu);

                        // Rejet des consignes non finies ou
                        // hors de la plage autorisée.
                        if (!Double.isFinite(cible)
                                || cible < TEMP_MIN
                                || cible > TEMP_MAX) {

                            System.err.println(
                                    "Consigne refusée : " + contenu
                            );
                            return;
                        }

                        // Mise à jour de la consigne de la pièce.
                        piece.definirConsigne(cible);

                        System.out.println(
                                "Commande reçue : " + piece.nom
                                        + " -> " + cible + " °C"
                        );

                    } catch (NumberFormatException e) {

                        System.err.println(
                                "Valeur de thermostat invalide : "
                                        + contenu
                        );
                    }
                }

                @Override
                public void deliveryComplete(
                        IMqttDeliveryToken token
                ) {
                    // Aucun traitement supplémentaire nécessaire
                    // après la confirmation d'une publication.
                }
            });

            // Connexion au broker MQTT.
            client.connect();

            System.out.println("Connecté à Mosquitto !");

            // Abonnement aux commandes des trois thermostats.
            // Le caractère + remplace un seul niveau du topic,
            // ici le nom de la pièce.
            client.subscribe(
                    "batiment/+/actuateur/thermostat"
            );

            System.out.println(
                    "Abonné aux commandes des thermostats."
            );

            // Simulation continue.
            while (true) {

                for (Piece piece : PIECES) {

                    // Mise à jour des mesures de la pièce.
                    Mesures mesures = piece.actualiser();

                    // Publication de la température avec
                    // deux chiffres après le point décimal.
                    publier(
                            client,
                            piece.nom,
                            "temperature",
                            String.format(
                                    Locale.US,
                                    "%.2f",
                                    mesures.temperature
                            )
                    );

                    // Publication de la luminosité.
                    publier(
                            client,
                            piece.nom,
                            "luminosite",
                            String.valueOf(mesures.luminosite)
                    );

                    // Publication de l'occupation.
                    publier(
                            client,
                            piece.nom,
                            "occupation",
                            String.valueOf(mesures.occupation)
                    );
                }

                System.out.println("-------------------------");

                // Attente de cinq secondes.
                Thread.sleep(INTERVALLE_MS);
            }

        } finally {

            // Fermeture des ressources si la simulation
            // se termine ou rencontre une erreur.
            if (client.isConnected()) {
                client.disconnect();
            }

            client.close();
        }
    }

    /**
     * Publie une valeur de capteur sur MQTT.
     *
     * @param client client connecté à Mosquitto
     * @param piece nom de la pièce
     * @param capteur type de capteur
     * @param valeur valeur transmise
     * @throws Exception si la publication échoue
     */
    private static void publier(
            MqttClient client,
            String piece,
            String capteur,
            String valeur
    ) throws Exception {

        // Construction du topic de mesure.
        String topic = "batiment/" + piece
                + "/capteur/" + capteur;

        // Encodage de la valeur en UTF-8.
        MqttMessage message = new MqttMessage(
                valeur.getBytes(StandardCharsets.UTF_8)
        );

        // Publication vers Mosquitto.
        client.publish(topic, message);

        // Affichage pour vérifier les échanges MQTT.
        System.out.println(topic + " = " + valeur);
    }
}
