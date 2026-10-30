
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
 * Simule trois pieces, chacune avec :
 * - un capteur de temperature ;
 * - un capteur de luminosite ;
 * - un capteur d'occupation.
 *
 * Publie les neuf mesures toutes les cinq secondes.
 * Recoit les consignes des thermostats via MQTT.
 *
 * Mode de demonstration :
 * le capteur de temperature de Salle A cesse de publier
 * pendant 40 secondes, puis reprend automatiquement.
 * Les huit autres capteurs continuent de fonctionner.
 */
public class SensorSimulator {

    // Adresse du broker MQTT Mosquitto.
    private static final String BROKER = "tcp://localhost:1883";

    // Intervalle de publication des capteurs : 5 secondes.
    private static final long INTERVALLE_MS = 5000;

    // Limites de temperature du simulateur.
    private static final double TEMP_MIN = 18.0;
    private static final double TEMP_MAX = 28.0;

    // Variation maximale vers la consigne par cycle.
    private static final double PAS_THERMOSTAT = 0.2;

    // Configuration du scenario de panne.
    // false : simulation normale sans panne.
    // true  : panne automatique de temperature Salle A.
    private static final boolean MODE_TEST_PANNE = true;

    // La panne commence apres 20 secondes.
    private static final long DEBUT_PANNE_MS = 20000;

    // La panne dure 40 secondes.
    private static final long DUREE_PANNE_MS = 40000;

    // Les trois pieces du batiment.
    private static final Piece[] PIECES = {
            new Piece("salleA", 22.0, 450, true),
            new Piece("salleB", 20.0, 350, false),
            new Piece("cuisine", 25.0, 600, true)
    };

    /**
     * Etat des capteurs et du thermostat d'une piece.
     *
     * Les methodes synchronized protegent les donnees
     * partagees entre la simulation et le callback MQTT.
     */
    private static class Piece {

        final String nom;

        private double temperature;
        private int luminosite;
        private boolean occupation;

        // null signifie aucune consigne recue.
        private Double consigne = null;

        Piece(
                String nom,
                double temperature,
                int luminosite,
                boolean occupation
        ) {
            this.nom = nom;
            this.temperature = temperature;
            this.luminosite = luminosite;
            this.occupation = occupation;
        }

        /**
         * Enregistre la nouvelle consigne du thermostat.
         */
        synchronized void definirConsigne(double valeur) {
            consigne = valeur;
        }

        /**
         * Actualise les capteurs et retourne un instantane.
         */
        synchronized Mesures actualiser() {

            ThreadLocalRandom hasard =
                    ThreadLocalRandom.current();

            if (consigne != null) {

                // La temperature se rapproche de la consigne.
                double ecart = consigne - temperature;

                double variation = Math.max(
                        -PAS_THERMOSTAT,
                        Math.min(PAS_THERMOSTAT, ecart)
                );

                temperature += variation;

            } else {

                // Variation naturelle sans consigne.
                temperature += hasard.nextDouble(-0.3, 0.3);
            }

            // Respect des limites de temperature.
            temperature = Math.max(
                    TEMP_MIN,
                    Math.min(TEMP_MAX, temperature)
            );

            // Variation de la luminosite.
            luminosite += hasard.nextInt(-30, 31);

            luminosite = Math.max(
                    100,
                    Math.min(800, luminosite)
            );

            // Probabilite de 10 % de changement d'occupation.
            if (hasard.nextDouble() < 0.10) {
                occupation = !occupation;
            }

            return new Mesures(
                    temperature,
                    luminosite,
                    occupation
            );
        }
    }

    /**
     * Instantane des mesures d'une piece.
     */
    private static class Mesures {

        final double temperature;
        final int luminosite;
        final boolean occupation;

        Mesures(
                double temperature,
                int luminosite,
                boolean occupation
        ) {
            this.temperature = temperature;
            this.luminosite = luminosite;
            this.occupation = occupation;
        }
    }

    /**
     * Recherche une piece par son nom.
     */
    private static Piece trouverPiece(String nom) {

        for (Piece piece : PIECES) {

            if (piece.nom.equals(nom)) {
                return piece;
            }
        }

        return null;
    }

    /**
     * Determine si le capteur de temperature de Salle A
     * doit etre temporairement indisponible.
     */
    private static boolean panneTemperatureSalleA(
            String nomPiece,
            long tempsEcoule
    ) {

        if (!MODE_TEST_PANNE) {
            return false;
        }

        return nomPiece.equals("salleA")
                && tempsEcoule >= DEBUT_PANNE_MS
                && tempsEcoule < DEBUT_PANNE_MS + DUREE_PANNE_MS;
    }

