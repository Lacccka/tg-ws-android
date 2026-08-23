import { connect } from "cloudflare:sockets";

const WORKER_NAME = "tg-ws-worker";
const WORKER_VERSION = "1";
const TCP_TIMEOUT_MS = 5000;

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

function toBytes(data) {
  if (data instanceof ArrayBuffer) {
    return new Uint8Array(data);
  }
  if (typeof data === "string") {
    return new TextEncoder().encode(data);
  }
  if (data && typeof data.arrayBuffer === "function") {
    return data.arrayBuffer().then((ab) => new Uint8Array(ab));
  }
  return new Uint8Array();
}

function isAllowedTelegramDestination(dst) {
  if (!/^\d{1,3}(?:\.\d{1,3}){3}$/.test(dst || "")) {
    return false;
  }
  const octets = dst.split(".").map(Number);
  if (octets.some((value) => value < 0 || value > 255)) {
    return false;
  }

  // Keep the diagnostic relay scoped to Telegram-owned address families used by
  // upstream tg-ws-proxy instead of exposing an arbitrary public TCP proxy.
  return dst.startsWith("149.154.") || dst.startsWith("91.108.") || dst.startsWith("91.105.");
}

function timeoutPromise(ms, label) {
  return new Promise((_, reject) => {
    setTimeout(() => reject(new Error(`${label} timeout after ${ms}ms`)), ms);
  });
}

async function waitUntilOpened(socket) {
  return Promise.race([
    socket.opened,
    timeoutPromise(TCP_TIMEOUT_MS, "outbound TCP connect"),
  ]);
}

async function closeQuietly(socket) {
  try {
    await socket.close();
  } catch {}
}

async function probeTcp(dst) {
  const started = Date.now();
  const socket = connect({ hostname: dst, port: 443 });
  try {
    const info = await waitUntilOpened(socket);
    return {
      ok: true,
      dst,
      port: 443,
      elapsed_ms: Date.now() - started,
      remote_address: info?.remoteAddress || null,
    };
  } finally {
    await closeQuietly(socket);
  }
}

async function handleWebSocket(request, url, dst) {
  const pair = new WebSocketPair();
  const client = pair[0];
  const server = pair[1];
  server.accept();

  const socket = connect({ hostname: dst, port: 443 });

  if (url.searchParams.get("probe") === "1") {
    (async () => {
      const started = Date.now();
      try {
        const info = await waitUntilOpened(socket);
        server.send(JSON.stringify({
          type: "worker_tcp_probe",
          ok: true,
          dst,
          port: 443,
          elapsed_ms: Date.now() - started,
          remote_address: info?.remoteAddress || null,
        }));
        try {
          server.close(1000, "probe complete");
        } catch {}
      } catch (error) {
        try {
          server.send(JSON.stringify({
            type: "worker_tcp_probe",
            ok: false,
            dst,
            error: String(error),
          }));
          server.close(1011, "tcp probe failed");
        } catch {}
      } finally {
        await closeQuietly(socket);
      }
    })();

    return new Response(null, { status: 101, webSocket: client });
  }

  const tcpReader = socket.readable.getReader();
  const tcpWriter = socket.writable.getWriter();

  server.addEventListener("message", async (event) => {
    try {
      await tcpWriter.write(await toBytes(event.data));
    } catch {
      try {
        server.close(1011, "tcp write failed");
      } catch {}
    }
  });

  server.addEventListener("close", async () => {
    try {
      await tcpWriter.close();
    } catch {}
    await closeQuietly(socket);
  });

  (async () => {
    try {
      while (true) {
        const { value, done } = await tcpReader.read();
        if (done) {
          break;
        }
        if (value) {
          server.send(value);
        }
      }
    } catch {
    } finally {
      try {
        server.close();
      } catch {}
      try {
        tcpReader.releaseLock();
      } catch {}
      await closeQuietly(socket);
    }
  })();

  return new Response(null, { status: 101, webSocket: client });
}

export default {
  async fetch(request) {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return json({
        ok: true,
        service: WORKER_NAME,
        version: WORKER_VERSION,
      });
    }

    const dst = (url.searchParams.get("dst") || "").trim();
    if (!isAllowedTelegramDestination(dst)) {
      return json({
        ok: false,
        error: "dst must be an allowed Telegram IPv4 address",
      }, 403);
    }

    if (url.pathname === "/probe") {
      try {
        return json(await probeTcp(dst));
      } catch (error) {
        return json({
          ok: false,
          dst,
          port: 443,
          error: String(error),
        }, 502);
      }
    }

    if (url.pathname !== "/apiws") {
      return json({ ok: false, error: "not found" }, 404);
    }

    if ((request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") {
      return json({ ok: false, error: "expected websocket" }, 426);
    }

    return handleWebSocket(request, url, dst);
  },
};
