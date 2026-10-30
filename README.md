# SmartAgent-IoT — Système intelligent de gestion d'un bâtiment

## 1. Présentation du projet

**SmartAgent-IoT** est un système distribué de surveillance et de gestion intelligente d'un bâtiment, développé dans le cadre du cours **IFT605 / IFT713 — Systèmes répartis et multi-agents** à l'Université de Sherbrooke.

Le projet combine l'Internet des objets (IoT), la communication MQTT, les agents distribués Java RMI et la plateforme domotique openHAB.

L'objectif est de superviser plusieurs pièces d'un bâtiment, d'automatiser certaines actions et de coordonner les décisions de régulation thermique.

Le bâtiment simulé comprend trois pièces :

- **Salle A** (`salleA`)
- **Salle B** (`salleB`)
- **Cuisine** (`cuisine`)

Chaque pièce possède trois capteurs simulés (température, luminosité et occupation) ainsi qu'un thermostat commandable.

## 2. Architecture générale

Le système est composé de cinq éléments principaux.

| Composant | Rôle |
|---|---|
| Simulateur de capteurs | Génère les mesures et les publie via MQTT |
| Mosquitto | Assure la communication entre les composants MQTT |
| openHAB | Centralise les mesures, affiche le tableau de bord et exécute les règles |
| Agents de pièces | Supervisent les pièces et communiquent via Java RMI |
| Coordinateur | Analyse les températures et prend des décisions d'équilibrage |

### Fonctionnement

1. Le simulateur génère les mesures des neuf capteurs.
2. Les mesures sont publiées sur des topics MQTT via Mosquitto.
3. openHAB reçoit les mesures et met à jour ses Items.
4. Les agents de pièces reçoivent les informations nécessaires à leur fonctionnement.
5. Le coordinateur communique avec les agents via Java RMI.
6. Lorsque certaines conditions sont remplies, des commandes sont envoyées aux thermostats.
7. Les décisions et les états des composants sont affichés dans openHAB.

### Technologies utilisées

- **Java 21** : développement du simulateur, des agents et du coordinateur.
- **Apache Maven** : compilation et gestion des modules.
- **Docker Compose** : déploiement de Mosquitto et d'openHAB.
- **Eclipse Mosquitto** : broker MQTT.
- **MQTT** : transmission des mesures et commandes.
- **Java RMI** : communication entre agents et coordinateur.
- **openHAB 4.3.10** : supervision, automatisation et visualisation.
- **API REST openHAB** : publication des états et décisions du coordinateur.

## 3. Fonctionnalités

### 3.1 Simulation des capteurs

Chaque pièce comporte :

| Capteur | Information |
|---|---|
| Température | Température ambiante en °C |
| Luminosité | Niveau de luminosité |
| Occupation | Présence ou absence d'occupants |

Le simulateur publie périodiquement les mesures, environ toutes les cinq secondes.

Il peut également recevoir des commandes MQTT destinées aux thermostats.

### 3.2 Supervision avec openHAB

Un tableau de bord **SmartAgent-IoT** permet de consulter :

- Les températures des trois pièces.
- Les niveaux de luminosité.
- Les états d'occupation.
- Les consignes des thermostats.
- Les statuts des trois agents.
- La dernière décision publiée par le coordinateur.

L'interface est accessible à l'adresse :

`http://localhost:8080`

### 3.3 Règles d'automatisation

Des règles DSL openHAB assurent notamment :

**Économie d'énergie :** lorsqu'une pièce devient inoccupée, son thermostat reçoit une consigne de 18 °C.

**Alerte de température élevée :** lorsqu'une température dépasse 30 °C, un avertissement est inscrit dans les journaux openHAB.

Les règles sont conservées dans :

`docker/openhab-rules/batiment.rules`

### 3.4 Agents distribués et coordination

Chaque pièce possède un agent Java chargé de participer à la supervision du bâtiment.

