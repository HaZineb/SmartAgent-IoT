
package ca.udes.smartagent.simulator;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public class SensorSimulator {

    private static final String BROKER = "tcp://localhost:1883";

    private static final String[] PIECES = {
            "salleA", "salleB", "cuisine"
    };

    public static void main(String[] args) throws Exception {

        MqttClient client = new MqttClient(
                BROKER,
                MqttClient.generateClientId()
        );

        try {
            client.connect();
            System.out.println("Connecté à Mosquitto !");

            // Répéter la simulation toutes les 5 secondes
            while (true) {

                for (String piece : PIECES) {

                    double temperature =
                            ThreadLocalRandom.current()
                                    .nextDouble(18.0, 28.0);

                    int luminosite =
                            ThreadLocalRandom.current()
                                    .nextInt(100, 801);

                    boolean occupation =
                            ThreadLocalRandom.current()
                                    .nextBoolean();

                    publier(client, piece, "temperature",
                            String.format(Locale.US, "%.1f", temperature));

                    publier(client, piece, "luminosite",
                            String.valueOf(luminosite));

                    publier(client, piece, "occupation",
                            String.valueOf(occupation));
                }

                System.out.println("-------------------------");

                Thread.sleep(5000);
            }

        } finally {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        }
    }

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
