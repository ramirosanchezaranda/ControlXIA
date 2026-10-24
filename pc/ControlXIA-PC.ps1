<#
ControlXIA para Windows

Deja que el asistente de tu telefono (ControlXIA) encuentre esta PC en tu wifi
y la apague, reinicie, suspenda, bloquee, cambie el volumen, pause la musica o
abra paginas. Para prenderla, el telefono usa Wake-on-LAN (no hace falta este
programa, pero si para saber su direccion fisica).

Cada pedido viene firmado con el codigo que muestra este programa al
instalarlo: nadie mas en la red puede mandarle ordenes.

Instalar (una vez, en PowerShell, como usuario comun):
  powershell -ExecutionPolicy Bypass -File ControlXIA-PC.ps1 -Install
Ver el codigo para vincular:   ... -Code
Desinstalar:                   ... -Uninstall

Puertos (solo en tu red): TCP 48300 (pedidos) y UDP 48301 (para encontrarla).
#>
param(
    [switch]$Install,
    [switch]$Uninstall,
    [switch]$Code,
    [switch]$Run,
    [switch]$SetupAdmin
)

$ErrorActionPreference = 'Stop'
$AppDir = Join-Path $env:LOCALAPPDATA 'ControlXIA'
$ConfigFile = Join-Path $AppDir 'config.json'
$ScriptFile = Join-Path $AppDir 'ControlXIA-PC.ps1'
$LogFile = Join-Path $AppDir 'log.txt'
$TaskName = 'ControlXIA PC'
$TcpPort = 48300
$UdpPort = 48301
$MaxClockSkew = 120

function Write-Log([string]$text) {
    try {
        if ((Test-Path $LogFile) -and ((Get-Item $LogFile).Length -gt 200KB)) { Remove-Item $LogFile -Force }
        Add-Content -Path $LogFile -Value ("{0}  {1}" -f (Get-Date -Format 's'), $text)
    } catch { }
}

function New-PairingCode {
    $chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'.ToCharArray()
    $bytes = New-Object byte[] 10
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $code = -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
    return $code.Substring(0, 5) + '-' + $code.Substring(5, 5)
}

function Get-Config {
    if (Test-Path $ConfigFile) {
        return Get-Content $ConfigFile -Raw | ConvertFrom-Json
    }
    New-Item -ItemType Directory -Force -Path $AppDir | Out-Null
    $config = [pscustomobject]@{ code = (New-PairingCode); name = $env:COMPUTERNAME }
    $config | ConvertTo-Json | Set-Content -Path $ConfigFile -Encoding ASCII
    return $config
}

function Get-CleanCode([string]$code) {
    return ($code.ToUpperInvariant() -replace '[^A-Z0-9]', '')
}

function Get-Macs {
    $adapters = @(Get-NetAdapter -Physical -ErrorAction SilentlyContinue | Where-Object { $_.MacAddress })
    # Cable primero: Wake-on-LAN anda mejor por cable. Despues, las que estan conectadas.
    $sorted = $adapters | Sort-Object `
        @{ Expression = { if ($_.MediaType -eq '802.3') { 0 } else { 1 } } }, `
        @{ Expression = { if ($_.Status -eq 'Up') { 0 } else { 1 } } }
    return @($sorted | ForEach-Object { $_.MacAddress.Replace('-', ':') })
}

function Get-Hmac([string]$key, [string]$text) {
    $hmac = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($key))
    $hash = $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($text))
    return -join ($hash | ForEach-Object { $_.ToString('x2') })
}

# --- Lo que puede hacer ---

$Shell = $null
function Send-MediaKey([int]$code, [int]$times) {
    if ($null -eq $script:Shell) { $script:Shell = New-Object -ComObject WScript.Shell }
    for ($i = 0; $i -lt $times; $i++) { $script:Shell.SendKeys([string][char]$code) }
}

function Get-Times([string]$arg, [int]$default) {
    $n = 0
    if ([int]::TryParse($arg, [ref]$n) -and $n -gt 0 -and $n -le 50) { return $n }
    return $default
}

