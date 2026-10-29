## 8. Lancer les agents et le coordinateur

Le projet utilise Java RMI pour permettre au coordinateur de communiquer avec les agents des trois pièces.

Chaque agent surveille les données MQTT de sa pièce et fournit des informations au coordinateur.

**Important :** chaque programme doit être lancé dans un terminal CMD différent et rester en fonctionnement.

### 8.1. Compiler les modules Java

Depuis la racine du projet :

```cmd
mvn -N install
```

Compiler ensuite le module des agents :

```cmd
cd agent-piece
mvn install
```

Revenir à la racine et compiler le coordinateur :

```cmd
cd ..
cd coordinateur
mvn clean package
```

Ces commandes génèrent les fichiers JAR nécessaires.

### 8.2. Démarrer le registre RMI

Ouvrir un nouveau CMD dans le dossier `agent-piece` :

```cmd
java -cp target\agent-piece-1.0-SNAPSHOT.jar ca.udes.smartagent.agent.LanceurRegistre
```

Le registre RMI utilise le port `2199`.

Il permet aux agents de s'enregistrer afin que le coordinateur puisse les retrouver.

Garder ce terminal ouvert.

### 8.3. Démarrer les trois agents

Ouvrir trois nouveaux terminaux CMD, chacun dans le dossier `agent-piece`.

**Terminal 1 — Agent salleA**

```cmd
java -cp target\agent-piece-1.0-SNAPSHOT-all.jar ca.udes.smartagent.agent.AgentPiece salleA tcp://localhost:1883
```

**Terminal 2 — Agent salleB**

```cmd
java -cp target\agent-piece-1.0-SNAPSHOT-all.jar ca.udes.smartagent.agent.AgentPiece salleB tcp://localhost:1883
```

**Terminal 3 — Agent cuisine**

```cmd
java -cp target\agent-piece-1.0-SNAPSHOT-all.jar ca.udes.smartagent.agent.AgentPiece cuisine tcp://localhost:1883
```

Chaque agent se connecte à Mosquitto et s'enregistre dans le registre RMI.

Les terminaux doivent rester ouverts pendant les tests.

### 8.4. Démarrer le coordinateur

Ouvrir un nouveau CMD dans le dossier `coordinateur`.

Définir l'adresse du serveur openHAB :

```cmd
set OPENHAB_URL=http://localhost:8080
```

Démarrer ensuite le coordinateur :

```cmd
java -cp target\coordinateur-1.0-SNAPSHOT-all.jar ca.udes.smartagent.coordinateur.CoordinateurBatiment localhost 10
```

Le paramètre `localhost` désigne l'ordinateur sur lequel fonctionne le registre RMI.

Le paramètre `10` indique un intervalle de vérification de 10 secondes.

Le coordinateur interroge les trois agents et compare les températures des pièces. Lorsqu'un écart dépasse le seuil défini dans le programme, il peut envoyer une commande de thermostat via MQTT.

Il transmet également le texte de sa décision à openHAB grâce à son client REST.

### 8.5. Vérifier les décisions dans openHAB

Ouvrir :

http://localhost:8080

Accéder au tableau de bord **SmartAgent-IoT**.

Repérer la carte :

**Décision intelligente du coordinateur**

Elle affiche la dernière décision reçue dans l'Item `Coordinateur_Decision`.

Exemple :

`cuisine : consigne 21.5 °C (écart 4.9 °C avec salleB)`

Cette information permet de vérifier que la communication entre le coordinateur Java et openHAB fonctionne.