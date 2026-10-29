package ca.udes.smartagent.coordinateur;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Intégration avec openHAB par son API REST (objectif de la semaine 3) : chaque décision du
 * coordinateur est écrite dans l'Item texte {@value #ITEM_DECISION}, visible au tableau de bord.
 *
 * <p>Requête envoyée : {@code PUT {url}/rest/items/Coordinateur_Decision/state} avec le texte
 * de la décision. L'envoi est asynchrone : si openHAB est arrêté, le cycle d'équilibrage n'est
 * jamais bloqué, on journalise seulement un avertissement.</p>
 *
 * <p>Prérequis côté openHAB (fichier {@code batiment.items}) :
 * {@code String Coordinateur_Decision "Coordinateur [%s]"}.</p>
 */
public class ClientOpenHab implements Consumer<String> {

    /** Nom de l'Item openHAB qui reçoit les décisions. */
    public static final String ITEM_DECISION = "Coordinateur_Decision";

    private static final Logger LOG = Logger.getLogger("OpenHAB");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final URI uriEtat;
    private final String jeton;

    /**
     * @param urlBase URL d'openHAB, par exemple {@code http://localhost:8080}
     * @param jeton   jeton d'API openHAB (facultatif, {@code null} si l'accès anonyme est permis)
     */
    public ClientOpenHab(String urlBase, String jeton) {
        String base = urlBase.endsWith("/") ? urlBase.substring(0, urlBase.length() - 1) : urlBase;
        this.uriEtat = URI.create(base + "/rest/items/" + ITEM_DECISION + "/state");
        this.jeton = (jeton == null || jeton.isBlank()) ? null : jeton;
    }

    /**
     * Envoie le texte d'une décision à openHAB, sans attendre la réponse.
     *
     * @param decision texte affiché dans l'Item {@value #ITEM_DECISION}
     */
    @Override
    public void accept(String decision) {
        HttpRequest.Builder requete = HttpRequest.newBuilder(uriEtat)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString(decision, StandardCharsets.UTF_8));
        if (jeton != null) {
            requete.header("Authorization", "Bearer " + jeton);
        }
        http.sendAsync(requete.build(), HttpResponse.BodyHandlers.discarding())
                .whenComplete((reponse, erreur) -> {
                    if (erreur != null) {
                        LOG.warning("openHAB injoignable (" + uriEtat + ") : " + erreur.getClass().getSimpleName());
                    } else if (reponse.statusCode() >= 300) {
                        LOG.warning("openHAB a répondu " + reponse.statusCode() + " pour " + uriEtat
                                + (reponse.statusCode() == 401 ? " (jeton OPENHAB_TOKEN requis)" : "")
                                + (reponse.statusCode() == 404 ? " (Item " + ITEM_DECISION + " absent)" : ""));
                    }
                });
    }
}
