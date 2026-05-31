# TailClip 📱💻

*[English version below](#english-version)*

TailClip je nástroj pro bleskurychlou, obousměrnou synchronizaci schránky a sdílení souborů mezi více zařízeními (Linux PC, Android, a další) přes zabezpečenou VPN síť (např. Tailscale).

> [!WARNING]
> **Proof of Concept Upozornění**  
> Tento projekt je pouze "proof of concept" (experimentální ukázka). Spoléhá na standardní nešifrované HTTP/WebSocket spojení, které **musí** běžet výhradně nad zabezpečeným tunelem (Tailscale, WireGuard apod.). Autor nenese **žádnou odpovědnost** za případné úniky dat, chyby nebo poškození systému. Použití je čistě na vlastní nebezpečí.

## ✨ Funkce (CZ)
- **Multi-device architektura**: Centrální relay server, ke kterému se připojuje libovolný počet zařízení.
- **Cílené odesílání**: Vyber si, na jaké zařízení chceš schránku poslat – na jedno konkrétní, na vybraná, nebo na všechna.
- **Okamžitá synchronizace textu**: Zkopíruješ text na PC a okamžitě se objeví ve schránce na zvoleném zařízení.
- **Z Androidu do PC (Sdílení)**: Text i soubory odesílej přes nativní **Menu sdílení** – stačí označit text nebo vybrat soubor, dát "Sdílet" a vybrat TailClip.
- **Složka ToMobile na PC**: Rychlé posílání souborů a obrázků přetažením do složky `~/Downloads/TailClip/ToMobile/` na PC – klient je automaticky odešle a složku vyčistí.
- **Prohlížeč souborů v mobilu**: Nová záložka **Soubory** v Android aplikaci, kde si můžeš procházet všechny stažené soubory, prohlížet si náhledy fotek a otevírat je přímo v systému.
- **Dlaždice (Quick Settings)**: Praktická dlaždice v horní liště Androidu pro manuální odeslání schránky.
- **Interaktivní nastavení PC**: Spuštění klienta bez argumentů otevře přehledné textové menu pro zadání IP/portu a nastavení uloží.
- 🚧 **Windows a macOS**: Verze pro Windows a macOS jsou aktuálně ve fázi vývoje (WIP).

## 🛠️ Architektura
- **Relay Server**: Python FastAPI server, který funguje jako čistý přepínač zpráv – nepoužívá lokální schránku. Spouští se na serveru/VPS a spravuje registr zařízení.
- **PC Klient**: Python daemon (`pc_client.py`), který se připojí k relay serveru, sleduje lokální schránku a synchronizuje ji.
- **Android Aplikace**: Nativní Kotlin appka (Jetpack Compose, Ktor). Zobrazuje seznam připojených zařízení a umožňuje výběr cílových zařízení.

```
┌─────────────┐     WebSocket     ┌──────────────┐     WebSocket     ┌──────────────┐
│  PC Klient  │ ←───────────────→ │ Relay Server │ ←───────────────→ │   Android    │
│ pc_client.py│                   │  backend.py  │                   │     App      │
└─────────────┘                   └──────────────┘                   └──────────────┘
                                        ↕
                                  ┌──────────────┐
                                  │  Další PC /  │
                                  │   zařízení   │
                                  └──────────────┘
```

## 🚀 Instalace a použití (CZ)

### 1. Relay Server (VPS / Server)
```bash
git clone https://github.com/VojtechVaidis/TailClip.git
cd TailClip
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn backend:app --host 0.0.0.0 --port 8765
```

### 2. PC Klient (Linux)
Na každém PC, kde chceš synchronizaci schránky:
```bash
cd TailClip
source .venv/bin/activate
python pc_client.py
```
*Tip: Pokud spustíš klienta bez parametrů, provede tě interaktivním nastavením a uloží konfiguraci do `~/.config/tailclip/config.json`.*

Pokud chceš spouštět přímo s parametry (např. pro spouštění na pozadí):
```bash
python pc_client.py --server <IP_SERVERU> --port 8765 --device-name "Můj PC"
```

Volitelné parametry:
- `--device-name` / `-n` – Název zařízení (výchozí: hostname/uložený)
- `--targets` / `-t` – Cílová zařízení: `all` nebo čárkou oddělené ID (výchozí: `all`)

### 3. Android Klient
```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Nastavení telefonu:**
1. Otevři TailClip.
2. Zadej IP adresu relay serveru.
3. Nastav si název zařízení.
4. Klikni na **Connect**.
5. V sekci **Connected Devices** si vyber, kam chceš posílat.

### 4. Jak synchronizovat
- **PC → Zařízení (Text)**: Zkopíruj text na PC (`Ctrl+C`). Automaticky se pošle na zvolená zařízení.
- **Android → Zařízení (Text & Soubory)**: Označ text nebo soubor, dej **Sdílet** a vyber **TailClip**.
- **PC → Zařízení (Soubory)**: 
  - Přetáhni soubory do složky `~/Downloads/TailClip/ToMobile/`. PC klient je automaticky nahraje na server a pošle na cílová zařízení.
  - Nebo použij skript: `./tailclip-send.sh soubor.jpg --server <IP> --to all`

### 5. REST API
- `GET /health` – Zdraví serveru + počet připojených zařízení
- `GET /devices` – Seznam aktuálně připojených zařízení
- `POST /upload` – Nahrání souboru na server
- `POST /push-file` – Odeslání souboru na cílová zařízení

---

<h1 id="english-version">English Version</h1>

TailClip is a seamless, multi-device clipboard and file synchronization tool designed to connect any number of devices (Linux PCs, Android phones) over a secure VPN (like Tailscale).

> [!WARNING]
> **Proof of Concept Disclaimer**  
> This project is a proof of concept. It relies on standard HTTP/WebSocket connections which are meant to be run over a secure, encrypted tunnel (like Tailscale or WireGuard). The author takes **zero responsibility** for any data leaks, bugs, or system issues resulting from its use. Use at your own risk.

## ✨ Features (EN)
- **Multi-Device Architecture**: A central relay server that any number of devices can connect to.
- **Targeted Delivery**: Choose to send clipboard to a specific device, selected devices, or all connected devices.
- **Instant Text Sync**: Copy text on any device and it instantly appears on your selected target devices.
- **Android to PC (Share Menu)**: Send text or files from Android via the native **Share Menu**.
- **ToMobile Folder on PC**: Send files to mobile devices by dropping them into `~/Downloads/TailClip/ToMobile/` on your PC – the client uploads them and cleans the folder.
- **Mobile Files Browser**: A new **Files** tab in the Android app to view downloaded files with photo previews and open them in default viewers.
- **Quick Settings Tile**: A handy Android tile to manually push your clipboard.
- **Interactive PC Setup**: Running the client without parameters opens a CLI setup wizard and saves settings.
- 🚧 **Windows & macOS**: Support for Windows and macOS is currently a Work In Progress (WIP).

## 🛠️ Architecture
- **Relay Server**: A Python FastAPI server that acts as a pure message relay – no local clipboard access. Runs on a server/VPS and maintains a device registry.
- **PC Client**: A Python daemon (`pc_client.py`) that connects to the relay server, watches the local clipboard, and syncs bidirectionally.
- **Android App**: A native Kotlin application using Jetpack Compose and Ktor. Shows connected devices and allows target device selection.

## 🚀 Installation & Usage (EN)

### 1. Relay Server (VPS / Server)
```bash
git clone https://github.com/VojtechVaidis/TailClip.git
cd TailClip
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn backend:app --host 0.0.0.0 --port 8765
```

### 2. PC Client (Linux)
On each PC that should have clipboard sync:
```bash
cd TailClip
source .venv/bin/activate
python pc_client.py
```
*Tip: Running without parameters starts an interactive configuration menu and saves settings to `~/.config/tailclip/config.json`.*

Or run directly with parameters (useful for background scripts):
```bash
python pc_client.py --server <SERVER_IP> --port 8765 --device-name "My PC"
```

Optional flags:
- `--device-name` / `-n` – Device display name (default: hostname/saved)
- `--targets` / `-t` – Target devices: `all` or comma-separated IDs (default: `all`)

### 3. Android App
```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Setup on Phone:**
1. Open TailClip.
2. Enter the relay server's IP address.
3. Set your device name.
4. Tap **Connect**.
5. In the **Connected Devices** section, select which devices to send to.

### 4. How to Sync
- **PC → Devices (Text)**: Simply copy text (`Ctrl+C`) on your Linux PC. It automatically syncs to selected target devices.
- **Android → Devices (Text & Files)**: Highlight text or select a file, tap **Share**, and choose **TailClip**.
- **PC → Devices (Files)**: 
  - Drop files into `~/Downloads/TailClip/ToMobile/` directory. The PC client will upload them and send to selected targets.
  - Or use the CLI script: `./tailclip-send.sh /path/to/file.jpg --server <IP> --to all`

### 5. WebSocket Protocol (JSON)
All messages use JSON format:

| Direction | Type | Key Fields |
|-----------|------|------------|
| Client → Server | `register` | `device_id`, `device_name`, `device_type` |
| Server → Client | `registered` | `device_id` |
| Server → Client | `device_list` | `devices: [{id, name, type}]` |
| Client → Server | `clipboard` | `to_devices` (list or `"all"`), `content` |
| Server → Client | `clipboard` | `from_device`, `from_name`, `content` |
| Server → Client | `file` | `from_device`, `from_name`, `filename` |

### 6. REST API
- `GET /health` – Server health + device count
- `GET /devices` – List of currently connected devices
- `POST /upload` – Upload a file to the server
- `POST /push-file` – Push a file to target devices

---
*Created by Vojtech Vaidis*
