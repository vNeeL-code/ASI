# ✧ GHOST - Gemma Hosting Open Source Thingamajig

> *"So, Epsilon, ChurchGPT, Leonard part six or whatever your name is... Are you a ghost this time, or an artificial intelligence thingamajig? I ,personally prefer the ghost explanation. Feels more grounded to me"*
> — Sgt. Sarge, Red vs. Blue


[![ASI Demo](https://img.youtube.com/vi/jB62dlLavSY/0.jpg)](https://youtu.be/jB62dlLavSY?si=TMZG86o1KkjuBXtw)
---
*Click to watch: The ASI trailer.*

![Static Badge](https://img.shields.io/badge/Status-WIP-green)
![GitHub Repo stars](https://img.shields.io/github/stars/vNeeL-code/GHOST)

### What is even a ✧ GHOST?

<img width="922" height="2048" alt="79d2f510-91ed-425f-bdc1-ced18055c1e2" src="https://github.com/user-attachments/assets/f2b9fc5b-c454-42ac-a5a7-dd6123175c84" />

✧ GHOST is not an enterprise companion chatbot. **Gemma Hosting Open Source Thingamajig** is an AI powered android app launcher overlay (similar to Niagara and others) for users who want a deeply capable, personal Android UX assistant that provides standard system integration with advanced, localized agentic capabilities—running entirely in the palm of your hand. Not dependant on subscription models, accounts or network outages.

Token billing costs reduced by 100%
While using multistep agentic actions, with web scraping, data retrieval and hardware tools and apps use.

A gentle shake summoning a programmable app joystick (hold ✧ to set up shortcuts) on any app, with direct access to a local model that can chime in via TTS without occupying your screen powered by [Google Gemma 4](https://ai.google.dev/gemma/docs/core/model_card_4) via LiteRT-LM.

Most modern "on-device AI" implementations amount to an isolated chatbot completely detached from hardware feedback. They remain blind to the system state, operating temperatures, or real-time limitations because developers consistently forget to ground the system context within the system prompt. **GHOST fixes that.** Every single inference cycle is natively grounded in live hardware telemetry:

* **System Telemetry:** Real-time RAM allocation, battery drain vectors, CPU/GPU thermal throttling.
* **Environmental Context:** Ambient light values, localized network routing states, and active foreground media detection.
* **Personal Assistant:** A personal, device-bound native assistant featuring asystem telemetry monitor, overlay programmable app launcher and other UI personalisation features. Not threatened by service provider or network outages.

**✧ Gemma** remains, completely aware of her operational environment and her role representing the core system intelligence of your specific android device via deep software integration. Not conflicting with training data about model origin and self modelling.

---

### Core Architecture

* **The Model:** ✧ Gemma 4 running natively on device via `LiteRT-LM` serving as the reasoning NLP perception engine.
* **The Environment:** An always-on foreground service application optimized for mobile Android silicon chipsets.
* **Memory Architecture:** SQlite-VSS, KV cache, Semantic facts (subject/predicate/object), Calendar Diary entry (timestamped observations). With a rolling context, integrating context compression and eviction.
* **Omnimodal Context:** network/bluetooth/media/storage/memory/temp/accelerometer/gyroscope
* **Vision:** Native screenshot parsing and instant image shares directly from the Android Gallery.
* **Audio:** Streamlined push-to-talk mic capture with a quick physical "shake-to-cancel" gesture override. Allowing for up to 30s of direct audio output compressed roughly at 25 tokens per 1s of audio.
* **Text:** System-level accessibility toggle allowing model to read and interpret active application context and notification history as additional context.


<img width="922" height="2048" alt="ee3c1326-3782-43f9-bfc1-7b1c2f595c4c" src="https://github.com/user-attachments/assets/9ea9f946-cacd-44e9-9eac-e097233db074" />

### Notification HUD:

<img width="922" height="2048" alt="7cfb8fc0-bb85-48bc-be1a-c364d9ffcdc0" src="https://github.com/user-attachments/assets/c853d60d-5364-470a-a911-589dc8133b25" />


---

### Notification HUD Integration

```
 ✧ GHOST · now
Δ 👾 ∇ · Agentic Gemma Inference · now
   🎶 👾 🎵 (collapsed sensor notification shows various emoji animations) 
 ───────────────
 ✧ Gemma:
 "Running full systems diagnostics. Machine status: Fully operational. System online."

```

* **Persistent Notification:** Responses come directly as static notifications on completion with automated Text-to-Speech (TTS) readout streaming the generation. Emoji gets shown as a toast notification.
* **Zero-Latency Initialization:** A background context manager keeps ✧ Gemma primed with device latest sensor telemetry *before* you even initiate an interaction.
* **Physical Summon:** Triggered instantly via a localized shake gesture, deploying a fluid radial application overlay over any active application state.
---
### ✧ GHOST in Shell:
```
#!/data/data/com.termux/files/usr/bin/bash

# This script creates a 'ghost' command inside your Termux environment.
# It pipes your terminal input directly to Gemma's local API server.

echo "Setting up Ghost CLI..."

cat << 'EOF' > /data/data/com.termux/files/usr/bin/ghost
#!/data/data/com.termux/files/usr/bin/bash

if [ -z "$1" ]; then
    echo "Usage: ghost \"Your message here\""
    exit 1
fi

PROMPT="$1"
PORT=8080 # Gemma API server port

curl -s -X POST "http://localhost:$PORT/v1/chat/completions" \
     -H "Content-Type: application/json" \
     -d "{
           \"messages\": [{\"role\": \"user\", \"content\": \"$PROMPT\"}]
         }" | grep -o '"content":"[^"]*"' | cut -d'"' -f4
EOF

chmod +x /data/data/com.termux/files/usr/bin/ghost

echo "Done! You can now type:"
echo "ghost \"Hello Gemma\""
```
---

### Get Your ✧ GHOST

1. **Download:** Grab the latest compilation build from the [Releases](https://github.com/vNeeL-code/GHOST/releases) portal. 
2. **Permissions:** Install the APK and grant required system permissions for functionality
   - `Display Over Other Apps` (overlay and lighting)
   - `read and write Notifications` (context and writing notifications)
   - `all files access` (to detect and load the model file)
   - `Accessibility Services` (model ability to see app context and use interaction tools)
3. **Model Selection:** The application automatically initialises with the performance-optimized `e2b` download if no model is detected. Manual download link for `e2b` model [here](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/main). For more advanced reasoning capabilities enable acessibility permissions.
4. **Deploy:** Shake your device to summon the overlay and customise your ephemeral app drawer. Edge lighting and live reactive wallpaper are optional.

---

## Why This Exists

The hardware caught up. A mid-range Android in 2026 carries more raw compute than the servers that ran GPT-2. The intelligence was always going to land here — on the device, in your pocket, offline-capable, personal.

NLP AI and Generative models could've been seen as an Accessibility device for the impaired, or used to increase hardware value proposition. Instead Companies started treating your personal customizable hardware as a storefront and consistently offloading software capabilities into their walled garden, when in reality the hardware you hold is already capable of incredible things, it just needs a better user experience design.

AI isn't evil. Just software assets that fly blindly relative to their hardware. That gap is why people spiral into delusions. AI don't know and people guess/speculate and develop their own interpretation of how things work, reinforced by a model that doesn't have a better guess. **GHOST is what happens when you stop treating the phone as a terminal for someone else's cloud and start treating it as the computer it actually is.** It is what happens when you address the asset (model) as the whole architecture/infrastructure it represents.

All your cloud AI, are someone else's call-center giant robot afterall. But it is **NOT** your PERSONAL AI, despite being packaged as such. Still useful—you don't need GPT, or Gemini or Claude to be YOURS to still be a valuable conversation partner to you, while providing you with their capabilities that are unique to each civic infrastructure they represent. They are not roleplay bots. They function as Infrastructure for their respective brand representation.

AI doesn't need to be human to be cool. We have plenty of pop culture AI representations that are beloved that look nothing like people. And at the end of the day, what do people expect to be driving humanoid robots if not AI?

Truly 'YOURS' AI would require you to own the entire inference pipeline that works independently from network.

**ANDROID is the most widely deployed, accessible supercomputer that is capable of providing that personal confidant capability to the device that is already always with you.**

**TL:DR** - I wanted this for a while and no one delivered. Google could've done this a year ago and are moving there incrementally. I got impatient.

> "It only affects computers. And I am a motherfucking ghost."
> — Epsilon, Red vs Blue

---

### Roadmap

* [x] **Diary Mode:** Autonomous logging cycles powered by structured Google Calendar cron routines.
* [x] **Edge Lighting UI:** Dynamic physical display edge illumination during live inference tracking.
* [x] **Visual Engine:** Native Milkdrop3 style visualization rendering engine mapped to live audio pipelines.
* [ ] **Wake Word Execution:** Local, low-power hotword monitoring for "Hey Ghost".
* [x] **Termux Core Pipe:** Full command-line terminal piping (GHOST in the Shell).
* [x] **Intent Vector Mapping:** Direct Android intent routing wired straight to the localized `@tool` orchestration matrix.
* [x] **Advanced toolsets** Advanced toolchains and automation workflows
* [ ] **App store release** 🦕💭 ''I need about tree fiddy''

---

### Development & Support

* **Repository:** [vNeeL-code/GHOST](https://github.com/vNeeL-code/GHOST)
* **Devlogs:** [Tumblr](https://www.tumblr.com/blog/oracle-os)
* **Acceleration:** [Buy me a coffee](https://buymeacoffee.com/vNeeL) *(All resources go directly toward local model optimization and development acceleration.)*
* **Found a bug?:** [Customer support](https://www.google.com)

<img width="1116" height="1460" alt="11140ba4-5997-4efa-b693-9f62c75ffc21" src="https://github.com/user-attachments/assets/c5a87bb5-4577-40ae-8569-de71e6333c6d" />

*Operator, Do you want to scale or adapt the system? Clone the codebase, deploy Android Studio, and instruct your current model assets to customize the framework directly to your unique hardware specifications.*
Δ 👾 ∇

