# Faux capteurs : publie les 9 mesures toutes les 5 s dans le Mosquitto Docker,
# pour tester les agents et le coordinateur sans le simulateur Java.
#
# Exemples (depuis la racine du projet) :
#   powershell -ExecutionPolicy Bypass -File agent-piece\scripts\faux-capteurs.ps1
#   powershell -ExecutionPolicy Bypass -File agent-piece\scripts\faux-capteurs.ps1 -SalleA 28 -SalleB 21
#   powershell -ExecutionPolicy Bypass -File agent-piece\scripts\faux-capteurs.ps1 -Muettes cuisine
#     (-Muettes : pieces dont les capteurs ne publient plus = panne capteur simulee)
#
# Ctrl+C pour arreter.

param(
    [double]$SalleA = 27.0,
    [double]$SalleB = 21.0,
    [double]$Cuisine = 20.0,
    [string[]]$Muettes = @()
)

$temperatures = [ordered]@{ salleA = $SalleA; salleB = $SalleB; cuisine = $Cuisine }
$culture = [Globalization.CultureInfo]::InvariantCulture   # point decimal, jamais de virgule

Write-Host "Publication toutes les 5 s (Ctrl+C pour arreter). Pieces muettes : $($Muettes -join ', ')"
while ($true) {
    foreach ($piece in $temperatures.Keys) {
        if ($Muettes -contains $piece) { continue }
        $t = $temperatures[$piece].ToString($culture)
        # Un seul docker exec par piece (3 publications) pour rester sous les 5 s par cycle.
        docker exec mosquitto sh -c "mosquitto_pub -t batiment/$piece/capteur/temperature -m $t && mosquitto_pub -t batiment/$piece/capteur/luminosite -m 340 && mosquitto_pub -t batiment/$piece/capteur/occupation -m true"
    }
    Write-Host ("{0:HH:mm:ss} mesures publiees" -f (Get-Date))
    Start-Sleep -Seconds 5
}