# Devuelve @{ ok; msg; after } ("after": lo que se hace despues de contestar, como apagar).
function Invoke-PcCommand([string]$cmd, [string]$arg) {
    switch ($cmd) {
        'ping' { return @{ ok = $true; msg = 'ok' } }
        'shutdown' {
            return @{ ok = $true; msg = 'apagando'; after = { Start-Process -FilePath 'shutdown.exe' -ArgumentList '/s', '/t', '5' -WindowStyle Hidden } }
        }
        'restart' {
            return @{ ok = $true; msg = 'reiniciando'; after = { Start-Process -FilePath 'shutdown.exe' -ArgumentList '/r', '/t', '5' -WindowStyle Hidden } }
        }
        'sleep' {
            return @{ ok = $true; msg = 'suspendiendo'; after = {
                Add-Type -AssemblyName System.Windows.Forms
                [System.Windows.Forms.Application]::SetSuspendState('Suspend', $false, $false) | Out-Null
            } }
        }
        'lock' { Start-Process -FilePath 'rundll32.exe' -ArgumentList 'user32.dll,LockWorkStation'; return @{ ok = $true; msg = 'bloqueada' } }
        'volume_up' { Send-MediaKey 175 (Get-Times $arg 5); return @{ ok = $true; msg = 'ok' } }
        'volume_down' { Send-MediaKey 174 (Get-Times $arg 5); return @{ ok = $true; msg = 'ok' } }
        'mute' { Send-MediaKey 173 1; return @{ ok = $true; msg = 'ok' } }
        'play_pause' { Send-MediaKey 179 1; return @{ ok = $true; msg = 'ok' } }
        'next' { Send-MediaKey 176 1; return @{ ok = $true; msg = 'ok' } }
        'previous' { Send-MediaKey 177 1; return @{ ok = $true; msg = 'ok' } }
        'open' {
            if ($arg -match '^https?://') { Start-Process $arg; return @{ ok = $true; msg = 'ok' } }
            return @{ ok = $false; msg = 'solo abro paginas web' }
        }
        default { return @{ ok = $false; msg = 'no conozco ese pedido' } }
    }
}

# --- El servidor ---

function Send-Reply($writer, [bool]$ok, [string]$msg, $config) {
    $reply = @{ ok = $ok; msg = $msg; name = $config.name; macs = @(Get-Macs) } | ConvertTo-Json -Compress
    $writer.WriteLine($reply)
}

function Invoke-Client($client, [string]$key, $seen, $config) {
    $after = $null
    try {
        $client.ReceiveTimeout = 3000
        $client.SendTimeout = 3000
        $stream = $client.GetStream()
        $reader = New-Object System.IO.StreamReader($stream, [Text.Encoding]::UTF8)
        $writer = New-Object System.IO.StreamWriter($stream, (New-Object System.Text.UTF8Encoding($false)))
        $writer.AutoFlush = $true
        $line = $reader.ReadLine()
        if (-not $line) { return }
        $req = $line | ConvertFrom-Json
        $cmd = [string]$req.cmd
        $arg = [string]$req.arg
        $ts = [long]$req.ts
        $nonce = [string]$req.nonce
        $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
        $expected = Get-Hmac $key ("{0}|{1}|{2}|{3}" -f $cmd, $arg, $ts, $nonce)
        if ($expected -ne [string]$req.sig) {
            Write-Log "pedido con codigo incorrecto desde $($client.Client.RemoteEndPoint)"
            Send-Reply $writer $false 'codigo' $config
        } elseif ([Math]::Abs($now - $ts) -gt $MaxClockSkew) {
            Send-Reply $writer $false 'hora' $config
        } elseif ($seen.Contains($nonce)) {
            Send-Reply $writer $false 'repetido' $config
        } else {
            $seen.Enqueue($nonce)
            while ($seen.Count -gt 200) { [void]$seen.Dequeue() }
            Write-Log "pedido: $cmd $arg"
            $result = Invoke-PcCommand $cmd $arg
            $after = $result.after
            Send-Reply $writer ([bool]$result.ok) ([string]$result.msg) $config
        }
    } catch {
        Write-Log "error con un pedido: $_"
    } finally {
        $client.Close()
    }
    if ($after) { & $after }
}

function Start-Agent {
    $config = Get-Config
    $key = Get-CleanCode $config.code
    $tcp = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Any, $TcpPort)
    try {
        $tcp.Start()
    } catch {
        Write-Log "el puerto $TcpPort ya esta en uso (ya hay otro ControlXIA andando?)"
        return
    }
    $udp = New-Object System.Net.Sockets.UdpClient($UdpPort)
    $udp.EnableBroadcast = $true
    $seen = New-Object 'System.Collections.Generic.Queue[string]'
    Write-Log "andando: TCP $TcpPort, UDP $UdpPort"
    while ($true) {
        try {
            if ($udp.Available -gt 0) {
                $remote = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
                $data = $udp.Receive([ref]$remote)
                if ([Text.Encoding]::UTF8.GetString($data) -eq 'CONTROLXIA?') {
                    $announce = @{ app = 'controlxia-pc'; v = 1; name = $config.name; port = $TcpPort; macs = @(Get-Macs) } | ConvertTo-Json -Compress
                    $bytes = [Text.Encoding]::UTF8.GetBytes($announce)
                    [void]$udp.Send($bytes, $bytes.Length, $remote)
                }
            }
            if ($tcp.Pending()) {
                Invoke-Client $tcp.AcceptTcpClient() $key $seen $config
            }
            Start-Sleep -Milliseconds 50
        } catch {
            Write-Log "error: $_"
            Start-Sleep -Seconds 1
        }
    }
}

