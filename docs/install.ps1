# Thiago OS - installer for Windows / installatieprogramma voor Windows
# Usage / Gebruik (PowerShell):
#   irm https://thiagofernandezguillen-rgb.github.io/thiago-os/install.ps1 | iex
#
# What it does: downloads Google's official adb (platform-tools) and the latest Thiago OS APK into %TEMP%\ThiagoOS,
# connects to your TV over Wi-Fi, installs the app and opens the "Home app" chooser.
# It only disables the stock launcher if you explicitly answer "y". Nothing is ever uninstalled from your TV.

$ErrorActionPreference = 'Continue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ProgressPreference = 'SilentlyContinue'
$Repo = 'thiagofernandezguillen-rgb/thiago-os'
$Pkg = 'be.nova.os'
$StockLaunchers = @('com.google.android.tvlauncher', 'com.google.android.apps.tv.launcherx', 'com.google.android.leanbacklauncher')
$Extras = @('com.google.android.tvrecommendations')

function Say([string]$t, [string]$c = 'White') { Write-Host $t -ForegroundColor $c }
function Ask([string]$q) { (Read-Host $q).Trim() }

Clear-Host
Say ''
Say '   Thiago OS' Cyan
Say '   Ad-free home screen for Android TV  |  Startscherm zonder reclame' DarkGray
Say ''
Say '   1) Install / update   |  Installeren / bijwerken'
Say '   2) Uninstall & restore |  Verwijderen & terugzetten'
$mode = Ask '   Choose / Kies (1/2)'
if ($mode -ne '2') { $mode = '1' }

$dir = Join-Path $env:TEMP 'ThiagoOS'
New-Item -ItemType Directory -Force $dir | Out-Null

# ---------- adb ----------
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
  $adb = Join-Path $dir 'platform-tools\adb.exe'
  if (-not (Test-Path $adb)) {
    Say "`n> Downloading Android platform-tools from Google...  |  Android-tools van Google downloaden..." Cyan
    try {
      Invoke-WebRequest 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile "$dir\pt.zip" -UseBasicParsing
      Expand-Archive "$dir\pt.zip" $dir -Force
    } catch { Say "  Download failed / mislukt: $($_.Exception.Message)" Red; return }
  }
}

# ---------- TV ----------
Say "`nOn your TV / Op je tv:" Yellow
Say '  1. Settings > Device Preferences > About > click "Build" 7 times  (Developer mode)'
Say '     Instellingen > Apparaatvoorkeuren > Info > klik 7x op "Build"'
Say '  2. Settings > Device Preferences > Developer options > USB debugging = ON'
Say '     Instellingen > Apparaatvoorkeuren > Opties voor ontwikkelaars > USB-foutopsporing = AAN'
Say '  3. IP address: Settings > Network & Internet > your network'
Say '     IP-adres: Instellingen > Netwerk en internet > je netwerk'
$ip = Ask "`nTV IP address / IP-adres van je tv (bv. 192.168.1.50)"
if (-not $ip) { Say 'No IP entered / Geen IP ingevuld.' Red; return }
$target = if ($ip -match ':') { $ip } else { "${ip}:5555" }

Say "`n> Connecting to $target...  |  Verbinden..." Cyan
& $adb connect $target | Out-Null
Say '  Look at your TV: choose "Always allow" and OK.  |  Kijk naar je tv: kies "Altijd toestaan" en OK.' Yellow
$ok = $false
for ($i = 0; $i -lt 60; $i++) {
  $st = (& $adb -s $target get-state 2>$null | Out-String).Trim()
  if ($st -eq 'device') { $ok = $true; break }
  Start-Sleep 2
  if ($i % 5 -eq 4) { & $adb connect $target | Out-Null }
}
if (-not $ok) {
  Say "`n  Could not connect. Check the IP, that the PC and TV are on the same network, and that USB debugging is on." Red
  Say '  Verbinden lukt niet. Controleer het IP, of pc en tv op hetzelfde netwerk zitten en of USB-foutopsporing aan staat.' Red
  return
}
Say '  Connected / Verbonden' Green

