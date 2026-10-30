
package ca.udes.smartagent.coordinateur;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Communication entre le coordinateur Java et openHAB via REST.
 *
 * Cette classe permet :
 * 1. D'afficher les décisions du coordinateur.
 * 2. D'afficher automatiquement l'état des agents RMI.
 *
 * Les requêtes sont asynchrones pour ne pas bloquer
 * les cycles d'équilibrage du coordinateur.
 */
public class ClientOpenHab implements Consumer<String> {

    public static final String ITEM_DECISION = "Coordinateur_Decision";

    /** Association entre les noms des agents et les Items openHAB. */
    private static final Map<String, String> ITEMS_AGENTS = Map.of(
            "salleA", "Agent_SalleA_Statut",
            "salleB", "Agent_SalleB_Statut",
            "cuisine", "Agent_Cuisine_Statut"
    );

    private static final Logger LOG = Logger.getLogger("OpenHAB");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final String urlBase;
    private final String jeton;

    /**
     * Initialise le client REST openHAB.
     *
     * @param urlBase adresse d'openHAB
     * @param jeton jeton facultatif d'authentification
     */
    public ClientOpenHab(String urlBase, String jeton) {
        this.urlBase = urlBase.endsWith("/")
                ? urlBase.substring(0, urlBase.length() - 1)
                : urlBase;

        this.jeton = (jeton == null || jeton.isBlank())
                ? null
                : jeton;
    }

    /**
     * Envoie une décision du coordinateur à openHAB.
     * Cette méthode conserve le comportement existant.
     */
    @Override
    public void accept(String decision) {
        envoyerEtat(ITEM_DECISION, decision);
    }

    /**
     * Met à jour le statut d'un agent dans openHAB.
     *
     * @param piece salleA, salleB ou cuisine
     * @param disponible true si l'agent répond par RMI
     */
    public void envoyerStatutAgent(String piece, boolean disponible) {

        String item = ITEMS_AGENTS.get(piece);

        if (item == null) {
            LOG.warning("Agent inconnu : " + piece);
            return;
        }

        String statut = disponible ? "ONLINE" : "OFFLINE";

        envoyerEtat(item, statut);
    }

    /**
     * Envoie une valeur texte vers un Item openHAB
     * en utilisant une requête HTTP PUT asynchrone.
     */
    private void envoyerEtat(String nomItem, String valeur) {

        URI uri = URI.create(
                urlBase + "/rest/items/" + nomItem + "/state"
        );

        HttpRequest.Builder requete = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        valeur,
                        StandardCharsets.UTF_8
                ));

        if (jeton != null) {
            requete.header("Authorization", "Bearer " + jeton);
        }

        http.sendAsync(
                requete.build(),
                HttpResponse.BodyHandlers.discarding()
        ).whenComplete((reponse, erreur) -> {

            if (erreur != null) {
                LOG.warning(
                        "openHAB injoignable (" + uri + ") : "
                                + erreur.getClass().getSimpleName()
                );

            } else if (reponse.statusCode() >= 300) {
                LOG.warning(
                        "Erreur openHAB : HTTP "
                                + reponse.statusCode()
                                + " pour " + nomItem
                );
            }
        });
    }
}
