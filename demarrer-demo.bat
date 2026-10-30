@echo off
setlocal
cd /d "%~dp0"
title SmartAgent-IoT - Demarrage de la demo

echo [1/6] Demarrage de Mosquitto et openHAB...
docker compose up -d
if errorlevel 1 (
  echo ERREUR : Docker Compose n'a pas demarre. Verifiez Docker Desktop.
  pause
  exit /b 1
)

echo Attente initiale des services (20 secondes)...
timeout /t 20 /nobreak >nul

echo [2/6] Registre RMI...
start "Registre RMI" /D "%~dp0agent-piece" cmd /k "java -cp target\agent-piece-1.0-SNAPSHOT.jar ca.udes.smartagent.agent.LanceurRegistre"
timeout /t 3 /nobreak >nul

echo [3/6] Agents des trois pieces...
start "Agent Salle A" /D "%~dp0agent-piece" cmd /k mvn compile exec:java -Dexec.mainClass=ca.udes.smartagent.agent.AgentPiece "-Dexec.args=salleA tcp://localhost:1883"
timeout /t 3 /nobreak >nul
start "Agent Salle B" /D "%~dp0agent-piece" cmd /k mvn compile exec:java -Dexec.mainClass=ca.udes.smartagent.agent.AgentPiece "-Dexec.args=salleB tcp://localhost:1883"
timeout /t 3 /nobreak >nul
start "Agent Cuisine" /D "%~dp0agent-piece" cmd /k mvn compile exec:java -Dexec.mainClass=ca.udes.smartagent.agent.AgentPiece "-Dexec.args=cuisine tcp://localhost:1883"
timeout /t 5 /nobreak >nul

echo [4/6] Coordinateur (openHAB REST)...
set "OPENHAB_URL=http://localhost:8080"
start "Coordinateur" /D "%~dp0coordinateur" cmd /k mvn compile exec:java -Dexec.mainClass=ca.udes.smartagent.coordinateur.CoordinateurBatiment "-Dexec.args=localhost 10"
timeout /t 3 /nobreak >nul

echo [5/6] Simulateur MQTT...
start "Simulateur MQTT" /D "%~dp0sensor-simulator" cmd /k "mvn compile exec:java -Dexec.mainClass=ca.udes.smartagent.simulator.SensorSimulator"

echo [6/6] Ouverture du tableau de bord...
start "" "http://localhost:8080"
echo.
echo Demarrage demande. Verifiez les 6 fenetres de logs.
echo Note : MODE_TEST_PANNE peut etre actif dans SensorSimulator.java.
pause
