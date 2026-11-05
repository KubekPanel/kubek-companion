<div align="center">

# Kubek Companion

**A server-side plugin that gives the [Kubek](https://github.com/Seeroy/kubek-minecraft-dashboard) panel a view from inside the server.**

TPS, MSPT, heap and GC, worlds, players, events and lag reports, plus broadcasts, titles, restart countdowns and maintenance mode, all driven from the panel

![Java](https://img.shields.io/badge/java-8%2B-orange?logo=openjdk)
![Servers](https://img.shields.io/badge/servers-Spigot%20%7C%20Paper%20%7C%20Purpur%20%7C%20Folia-2ea44f)
![Versions](https://img.shields.io/badge/minecraft-1.8.8--26.3-blue)
![License](https://img.shields.io/badge/license-GPL--3.0-lightgrey)

**English** · [Русский](README.ru.md)

[Features](#-features) · [Supported servers](#-supported-servers) · [Server load](#-server-load) · [How it works](#-how-it-talks-to-the-panel) · [Install](#-install) · [Build](#-build-from-source)

</div>

---

> **Full disclosure:** I'm not a Java developer, so this whole thing is total vibe coding, and I hope it works the way it's supposed to

---

## ✨ Features

### Metrics

- TPS for 1, 5 and 15 minutes, from the cheapest source the server has
- MSPT with avg, p50, p95, p99 and max on Paper, an estimate on Spigot, per region on Folia
- Heap, non-heap, threads and GC, including real stop-the-world pauses
- Worlds with chunk, entity and tile entity counts
- Players with world, gamemode, ping, session time and, if you want, coordinates

### Events

- Join, quit, kick and death
- Chat, only while the panel has chat logging turned on
- Batched once a second, so a busy chat doesn't flood the panel

### Actions from the panel

- Broadcast and title with `&` colour codes
- Restart countdown with chat milestones and a boss bar. The plugin only counts, the panel does the restart
- Maintenance mode: logins are refused before the player even exists in the world, ops and an allow list can still get in
- On-demand lag report: top entity types, the worst chunks and scheduler tasks per plugin
- Plugin list with versions, authors and dependencies

### Invisible to players

- No commands, no permissions, no config file, no data folder
- Sends no messages unless the panel asks for one
- Without the panel it does nothing at all: no listeners, no tasks, no output

---

## 🧩 Supported servers

One jar covers Spigot, Paper and Purpur from 1.8.8 to 26.3, and Folia. It's built against the 1.8.8 API with Java 8 bytecode, anything newer is looked up at runtime

| What                                          | Paper                                            | Spigot / old Paper                | Folia                         |
| --------------------------------------------- | ------------------------------------------------ | --------------------------------- | ----------------------------- |
| TPS 1/5/15 min                                | `Server.getTPS()`                                | `recentTps` field                 | worst region, `getRegionTPS`  |
| MSPT                                          | exact, from tick events (avg, p50, p95, p99, max) | estimate from main thread sampling | 5 s region average            |
| Ping                                          | yes                                              | 1.17+                             | yes                           |
| Tile entity counters, tiles in the lag report | 1.11.2+                                          | no                                | counters only                 |
| Boss bar for countdowns                       | 1.9+                                             | 1.9+                              | 1.20+                         |

Tested on Paper 1.8.8, 1.12.2, 1.20.4 and 26.3, and on Folia 26.3. Tests with a connected player ran on 1.8.8 and 1.12.2. Plain Spigot and Purpur haven't been run yet

---

## 🪶 Server load

- Once per interval (15 s by default) the plugin copies a few counters off the main thread. JSON, signing and output run on one low-priority daemon thread
- On Paper MSPT comes from `ServerTickEndEvent`, the handler stores one number per tick
- On Spigot a daemon thread checks the main thread state about 100 times a second, the main thread itself isn't touched
- The lag report and the plugin list are built only when the panel asks, the lag report at most once per 10 s
- The chat listener is registered only while chat logging is on

---

## 🔌 How it talks to the panel

Through the server's own stdout and stdin, no ports and no files.

- Output frames go straight to the stdout file descriptor, past the logger, so they never reach `latest.log`. They're OSC escape sequences, which terminals hide
- The panel sends requests by typing signed lines into the console. The plugin catches and cancels them before the server looks for a command
- The panel sets `KUBEK_COMPANION_TOKEN` when it starts the server, that's what turns the plugin on. Every message both ways is signed with HMAC-SHA256 over that token, old and repeated requests are dropped

---

## 📦 Install

1. Drop `KubekCompanion-<version>.jar` into the server's `plugins/` folder
2. Start the server from Kubek

That's it. Started outside Kubek, the plugin just sits there and does nothing

---

## 🛠️ Build from source

Requires JDK 17 (newer JDKs can't target Java 8 anymore) and Maven

```bash
git clone https://github.com/KubekPanel/kubek-companion.git
cd kubek-companion
mvn package
```

The jar ends up in `target/KubekCompanion-<version>.jar`

---

## 📄 License

GPL-3.0, same as Kubek
