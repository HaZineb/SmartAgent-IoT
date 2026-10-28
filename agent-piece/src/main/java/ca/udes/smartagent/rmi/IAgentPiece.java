package ca.udes.smartagent.rmi;

import java.rmi.Remote;
import java.rmi.RemoteException;

/** Contrat RMI commun aux trois agents de pièce. */
public interface IAgentPiece extends Remote {
    double getTemperature() throws RemoteException;
    int getLuminosite() throws RemoteException;
    boolean isOccupee() throws RemoteException;
    void setConsigneTemperature(double temperature) throws RemoteException;
    String getEtatJson() throws RemoteException;
}