# --- Instalar ---

function Show-Code($config) {
    $message = "Codigo para vincular esta PC en ControlXIA:`n`n    $($config.code)`n`nEn el telefono: Ajustes > Casa > Buscar compus > Vincular."
    Write-Host ''
    Write-Host $message -ForegroundColor Cyan
    try {
        Add-Type -AssemblyName System.Windows.Forms
        [void][System.Windows.Forms.MessageBox]::Show($message, 'ControlXIA')
    } catch { }
}

# Lo que necesita permiso de administrador: el firewall y dejar lista la placa de red para Wake-on-LAN.
function Invoke-AdminSetup {
    foreach ($rule in @(@('ControlXIA PC (pedidos)', 'TCP', $TcpPort), @('ControlXIA PC (buscar)', 'UDP', $UdpPort))) {
        Remove-NetFirewallRule -DisplayName $rule[0] -ErrorAction SilentlyContinue
        New-NetFirewallRule -DisplayName $rule[0] -Direction Inbound -Protocol $rule[1] -LocalPort $rule[2] `
            -Profile Private, Domain -Action Allow | Out-Null
    }
    foreach ($adapter in @(Get-NetAdapter -Physical -ErrorAction SilentlyContinue)) {
        try { Set-NetAdapterPowerManagement -Name $adapter.Name -WakeOnMagicPacket Enabled -ErrorAction Stop } catch { }
        # El nombre de la opcion y su valor cambian segun el idioma de Windows y la marca de la placa.
        foreach ($property in @('Wake on Magic Packet', 'Wake on magic packet', 'Reactivar con Magic Packet', 'Activacion con Magic Packet')) {
            foreach ($value in @('Enabled', 'Habilitado', 'Activado')) {
                try { Set-NetAdapterAdvancedProperty -Name $adapter.Name -DisplayName $property -DisplayValue $value -ErrorAction Stop } catch { }
            }
        }
    }
    # El "inicio rapido" de Windows no deja prenderla por la red estando apagada.
    try {
        Set-ItemProperty -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Power' -Name 'HiberbootEnabled' -Value 0 -Type DWord
    } catch { }
    Write-Host 'Listo: firewall y Wake-on-LAN configurados.' -ForegroundColor Green
}

function Install-Agent {
    if (-not $PSCommandPath) { throw 'Guarda este archivo y correlo con -File (no pegado).' }
    New-Item -ItemType Directory -Force -Path $AppDir | Out-Null
    if ($PSCommandPath -ne $ScriptFile) { Copy-Item -Path $PSCommandPath -Destination $ScriptFile -Force }
    $config = Get-Config

    # Arranca solo al entrar a Windows (en tu sesion: asi puede bloquear y cambiar el volumen).
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' `
        -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$ScriptFile`" -Run"
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings `
        -Description 'ControlXIA: que el asistente del telefono maneje esta PC' -Force | Out-Null
    Start-ScheduledTask -TaskName $TaskName

    # Opcional: el permiso de administrador para el firewall y Wake-on-LAN.
    try {
        Start-Process -FilePath 'powershell.exe' -Verb RunAs -Wait `
            -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$ScriptFile`" -SetupAdmin"
    } catch {
        Write-Host 'Sin permiso de administrador: si Windows pregunta, permiti el acceso en redes privadas.' -ForegroundColor Yellow
    }
    Write-Host "Instalado. Arranca solo cada vez que entras a Windows." -ForegroundColor Green
    Show-Code $config
}

function Uninstall-Agent {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like '*ControlXIA-PC.ps1*-Run*' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Remove-Item -Path $AppDir -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host 'ControlXIA desinstalado. (Las reglas del firewall, si las hay, se sacan desde el Firewall de Windows.)'
}

if ($SetupAdmin) { Invoke-AdminSetup }
elseif ($Install) { Install-Agent }
elseif ($Uninstall) { Uninstall-Agent }
elseif ($Code) { Show-Code (Get-Config) }
elseif ($Run) { Start-Agent }
else {
    Write-Host 'Uso: ControlXIA-PC.ps1 -Install | -Code | -Uninstall'
}
