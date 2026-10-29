package ca.udes.smartagent.coordinateur;

import ca.udes.smartagent.rmi.IAgentPiece;

import java.rmi.NotBoundException;
import java.rmi.RemoteException;

/**
 * Moyen de trouver l'agent d'une pièce.
 *
 * <p>En production, c'est le registre RMI ({@code registre.lookup("agent-" + piece)}). Dans les
 * tests JUnit, c'est une simple table de faux agents : le coordinateur peut ainsi être testé sans
 * réseau, y compris en simulant des pannes.</p>
 */
@FunctionalInterface
public interface AnnuaireAgents {

    /**
     * @param piece nom de la pièce, par exemple {@code salleA}
     * @return l'agent (souche RMI en production)
     * @throws RemoteException   si le registre est injoignable
     * @throws NotBoundException si aucun agent n'est enregistré pour cette pièce
     */
    IAgentPiece trouver(String piece) throws RemoteException, NotBoundException;
}