Le coordinateur interroge les agents via Java RMI et compare les températures des pièces voisines.

Lorsqu'un écart thermique dépasse **4 °C**, le coordinateur peut calculer et transmettre une nouvelle consigne de thermostat.

Il publie également ses décisions et les statuts des agents dans openHAB via son API REST.

### 3.5 Gestion des pannes

Le système comprend un mécanisme de détection d'interruption des mesures MQTT.

Lorsqu'aucune nouvelle mesure n'est reçue pendant **30 secondes**, l'Item concerné peut passer à l'état `UNDEF`.

Le coordinateur poursuit son fonctionnement avec les autres pièces et le système peut retrouver les mesures lorsqu'un capteur recommence à publier.

Un mode de simulation de panne est disponible dans le code du simulateur pour les tests et la démonstration.

## 4. Structure du projet

```text
SmartAgent-IoT/
├── agent-piece/
│   ├── pom.xml
│   └── src/
├── coordinateur/
│   ├── pom.xml
│   └── src/
├── sensor-simulator/
│   ├── pom.xml
│   └── src/
├── docker/
│   ├── mosquitto/
│   ├── openhab-backup/
│   └── openhab-rules/
├── docs/
│   └── INSTALLATION_OPENHAB.md
├── docker-compose.yml
├── demarrer-demo.bat
├── pom.xml
└── README.md
```

### Description des modules

**`sensor-simulator/`** : génération et publication des données MQTT des capteurs.

**`agent-piece/`** : implémentation des agents de pièces, registre RMI et composants associés.

**`coordinateur/`** : coordination des agents, logique d'équilibrage et intégration REST avec openHAB.

**`docker/`** : configuration de Mosquitto, sauvegardes de configuration openHAB et règles d'automatisation.

**`docs/`** : documentation complémentaire d'installation.

## 5. Prérequis

Avant de lancer le projet, installer :

- Java JDK 21.
- Apache Maven.
- Docker Desktop avec Docker Compose.
- Git, pour récupérer le dépôt.

Vérifier les installations :

```bash
java -version
mvn -version
docker --version
docker compose version
```

Docker Desktop doit être démarré avant le lancement du système.

## 6. Installation et démarrage

### Étape 1 — Récupérer le dépôt

```bash
git clone https://github.com/HaZineb/SmartAgent-IoT.git
cd SmartAgent-IoT
```

### Étape 2 — Compiler le projet

Depuis la racine du dépôt :

```bash
mvn clean package
```

Cette commande compile les modules Maven et exécute les tests configurés.

### Étape 3 — Démarrer Docker

```bash
docker compose up -d
```

Les services principaux sont :

- Mosquitto : port `1883`.
- openHAB : port `8080`.

### Étape 4 — Configurer openHAB

La configuration openHAB utilisée dans le projet comprend des Things MQTT, des Items, des liens entre Items et canaux, ainsi qu'un tableau de bord personnalisé.

Des fichiers de sauvegarde sont disponibles dans :

`docker/openhab-backup/`

Les règles d'automatisation sont disponibles dans :

`docker/openhab-rules/batiment.rules`

Consulter également :

`docs/INSTALLATION_OPENHAB.md`

**Important :** une nouvelle installation d'openHAB doit être configurée avec les éléments nécessaires avant de pouvoir afficher le tableau de bord complet.

### Étape 5 — Lancer la démonstration sous Windows

Après compilation et configuration des services, exécuter à la racine du projet :

```cmd
demarrer-demo.bat
```

Le script lance successivement :

1. Mosquitto et openHAB via Docker Compose.
2. Le registre Java RMI.
3. Les trois agents de pièces.
4. Le coordinateur.
5. Le simulateur MQTT.
6. L'interface web openHAB.

Plusieurs fenêtres CMD s'ouvrent pour afficher les journaux des composants.

