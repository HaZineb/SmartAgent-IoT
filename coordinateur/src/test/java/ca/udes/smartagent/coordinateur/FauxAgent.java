package ca.udes.smartagent.coordinateur;

import ca.udes.smartagent.rmi.IAgentPiece;

import java.rmi.ConnectException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Faux agent pour les tests : implémente IAgentPiece en mémoire, sans RMI ni MQTT.
 * On règle sa température, son timeout, ou on le met « en panne » pour qu'il lance
 * une {@link RemoteException}, comme le ferait une souche RMI vers une JVM arrêtée.
 */
class FauxAgent implements IAgentPiece {

    double temperature;
    boolean timeout;
    boolean enPanne;
    final List<Double> consignesRecues = new ArrayList<>();

    FauxAgent(double temperature) {
        this.temperature = temperature;
    }

    private void verifierPanne() throws RemoteException {
        if (enPanne) {
            throw new ConnectException("Connection refused (panne simulée)");
        }
    }

    @Override
    public double getTemperature() throws RemoteException {
        verifierPanne();
        return temperature;
    }

    @Override
    public int getLuminosite() throws RemoteException {
        verifierPanne();
        return 300;
    }

    @Override
    public boolean isOccupee() throws RemoteException {
        verifierPanne();
        return true;
    }

    @Override
    public void setConsigneTemperature(double t) throws RemoteException {
        verifierPanne();
        consignesRecues.add(t);
    }

    @Override
    public String getEtatJson() throws RemoteException {
        verifierPanne();
        // Comme le vrai agent : la consigne est la dernière reçue, ou null si aucune.
        String consigne = consignesRecues.isEmpty() ? "null"
                : String.format(Locale.ROOT, "%.1f", consignesRecues.get(consignesRecues.size() - 1));
        return String.format(Locale.ROOT, "{\"temperature\":%.1f,\"timeout\":%b,\"consigne\":%s}",
                temperature, timeout, consigne);
    }
}
