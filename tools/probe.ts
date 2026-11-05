#!/usr/bin/env bun
// Pre-release check: runs a real server with the built jar and goes through every message and request
// bun probe.ts --java /path/to/java --jar paper.jar --dir ../run/paper --plugin ../target/KubekCompanion-1.0.0.jar

import { existsSync } from "node:fs";
import { copyFile, mkdir, readFile, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { parseArgs } from "node:util";
import type { Subprocess } from "bun";
import { Bot, type BotProtocol } from "./mcbot.ts";
import { encodeRequest, FrameDecoder, type Message } from "./protocol.ts";

const { values: opts } = parseArgs({
  options: {
    java: { type: "string" },
    jar: { type: "string" },
    dir: { type: "string" },
    plugin: { type: "string" },
    port: { type: "string", default: "25599" },
    xmx: { type: "string", default: "2G" },
    "jvm-arg": { type: "string", multiple: true, default: [] },
    token: { type: "string", default: "kubek-test-token-0123456789" },
    "startup-timeout": { type: "string", default: "300" },
    dump: { type: "string" },
    "bot-protocol": { type: "string" },
    verbose: { type: "boolean", default: false },
  },
});

const java = required("java");
const jar = required("jar");
const dir = required("dir");
const plugin = required("plugin");
const port = Number(opts.port);
const token = opts.token;
const startupMs = Number(opts["startup-timeout"]) * 1000;
const botProtocol = opts["bot-protocol"] ? (Number(opts["bot-protocol"]) as BotProtocol) : null;
if (botProtocol !== null && botProtocol !== 47 && botProtocol !== 340) {
  fail("--bot-protocol is 47 (1.8) or 340 (1.12.2)");
}

function required(name: "java" | "jar" | "dir" | "plugin"): string {
  const value = opts[name];
  if (!value) fail(`--${name} is required`);
  return value;
}

function fail(message: string): never {
  console.error(message);
  process.exit(2);
}

type Predicate = (message: Message) => boolean;

class Probe {
  readonly messages: Message[] = [];
  readonly log: string[] = [];
  readonly decoder = new FrameDecoder(
    token,
    (message) => {
      this.messages.push(message);
      for (const waiter of this.waiters) waiter();
    },
    (line) => this.addLine(line),
  );
  private readonly waiters = new Set<() => void>();
  private proc: Subprocess<"pipe", "pipe", "pipe"> | null = null;

  async start(): Promise<void> {
    await mkdir(join(dir, "plugins"), { recursive: true });
    await copyFile(plugin, join(dir, "plugins", "KubekCompanion.jar"));
    await writeFile(join(dir, "eula.txt"), "eula=true\n");
    const props = join(dir, "server.properties");
    if (!existsSync(props)) {
      await writeFile(props, `online-mode=false\nserver-port=${port}\nlevel-type=flat\nspawn-protection=0\n`);
    }
    this.proc = Bun.spawn([java, "-Xms512M", `-Xmx${opts.xmx}`, ...(opts["jvm-arg"] ?? []), "-jar", resolve(jar), "nogui"], {
      cwd: dir,
      env: { ...process.env, KUBEK_COMPANION_TOKEN: token },
      stdin: "pipe",
      stdout: "pipe",
      stderr: "pipe",
    });
    void this.pump(this.proc.stdout, (chunk) => this.decoder.push(chunk));
    // frames only go to stdout, stderr is just more console
    let rest = "";
    const utf8 = new TextDecoder();
    void this.pump(this.proc.stderr, (chunk) => {
      const lines = (rest + utf8.decode(chunk, { stream: true })).split("\n");
      rest = lines.pop() ?? "";
      for (const line of lines) this.addLine(line);
    });
  }

  send(type: string, fields: Record<string, unknown> = {}): string {
    const id = crypto.randomUUID().replaceAll("-", "").slice(0, 12);
    this.raw(encodeRequest(token, { id, ts: Date.now(), t: type, ...fields }));
    return id;
  }

  raw(line: string): void {
    this.proc?.stdin.write(`${line}\n`);
    this.proc?.stdin.flush();
  }

  wait(predicate: Predicate, timeoutMs: number): Promise<Message | null> {
    const found = this.messages.find(predicate);
    if (found) return Promise.resolve(found);
    return new Promise((done) => {
      const check = () => {
        const message = this.messages.find(predicate);
        if (!message) return;
        clearTimeout(timer);
        this.waiters.delete(check);
        done(message);
      };
      const timer = setTimeout(() => {
        this.waiters.delete(check);
        done(null);
      }, timeoutMs);
      this.waiters.add(check);
    });
  }

  result(id: string, timeoutMs = 15_000): Promise<Message | null> {
    return this.wait((m) => m.t === "result" && m.id === id, timeoutMs);
  }

  event(type: string, timeoutMs: number, match: (item: Message) => boolean = () => true): Promise<Message | null> {
    return this.wait((m) => isEvent(m, type, match), timeoutMs);
  }

  countEvents(type: string): number {
    return this.messages.filter((m) => m.t === "events").flatMap((m) => m.items as Message[]).filter((i) => i.type === type)
      .length;
  }

  async stop(timeoutMs: number): Promise<void> {
    const proc = this.proc;
    if (!proc) return;
    const exited = await Promise.race([proc.exited.then(() => true), Bun.sleep(timeoutMs).then(() => false)]);
    if (!exited) proc.kill();
  }

  private addLine(line: string): void {
    this.log.push(line);
    if (opts.verbose) console.log(`  | ${line}`);
  }

  private async pump(stream: ReadableStream<Uint8Array>, sink: (chunk: Uint8Array) => void): Promise<void> {
    for await (const chunk of stream) sink(chunk);
  }
}

function isEvent(message: Message, type: string, match: (item: Message) => boolean = () => true): boolean {
  return message.t === "events" && (message.items as Message[]).some((item) => item.type === type && match(item));
}

const results: boolean[] = [];

function check(name: string, ok: boolean, detail = ""): void {
  console.log(`${ok ? "PASS" : "FAIL"} ${name}${detail ? ` - ${detail}` : ""}`);
  results.push(ok);
}

function short(value: unknown, max = 300): string {
  return (JSON.stringify(value) ?? "null").slice(0, max);
}

async function botScenario(p: Probe, protocol: BotProtocol): Promise<void> {
  const bot = new Bot("127.0.0.1", port, "ProbeBot", protocol);
  check("bot login", await bot.connect(), String(bot.disconnectReason ?? ""));
  check("join event", (await p.event("join", 15_000)) !== null);
  const stats = await p.wait((m) => m.t === "stats" && m.players?.length > 0, 20_000);
  const player = stats?.players[0];
  check("player in stats", player?.name === "ProbeBot", short(player));
  check("player coords", player !== undefined && "x" in player);
  check("player since", Number.isInteger(player?.since));

  let rid = p.send("title", { title: "&6hi", subtitle: "bot" });
  check("title with player", (await p.result(rid))?.ok === true);
  rid = p.send("countdown", { seconds: 2, message: "&ebot {s}" });
  check("countdown with player", (await p.result(rid)) !== null);
  const countdownId = rid;
  check("countdown_done with player", (await p.event("countdown_done", 15_000, (i) => i.id === countdownId)) !== null);

  rid = p.send("maintenance", { enabled: true, message: "&cProbe maintenance", allowOps: false, kickOnline: true });
  check("maintenance kick", (await p.result(rid)) !== null);
  await Promise.race([bot.closed, Bun.sleep(15_000)]);
  check("bot kicked", bot.isClosed, short(bot.disconnectReason, 120));
  check("quit event kicked", (await p.event("quit", 15_000, (i) => i.kicked === true)) !== null);

  const joinsBefore = p.countEvents("join");
  const again = new Bot("127.0.0.1", port, "ProbeBot", protocol);
  await again.connect(10_000);
  await Promise.race([again.closed, Bun.sleep(10_000)]);
  const reason = JSON.stringify(again.disconnectReason) ?? "";
  check("login refused", reason.includes("Probe maintenance"), reason.slice(0, 120));
  await Bun.sleep(2_000);
  check("no join while maintenance", p.countEvents("join") === joinsBefore);
  await p.result(p.send("maintenance", { enabled: false }));

  const third = new Bot("127.0.0.1", port, "ProbeBot", protocol);
  check("login after maintenance", await third.connect(), String(third.disconnectReason ?? ""));
  third.close();
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline && p.countEvents("quit") < 2) await Bun.sleep(500);
  check("quit after close", p.countEvents("quit") >= 2, `quits=${p.countEvents("quit")}`);
}

