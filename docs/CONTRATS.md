# Contrats communs — SmartAgent-IoT

## Java RMI
- Java 21 ; registre RMI sur port **2199**.
- Interface commune : `ca.udes.smartagent.rmi.IAgentPiece`.
- Noms proposés dans le registre : `agent-salleA`, `agent-salleB`, `agent-cuisine`.
- `getTemperature()` : température en degrés Celsius, `double`.
- `getLuminosite()` : luminosité entière, unité et plage à confirmer en équipe.
- `isOccupee()` : booléen.
- `setConsigneTemperature(double)` : transmet la consigne du thermostat sur MQTT.
- `getEtatJson()` : représentation JSON de l'état ; **schéma à fixer ensemble avant l'intégration**.

**Point à confirmer avec l'enseignante :** l'énoncé emploie `getEtatJson()` dans les exigences et `getEtat()` dans un squelette. Ne conserver qu'un nom après clarification.

## MQTT
- Broker : Mosquitto, port 1883.
- Mesures : `batiment/{piece}/capteur/{type}` ; pièces `salleA`, `salleB`, `cuisine` ; types `temperature`, `luminosite`, `occupation`.
- Commande thermostat : `batiment/{piece}/actuateur/thermostat`.
- Valeurs texte UTF-8 : température `22.5`, luminosité `450`, occupation `true` / `false`, consigne `21.0`.
- Intervalle de publication des mesures : 5 secondes.
- Timeout de capteur : 30 secondes sans message.
- Adresse du broker configurable : sur l'hôte `localhost:1883` si le port est publié ; entre conteneurs Docker `mosquitto:1883` si le service s'appelle `mosquitto`.

## Intégration
- Les deux personnes utilisent **la même interface Java compilée** et la même version du projet Maven.
- Les agents doivent publier les consignes MQTT ; le coordinateur doit appeler les agents via RMI.
- Prévoir les exceptions RMI, la perte de messages MQTT, et l'affichage `UNDEF` dans openHAB.
