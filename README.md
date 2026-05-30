# TailClip 📱💻

*[English version below](#english-version)*

TailClip je nástroj pro bleskurychlou, obousměrnou synchronizaci schránky a sdílení souborů mezi Linuxovým PC a Android zařízením přes bezpečnou VPN síť (např. Tailscale).

> [!WARNING]
> **Proof of Concept Upozornění**  
> Tento projekt je pouze "proof of concept" (experimentální ukázka). Spoléhá na standardní nešifrované HTTP/WebSocket spojení, které **musí** běžet výhradně nad zabezpečeným tunelem (Tailscale, WireGuard apod.). Autor nenese **žádnou odpovědnost** za případné úniky dat, chyby nebo poškození systému. Použití je čistě na vlastní nebezpečí.

## ✨ Funkce (CZ)
- **Okamžitá synchronizace textu**: Zkopíruješ text na PC a okamžitě se objeví ve schránce na Androidu.
- **Z Androidu do PC (Sdílení)**: Kvůli přísným bezpečnostním omezením v moderním Androidu (12+) se text i soubory odesílají do PC pohodlně přes nativní **Menu sdílení**. Stačí označit text nebo vybrat soubor, dát "Sdílet" a vybrat TailClip.
- **Dlaždice (Quick Settings)**: Praktická dlaždice v horní liště Androidu pro manuální vynucení odeslání schránky do PC.
- **Obousměrné soubory**: Posílej fotky a dokumenty z mobilu do PC přes Sdílení, nebo z PC do mobilu přes přiložený terminálový skript.
- 🚧 **Windows a macOS**: Verze pro Windows a macOS jsou aktuálně ve fázi vývoje (WIP).

## 🛠️ Architektura
- **PC Backend**: Python FastAPI server (port `8765`), který používá WebSockets pro text a HTTP POST pro soubory. Integruje se do Linuxové schránky přes `wl-clipboard` (Wayland).
- **Android Aplikace**: Nativní Kotlin appka (Jetpack Compose, Ktor). Udržuje trvalé spojení s PC přes službu na pozadí (Foreground Service).

## 🚀 Instalace a použití (CZ)

### 1. PC Backend (Linux)
```bash
git clone https://github.com/VojtechVaidis/TailClip.git
cd TailClip
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn backend:app --host 0.0.0.0 --port 8765
```

### 2. Android Klient
```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Nastavení telefonu:**
1. Otevři TailClip.
2. Zadej Tailscale IP adresu svého PC.
3. Klikni na **Connect**.

### 3. Jak synchronizovat
- **PC → Android**: Zkopíruj text na PC (`Ctrl+C`). Sám se objeví na mobilu.
- **Android → PC**: Označ text nebo soubor, dej **Sdílet** a vyber **TailClip**. Soubory se uloží do `~/Downloads/TailClip/FromMobile`.
- **PC → Android (Soubory)**: Na PC spusť `./tailclip-send.sh cesta/k/souboru.jpg`.

---

<h1 id="english-version">English Version</h1>

TailClip is a seamless, bidirectional clipboard and file synchronization tool designed to bridge the gap between Linux PCs and Android devices over a secure VPN (like Tailscale).

> [!WARNING]
> **Proof of Concept Disclaimer**  
> This project is a proof of concept. It relies on standard HTTP/WebSocket connections which are meant to be run over a secure, encrypted tunnel (like Tailscale or WireGuard). The author takes **zero responsibility** for any data leaks, bugs, or system issues resulting from its use. Use at your own risk.

## ✨ Features (EN)
- **Instant Text Sync**: Copy text on your PC and it instantly appears on your Android device.
- **Android to PC (Share Menu)**: Due to modern Android security restrictions on background clipboard reading, sending text or files from Android to PC is natively handled via the **Share Menu**. Just highlight text or select a file, tap "Share", and select TailClip!
- **Quick Settings Tile**: A handy Android tile to manually push your clipboard to your PC.
- **Bidirectional File Sharing**: Send photos, videos, and documents from Android to PC via Share, or from PC to Android via the included CLI script.
- 🚧 **Windows & macOS**: Support for Windows and macOS is currently a Work In Progress (WIP).

## 🛠️ Architecture
- **PC Backend**: A Python FastAPI server that listens on `0.0.0.0:8765`. It uses WebSockets for text sync and HTTP POST endpoints for file transfers. It uses `wl-clipboard` to interface with Linux Wayland clipboards.
- **Android App**: A native Kotlin application using Jetpack Compose and Ktor. It runs a Foreground Service to maintain a persistent WebSocket connection to the PC.

## 🚀 Installation & Usage (EN)

### 1. PC Backend (Linux)
You need Python 3 and a Wayland-compatible clipboard manager (`wl-clipboard`).

```bash
git clone https://github.com/VojtechVaidis/TailClip.git
cd TailClip
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn backend:app --host 0.0.0.0 --port 8765
```

### 2. Android App
```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Setup on Phone:**
1. Open TailClip.
2. Enter your PC's IP address (e.g., your Tailscale IP `100.x.x.x`).
3. Tap **Connect**.

### 3. How to Sync
- **PC → Android (Text)**: Simply copy text (`Ctrl+C`) on your Linux PC. It will automatically sync to your Android clipboard.
- **Android → PC (Text & Files)**: Highlight any text or select a file, tap **Share**, and choose **TailClip**. The content will instantly appear on your PC (files are saved to `~/Downloads/TailClip/FromMobile`).
- **PC → Android (Files)**: Use the included shell script on your PC:
  ```bash
  ./tailclip-send.sh /path/to/image.jpg
  ```
  The file will be pushed to your Android device and saved to the Downloads folder.

---
*Created by Vojtech Vaidis*