    public static void main(String[] args) throws Exception {

        // Client MQTT avec identifiant unique.
        MqttClient client = new MqttClient(
                BROKER,
                MqttClient.generateClientId()
        );

        try {

            // Reception des commandes MQTT.
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

                    String contenu = new String(
                            message.getPayload(),
                            StandardCharsets.UTF_8
                    ).trim();

                    // Format :
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
                                "Piece inconnue : " + parties[1]
                        );
                        return;
                    }

                    try {

                        double cible = Double.parseDouble(contenu);

                        // Verification de la consigne.
                        if (!Double.isFinite(cible)
                                || cible < TEMP_MIN
                                || cible > TEMP_MAX) {

                            System.err.println(
                                    "Consigne refusee : " + contenu
                            );
                            return;
                        }

                        piece.definirConsigne(cible);

                        System.out.println(
                                "Commande recue : " + piece.nom
                                        + " -> " + cible + " degres"
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
                    // Aucune action supplementaire.
                }
            });

            // Connexion a Mosquitto.
            client.connect();

            System.out.println("Connecte a Mosquitto !");

            // Abonnement aux commandes des thermostats.
            client.subscribe(
                    "batiment/+/actuateur/thermostat"
            );

            System.out.println(
                    "Abonne aux commandes des thermostats."
            );

            if (MODE_TEST_PANNE) {

                System.out.println(
                        "MODE TEST PANNE ACTIVE"
                );

                System.out.println(
                        "Capteur concerne : temperature Salle A"
                );

                System.out.println(
                        "Debut de panne : 20 secondes"
                );

                System.out.println(
                        "Duree de panne : 40 secondes"
                );
            }

            // Point de depart de la simulation.
            long debutSimulation = System.currentTimeMillis();

            boolean panneAnnoncee = false;
            boolean repriseAnnoncee = false;

            // Simulation continue.
            while (true) {

                long tempsEcoule =
                        System.currentTimeMillis() - debutSimulation;

                for (Piece piece : PIECES) {

                    // Actualisation des trois mesures.
                    Mesures mesures = piece.actualiser();

                    boolean capteurEnPanne =
                            panneTemperatureSalleA(
                                    piece.nom,
                                    tempsEcoule
                            );

                    // Publication de la temperature.
                    if (capteurEnPanne) {

                        if (!panneAnnoncee) {

                            System.out.println(
                                    "================================"
                            );

                            System.out.println(
                                    "PANNE SIMULEE : "
                                            + "temperature Salle A"
                            );

                            System.out.println(
                                    "Arret des publications MQTT "
                                            + "de ce capteur"
                            );

                            System.out.println(
                                    "Les autres capteurs restent actifs"
                            );

                            System.out.println(
                                    "================================"
                            );

                            panneAnnoncee = true;
                        }

                    } else {

                        // Premiere publication apres la panne.
                        if (piece.nom.equals("salleA")
                                && panneAnnoncee
                                && !repriseAnnoncee) {

                            System.out.println(
                                    "================================"
                            );

                            System.out.println(
                                    "RECUPERATION DU CAPTEUR"
                            );

                            System.out.println(
                                    "Temperature Salle A : "
                                            + "publication MQTT retablie"
                            );

                            System.out.println(
                                    "================================"
                            );

                            repriseAnnoncee = true;
                        }

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
                    }

                    // Publication de la luminosite.
                    // Elle continue meme pendant la panne.
                    publier(
                            client,
                            piece.nom,
                            "luminosite",
                            String.valueOf(mesures.luminosite)
                    );

                    // Publication de l'occupation.
                    // Elle continue meme pendant la panne.
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

            // Fermeture propre de la connexion MQTT.
            if (client.isConnected()) {
                client.disconnect();
            }

            client.close();
        }
    }

    /**
     * Publie une mesure sur MQTT.
     *
     * @param client client MQTT
     * @param piece nom de la piece
     * @param capteur type de capteur
     * @param valeur valeur a publier
     * @throws Exception si la publication echoue
     */
    private static void publier(
            MqttClient client,
            String piece,
            String capteur,
            String valeur
    ) throws Exception {

        String topic = "batiment/" + piece
                + "/capteur/" + capteur;

        MqttMessage message = new MqttMessage(
                valeur.getBytes(StandardCharsets.UTF_8)
        );

        client.publish(topic, message);

        System.out.println(topic + " = " + valeur);
    }
}
