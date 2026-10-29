package ca.udes.smartagent.coordinateur;

import ca.udes.smartagent.agent.AgentPiece;

import java.util.Optional;

/**
 * Politique d'équilibrage thermique entre deux pièces adjacentes (tâche 2.3).
 *
 * <p>Règle : si l'écart absolu de température dépasse {@value #SEUIL_ECART} °C, on envoie à la
 * pièce <b>la plus chaude</b> une consigne égale à la <b>moyenne</b> des deux températures,
 * arrondie au demi-degré. La pièce chaude se refroidit vers un niveau intermédiaire, ce qui
 * réduit l'écart sans pénaliser la pièce froide.</p>
 *
 * <p>Cette classe ne fait aucun appel réseau : elle est « pure », donc facile à tester.</p>
 */
public final class PolitiqueEquilibrage {

    /** Écart (°C) au-delà duquel le coordinateur intervient. Un écart de 4,0 exactement ne déclenche rien. */
    public static final double SEUIL_ECART = 4.0;

    private PolitiqueEquilibrage() {
    }

    /**
     * Décision prise pour une paire.
     *
     * @param pieceCible pièce qui reçoit la nouvelle consigne (la plus chaude)
     * @param consigne   consigne à envoyer, en °C
     * @param ecart      écart absolu observé entre les deux pièces, en °C
     */
    public record Decision(String pieceCible, double consigne, double ecart) {
    }

    /**
     * Évalue une paire de pièces adjacentes.
     *
     * @param pieceA nom de la première pièce
     * @param tA     température de la première pièce (°C)
     * @param pieceB nom de la seconde pièce
     * @param tB     température de la seconde pièce (°C)
     * @return une décision si l'écart dépasse le seuil, sinon {@link Optional#empty()} ;
     *         vide aussi si une des températures est inconnue ({@code NaN})
     */
    public static Optional<Decision> evaluer(String pieceA, double tA, String pieceB, double tB) {
        if (Double.isNaN(tA) || Double.isNaN(tB)) {
            return Optional.empty();
        }
        double ecart = Math.abs(tA - tB);
        if (ecart <= SEUIL_ECART) {
            return Optional.empty();
        }
        String plusChaude = tA > tB ? pieceA : pieceB;
        double moyenne = (tA + tB) / 2.0;
        double consigne = Math.round(moyenne * 2.0) / 2.0; // arrondi au 0,5 °C le plus proche
        // On reste dans la plage acceptée par les agents (sinon l'agent refuserait la commande).
        consigne = Math.max(AgentPiece.CONSIGNE_MIN, Math.min(AgentPiece.CONSIGNE_MAX, consigne));
        return Optional.of(new Decision(plusChaude, consigne, ecart));
    }
}