function Sh([string]$cmd) { (& $adb -s $target shell $cmd 2>$null | Out-String).Trim() }
function HomeNow { (Sh 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME') -split "`n" | Select-Object -Last 1 }

# ---------- verwijderen ----------
if ($mode -eq '2') {
  $disabled = Sh 'pm list packages -d'
  foreach ($p in $StockLaunchers + $Extras) {
    if ($disabled -match [regex]::Escape("package:$p")) { Say "> Re-enabling / terug aanzetten: $p" Cyan; Sh "pm enable $p" | Out-Null }
  }
  Say "> Uninstalling Thiago OS...  |  Thiago OS verwijderen..." Cyan
  & $adb -s $target uninstall $Pkg | Out-Host
  Sh 'input keyevent KEYCODE_HOME' | Out-Null
  Say "`nDone. Your TV is back to its original home screen.  |  Klaar. Je tv heeft weer zijn originele startscherm." Green
  return
}

# ---------- installeren ----------
Say "`n> Downloading the latest Thiago OS...  |  Nieuwste Thiago OS downloaden..." Cyan
try { Invoke-WebRequest "https://github.com/$Repo/releases/latest/download/ThiagoOS.apk" -OutFile "$dir\ThiagoOS.apk" -UseBasicParsing }
catch { Say "  Download failed / mislukt: $($_.Exception.Message)" Red; return }

Say '> Installing on your TV...  |  Installeren op je tv...' Cyan
$r = (& $adb -s $target install -r "$dir\ThiagoOS.apk" 2>&1 | Out-String)
if ($r -notmatch 'Success') { Say "  Install failed / Installeren mislukt:`n$r" Red; return }
Say '  Installed / Geinstalleerd' Green

Say "`n> On your TV the 'Home app' screen opens: select Thiago OS.  |  Kies op je tv Thiago OS als startscherm-app." Yellow
Sh 'am start -a android.settings.HOME_SETTINGS' | Out-Null
Ask 'Press Enter when done / Druk op Enter als je klaar bent' | Out-Null

Sh 'input keyevent KEYCODE_HOME' | Out-Null
Start-Sleep 2
$cur = HomeNow
if ($cur -match [regex]::Escape($Pkg)) {
  Say "`nDone! The Home button now opens Thiago OS.  |  Klaar! De Home-knop opent nu Thiago OS." Green
  return
}

$stock = $StockLaunchers | Where-Object { $cur -match [regex]::Escape($_) } | Select-Object -First 1
Say "`nYour TV still sends the Home button to: $cur" Yellow
Say 'Je tv stuurt de Home-knop nog naar de oude launcher.' Yellow
if (-not $stock) { Say 'Unknown launcher - not touching it. Open Thiago OS from your apps list.  |  Onbekende launcher, ik raak hem niet aan.' Yellow; return }
Say "`nTo fix this, the old launcher ($stock) can be DISABLED (not removed). You can undo it any time with option 2." White
Say "Dit lost je op door de oude launcher UIT te zetten (niet te verwijderen). Terugzetten kan altijd met optie 2." White
$a = Ask 'Disable the old launcher? / Oude launcher uitzetten? (y/j/n)'
if ($a -match '^(y|j|yes|ja)$') {
  foreach ($p in @($stock) + $Extras) { if ((Sh "pm path $p")) { Say "> Disabling / uitzetten: $p" Cyan; Sh "pm disable-user --user 0 $p" | Out-Host } }
  Sh 'input keyevent KEYCODE_HOME' | Out-Null
  Start-Sleep 2
  if ((HomeNow) -match [regex]::Escape($Pkg)) { Say "`nDone! The Home button now opens Thiago OS.  |  Klaar! De Home-knop opent nu Thiago OS." Green }
  else { Say "`nPress Home on your remote and pick Thiago OS if asked.  |  Druk op Home en kies Thiago OS als je het gevraagd wordt." Yellow }
  Say "Undo / terugzetten: run this installer again and choose 2." DarkGray
} else {
  Say 'OK, nothing changed. Thiago OS is in your apps list.  |  Niets veranderd. Thiago OS staat in je apps.' White
}