**Remarque :** le script utilise des chemins relatifs au dossier du projet. Il nécessite que Java, Maven et Docker soient disponibles dans l'environnement d'exécution.

## 7. Lancement manuel

Pour démarrer les composants séparément, utiliser plusieurs terminaux.

### Registre RMI

Depuis `agent-piece/` :

```cmd
java -cp target\agent-piece-1.0-SNAPSHOT.jar ca.udes.smartagent.agent.LanceurRegistre
```

### Agents de pièces

Depuis `agent-piece/`, lancer une commande par terminal :

```cmd
mvn exec:java -Dexec.mainClass=ca.udes.smartagent.agent.AgentPiece "-Dexec.args=salleA tcp://localhost:1883"
```

```cmd
mvn exec:java -Dexec.mainClass=ca.udes.smartagent.agent.AgentPiece "-Dexec.args=salleB tcp://localhost:1883"
```

```cmd
mvn exec:java -Dexec.mainClass=ca.udes.smartagent.agent.AgentPiece "-Dexec.args=cuisine tcp://localhost:1883"
```

### Coordinateur

Depuis `coordinateur/` :

```cmd
set OPENHAB_URL=http://localhost:8080
mvn compile exec:java -Dexec.mainClass=ca.udes.smartagent.coordinateur.CoordinateurBatiment "-Dexec.args=localhost 10"
```

### Simulateur MQTT

Depuis `sensor-simulator/` :

```cmd
mvn compile exec:java -Dexec.mainClass=ca.udes.smartagent.simulator.SensorSimulator
```

Ouvrir ensuite :

`http://localhost:8080`

## 8. Scénarios de test

Les scénarios suivants ont été utilisés pour vérifier les principales fonctionnalités du système.

| Scénario | Comportement attendu |
|---|---|
| Publication MQTT | Réception des mesures des neuf capteurs |
| Tableau de bord | Actualisation des mesures dans openHAB |
| Communication RMI | Connexion du coordinateur aux trois agents |
| Écart thermique supérieur à 4 °C | Décision d'ajustement du thermostat |
| Pièce inoccupée | Consigne de 18 °C |
| Température supérieure à 30 °C | Avertissement dans les journaux |
| Interruption d'un capteur | Passage de sa mesure à `UNDEF` après expiration |
| Reprise d'un capteur | Retour des mesures après rétablissement |
| Statut des agents | Publication des états `ONLINE` et `OFFLINE` par le coordinateur |

Certains scénarios ont été vérifiés sur des pièces précises et ne constituent pas nécessairement des tests exhaustifs de toutes les combinaisons possibles.

## 9. Démonstration

Pour présenter le fonctionnement du projet :

1. Démarrer Docker Desktop.
2. Vérifier que les modules Java ont été compilés.
3. Lancer `demarrer-demo.bat`.
4. Ouvrir le tableau de bord SmartAgent-IoT.
5. Observer la réception des mesures MQTT.
6. Vérifier les connexions des agents RMI.
7. Observer une décision du coordinateur lorsqu'un écart thermique dépasse le seuil.
8. Présenter une règle d'automatisation openHAB.
9. Simuler une interruption de capteur et montrer la reprise des mesures.

Le fichier `SensorSimulator.java` contient un paramètre `MODE_TEST_PANNE` permettant de contrôler le scénario de panne simulée.

Pour une démonstration sans interruption volontaire des capteurs, ce paramètre doit être désactivé.

## 10. Documentation

Pour plus d'informations sur l'installation et la configuration openHAB :

`docs/INSTALLATION_OPENHAB.md`

## 11. Contexte académique

Projet réalisé dans le cadre du cours **IFT605 / IFT713 — Systèmes répartis et multi-agents**, à l'**Université de Sherbrooke**.

Le projet illustre l'intégration de plusieurs technologies de systèmes répartis dans une application IoT : communication asynchrone MQTT, invocation distante RMI, coordination multi-agents, supervision et automatisation domotique.
