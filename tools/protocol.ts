import { createHmac, timingSafeEqual } from "node:crypto";

// fields depend on `t`
export type Message = { t: string; ts: number; [field: string]: any };

const FRAME = /\x1b\]7768;kc1;([0-9a-z]+);(\d+);(\d+);([0-9a-f]{32});([\x20-\x7e]*)\x07\n?/;
const FRAME_START = "\x1b]7768";

export function sign(token: string, payload: string): string {
  return createHmac("sha256", token).update(payload).digest("hex").slice(0, 32);
}

/** Console line for a request, `kubekc <sig> <base64url json>` */
export function encodeRequest(token: string, body: Record<string, unknown>): string {
  const encoded = Buffer.from(JSON.stringify(body)).toString("base64url");
  return `kubekc ${sign(token, encoded)} ${encoded}`;
}

/** Splits server stdout into signed messages and plain console lines */
export class FrameDecoder {
  badFrames = 0;
  // latin1 keeps 1 byte = 1 char, so a UTF-8 char split between chunks survives
  private buffer = "";
  private parts = new Map<string, Map<number, string>>();

  constructor(
    private readonly token: string,
    private readonly onMessage: (message: Message) => void,
    private readonly onLine: (line: string) => void,
  ) {}

  push(chunk: Uint8Array): void {
    this.buffer += Buffer.from(chunk).toString("latin1");
    // frames can sit mid-line, cut them out before splitting lines
    for (let m = FRAME.exec(this.buffer); m; m = FRAME.exec(this.buffer)) {
      const [whole, id = "", part = "", parts = "", sig = "", chunkText = ""] = m;
      this.frame(id, Number(part), Number(parts), sig, chunkText);
      this.buffer = this.buffer.slice(0, m.index) + this.buffer.slice(m.index + whole.length);
    }
    // a half-received frame stays in the buffer along with the line it started on
    const pending = this.buffer.indexOf(FRAME_START);
    const cut = this.buffer.lastIndexOf("\n", (pending === -1 ? this.buffer.length : pending) - 1);
    if (cut === -1) return;
    for (const line of this.buffer.slice(0, cut).split("\n")) {
      this.onLine(Buffer.from(line, "latin1").toString("utf8").replace(/\r$/, ""));
    }
    this.buffer = this.buffer.slice(cut + 1);
  }

  private frame(id: string, part: number, parts: number, sig: string, chunk: string): void {
    let chunks = this.parts.get(id);
    if (!chunks) {
      chunks = new Map();
      this.parts.set(id, chunks);
    }
    chunks.set(part, chunk);
    if (chunks.size < parts) return;
    this.parts.delete(id);
    const payload = Array.from({ length: parts }, (_, i) => chunks.get(i) ?? "").join("");
    if (!timingSafeEqual(Buffer.from(sign(this.token, payload)), Buffer.from(sig))) {
      this.badFrames++;
      return;
    }
    this.onMessage(JSON.parse(payload) as Message);
  }
}
