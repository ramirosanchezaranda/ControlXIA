# ControlXIA para Windows

Un programita para que el asistente del teléfono (ControlXIA) maneje tus compus con Windows desde el wifi de casa: apagarlas, reiniciarlas, suspenderlas, bloquearlas, subir o bajar el volumen, pausar la música y abrir páginas. Para **prenderlas** el teléfono usa Wake-on-LAN; el programita le dice su dirección física (MAC).

## Instalar

En cada compu, abrí **PowerShell** (tecla Windows, escribí «PowerShell») y pegá:

```powershell
iwr -useb https://raw.githubusercontent.com/ramirosanchezaranda/ControlXIA/claude/voice-control-app-planning-f1uhhu/pc/ControlXIA-PC.ps1 -OutFile $env:TEMP\ControlXIA-PC.ps1; powershell -ExecutionPolicy Bypass -File $env:TEMP\ControlXIA-PC.ps1 -Install
```

(El mismo comando está en el teléfono, en Ajustes → Casa, con un botón para copiarlo o mandártelo.)

1. Se instala para tu usuario (no hace falta ser administrador) y arranca solo cada vez que entrás a Windows.
2. Pide permiso de administrador **una vez, opcional**: abre el firewall para tu red privada y deja lista la placa de red para Wake-on-LAN (y apaga el «inicio rápido», que no deja prenderla por la red). Si no lo das, Windows te pregunta la primera vez: permití el acceso en **redes privadas**.
3. Te muestra un **código** (por ejemplo `K7P2Q-9XQF3`). En el teléfono: Ajustes → Casa → Buscar compus → Vincular, y lo escribís.

Ver el código de nuevo: `powershell -ExecutionPolicy Bypass -File $env:LOCALAPPDATA\ControlXIA\ControlXIA-PC.ps1 -Code`
Desinstalar: el mismo comando con `-Uninstall`.

## Prenderla desde el teléfono (Wake-on-LAN)

- Funciona casi siempre **por cable de red**; por wifi, muy pocas compus lo permiten estando apagadas.
- En la BIOS/UEFI (al prender, F2, Supr o F10 según la marca) activá **Wake on LAN** / «Power On by PCI-E» / «Resume by LAN».
- El permiso de administrador del instalador activa «Wake on Magic Packet» en la placa de red y apaga el inicio rápido.
- Desde suspendida suele andar también por wifi.

## Seguridad

- Solo escucha en tu red (TCP 48300 para pedidos, UDP 48301 para que el teléfono la encuentre); el firewall lo permite solo en redes privadas.
- Cada pedido viene **firmado con el código** (HMAC-SHA256 con la hora y un número al azar): nadie más en la red puede mandarle órdenes ni repetir una vieja.
- No abre nada que no sea una página web (`http`/`https`) y no ejecuta comandos que lleguen por la red.
- Que tu wifi esté como **red privada** en Windows (Configuración → Red → Propiedades).

El registro queda en `%LOCALAPPDATA%\ControlXIA\log.txt`.
