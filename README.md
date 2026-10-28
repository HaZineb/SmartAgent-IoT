# SmartAgent-IoT — base de collaboration

Ce dépôt de départ contient le **contrat RMI commun** et les conventions MQTT pour l'équipe.
Il ne contient pas encore l'implémentation des agents, du coordinateur ou du simulateur.

## Structure cible
- `sensor-simulator/` — simulation Java des capteurs
- `agent-piece/` — agents Java et interface RMI commune
- `coordinateur/` — coordinateur Java RMI
- `openhab/` — Things, Items, DSL Rules et tableau de bord
- `docker/` — configuration Docker
- `docs/CONTRATS.md` — contrats partagés

## Première étape
Lire ensemble `docs/CONTRATS.md`, valider les noms et le schéma JSON, puis figer les signatures de `IAgentPiece` avant le développement parallèle.