async function main(): Promise<void> {
  const p = new Probe();
  await p.start();

  const hello = await p.wait((m) => m.t === "hello", startupMs);
  check("hello", hello !== null, short(hello?.caps));
  check("ready", (await p.wait((m) => m.t === "ready", startupMs)) !== null);

  let rid = p.send("config", { intervalSec: 5, coords: true, chat: true });
  let res = await p.result(rid);
  check("config", res?.ok === true, short(res?.data));

  const stats = await p.wait((m) => m.t === "stats", 30_000);
  check("stats", stats?.worlds != null, stats ? short({ tps: stats.tps, tpsSource: stats.tpsSource, mspt: stats.mspt, online: stats.online }) : "");
  if (stats) {
    console.log("     jvm:", short(stats.jvm, 1000));
    console.log("     worlds:", short(stats.worlds, 1000));
  }
  // 2nd window has real deltas for MSPT and GC
  const stats2 = await p.wait((m) => m.t === "stats" && m !== stats, 30_000);
  if (stats2) console.log("     mspt#2:", short(stats2.mspt), "gc#2:", short(stats2.jvm?.gc));

  rid = p.send("plugins");
  res = await p.result(rid);
  check("plugins", res?.ok === true && res.data?.length > 0, short(res?.data));

  rid = p.send("lag_report", { limit: 5 });
  const report = await p.result(rid);
  check("lag_report", report?.ok === true, short(report?.data, 600));
  res = await p.result(p.send("lag_report", { limit: 5 }));
  check("lag_report cached", res?.ok === true && res.data?.ts === report?.data?.ts);
  res = await p.result(p.send("lag_report", { limit: 7 }));
  check("lag_report rate limited", res?.ok === false && res.error === "rate_limited", short(res));

  if (botProtocol !== null) await botScenario(p, botProtocol);

  const actions: [string, Record<string, unknown>][] = [
    ["broadcast", { message: "&aprobe broadcast" }],
    ["title", { title: "&6probe", subtitle: "title" }],
    ["maintenance", { enabled: true, message: "&cmaintenance", motd: "&cmaint" }],
    ["maintenance", { enabled: false }],
  ];
  for (const [type, fields] of actions) {
    res = await p.result(p.send(type, fields));
    check(type, res?.ok === true, short(res?.data));
  }

  // a new countdown replaces the running one and says so
  const first = p.send("countdown", { seconds: 60, message: "&eprobe restart in {time}" });
  check("countdown", (await p.result(first))?.ok === true);
  const second = p.send("countdown", { seconds: 3, message: "&eprobe restart in {s}" });
  check("countdown replaced", (await p.result(second))?.ok === true);
  check("countdown_cancelled for the old one", (await p.event("countdown_cancelled", 5_000, (i) => i.id === first)) !== null);
  check("countdown_done", (await p.event("countdown_done", 15_000, (i) => i.id === second)) !== null);

  res = await p.result(p.send("nope"));
  check("unknown type rejected", res?.ok === false && res.error === "unknown_type");

  // forged and stale requests must vanish, no reply and no console noise
  p.raw("kubekc 00000000000000000000000000000000 eyJpZCI6ImZvcmdlZCIsInRzIjowLCJ0IjoiaGVsbG8ifQ");
  p.raw(encodeRequest(token, { id: "stale1", ts: Date.now() - 600_000, t: "hello" }));
  // replayed line: same id twice, only one reply
  const replay = encodeRequest(token, { id: "replay1", ts: Date.now(), t: "hello" });
  p.raw(replay);
  p.raw(replay);
  await Bun.sleep(2_000);
  check("forged/stale ignored", (await p.result("forged", 1_000)) === null && (await p.result("stale1", 1_000)) === null);
  check("replay ignored", p.messages.filter((m) => m.t === "result" && m.id === "replay1").length === 1);

  p.raw("stop");
  check("stopping", (await p.wait((m) => m.t === "stopping", 120_000)) !== null);
  await p.stop(120_000);

  const leaks = (line: string) =>
    line.includes("kubekc") || line.includes("7768") || line.includes("Unknown command") || line.includes("Unknown or incomplete");
  const consoleLeak = p.log.filter(leaks);
  check("console clean", consoleLeak.length === 0, consoleLeak.slice(0, 3).join(" | "));
  const latest = join(dir, "logs", "latest.log");
  const logText = existsSync(latest) ? await readFile(latest, "utf8") : "";
  const fileLeak = logText.split("\n").filter(leaks);
  check("latest.log clean", fileLeak.length === 0, fileLeak.slice(0, 3).join(" | "));
  // the server's own lines about loading plugins are fine, anything else from us isn't
  const serverLines = ["Enabling", "Disabling", "Loading", "- KubekCompanion (", "Remapping", "remapped"];
  const noisy = p.log.filter((l) => l.includes("KubekCompanion") && !serverLines.some((s) => l.includes(s)));
  check("no plugin chatter", noisy.length === 0, noisy.slice(0, 3).join(" | "));
  check("no exceptions", !p.log.some((l) => l.includes("Exception") && l.toLowerCase().includes("kubek")));
  check("signatures", p.decoder.badFrames === 0, `bad=${p.decoder.badFrames}`);
  check("no data folder", !existsSync(join(dir, "plugins", "KubekCompanion")));

  if (opts.dump) await writeFile(opts.dump, JSON.stringify(p.messages, null, 1));
  const passed = results.filter(Boolean).length;
  console.log(`${passed}/${results.length} passed`);
  process.exit(passed === results.length ? 0 : 1);
}

await main();
