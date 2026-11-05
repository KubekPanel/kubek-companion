import { connect, type Socket } from "node:net";
import { inflateSync } from "node:zlib";

export type BotProtocol = 47 | 340;

// 47 = 1.8.x, 340 = 1.12.2
const KEEPALIVE: Record<BotProtocol, { clientbound: number; serverbound: number; asLong: boolean }> = {
  47: { clientbound: 0x00, serverbound: 0x00, asLong: false },
  340: { clientbound: 0x1f, serverbound: 0x0b, asLong: true },
};
const DISCONNECT_PLAY: Record<BotProtocol, number> = { 47: 0x40, 340: 0x1a };

function varint(value: number): Buffer {
  const out: number[] = [];
  let rest = value >>> 0;
  do {
    let byte = rest & 0x7f;
    rest >>>= 7;
    if (rest) byte |= 0x80;
    out.push(byte);
  } while (rest);
  return Buffer.from(out);
}

/** [value, next position], null if the buffer ends mid-varint */
function readVarint(data: Buffer, start: number): [number, number] | null {
  let result = 0;
  let pos = start;
  for (let shift = 0; shift < 35; shift += 7) {
    const byte = data[pos++];
    if (byte === undefined) return null;
    result += (byte & 0x7f) * 2 ** shift;
    if (!(byte & 0x80)) return [result, pos];
  }
  throw new Error("varint too long");
}

function mustVarint(data: Buffer, start: number): [number, number] {
  const read = readVarint(data, start);
  if (!read) throw new Error("truncated packet");
  return read;
}

function mcString(text: string): Buffer {
  const raw = Buffer.from(text);
  return Buffer.concat([varint(raw.length), raw]);
}

/** Offline-mode client, just enough to join, stay online and see the kick reason */
export class Bot {
  disconnectReason: unknown = null;
  isClosed = false;
  readonly joined: Promise<boolean>;
  readonly closed: Promise<void>;
  private socket: Socket | null = null;
  private threshold = -1;
  private state: "login" | "play" = "login";
  private incoming = Buffer.alloc(0);
  private resolveJoined: (ok: boolean) => void = () => {};
  private resolveClosed: () => void = () => {};

  constructor(
    private readonly host: string,
    private readonly port: number,
    private readonly name: string,
    private readonly protocol: BotProtocol,
  ) {
    this.joined = new Promise((resolve) => (this.resolveJoined = resolve));
    this.closed = new Promise((resolve) => (this.resolveClosed = resolve));
  }

  connect(timeoutMs = 20_000): Promise<boolean> {
    const socket = connect(this.port, this.host);
    this.socket = socket;
    socket.on("connect", () => {
      const port = Buffer.alloc(2);
      port.writeUInt16BE(this.port);
      this.send(0x00, Buffer.concat([varint(this.protocol), mcString(this.host), port, varint(2)]));
      this.send(0x00, mcString(this.name));
    });
    socket.on("data", (chunk: Buffer) => this.onData(chunk));
    socket.on("error", () => {});
    socket.on("close", () => {
      this.isClosed = true;
      this.resolveJoined(false);
      this.resolveClosed();
    });
    return Promise.race([this.joined, Bun.sleep(timeoutMs).then(() => false)]);
  }

  close(): void {
    this.socket?.destroy();
  }

  private send(packetId: number, payload: Buffer): void {
    let body = Buffer.concat([varint(packetId), payload]);
    if (this.threshold >= 0) body = Buffer.concat([varint(0), body]);
    this.socket?.write(Buffer.concat([varint(body.length), body]));
  }

  private onData(chunk: Buffer): void {
    this.incoming = Buffer.concat([this.incoming, chunk]);
    try {
      for (;;) {
        const header = readVarint(this.incoming, 0);
        if (!header) return;
        const [length, start] = header;
        if (this.incoming.length < start + length) return;
        const packet = this.incoming.subarray(start, start + length);
        this.incoming = this.incoming.subarray(start + length);
        this.handle(packet);
      }
    } catch {
      this.close();
    }
  }

  private handle(packet: Buffer): void {
    let data = packet;
    if (this.threshold >= 0) {
      const [dataLength, pos] = mustVarint(data, 0);
      data = dataLength ? inflateSync(data.subarray(pos)) : data.subarray(pos);
    }
    const [packetId, pos] = mustVarint(data, 0);
    const body = data.subarray(pos);

    if (this.state === "login") {
      if (packetId === 0x03) {
        this.threshold = mustVarint(body, 0)[0];
      } else if (packetId === 0x02) {
        this.state = "play";
        this.resolveJoined(true);
      } else if (packetId === 0x00) {
        this.disconnectReason = text(body);
        this.close();
      }
      return;
    }

    const keepAlive = KEEPALIVE[this.protocol];
    if (packetId === keepAlive.clientbound) {
      this.send(keepAlive.serverbound, keepAlive.asLong ? body.subarray(0, 8) : varint(mustVarint(body, 0)[0]));
    } else if (packetId === DISCONNECT_PLAY[this.protocol]) {
      this.disconnectReason = text(body);
      this.close();
    }
  }
}

/** Chat component as JSON, raw string if it isn't JSON */
function text(body: Buffer): unknown {
  const [length, pos] = mustVarint(body, 0);
  const raw = body.subarray(pos, pos + length).toString("utf8");
  try {
    return JSON.parse(raw);
  } catch {
    return raw;
  }
}
