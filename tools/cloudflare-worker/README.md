# Cloudflare Worker diagnostic relay

This is an isolated diagnostic step before wiring Cloudflare Worker fallback into `ProxyServer`.

The relay behavior of `worker.js` follows the current upstream `Flowseal/tg-ws-proxy` Worker model:

- Android connects to the Worker domain over HTTPS/WSS.
- `/apiws?dst=<telegram-ip>&dc=<dc>` upgrades to WebSocket.
- the Worker opens raw TCP to `<telegram-ip>:443` through `cloudflare:sockets`.
- normal `/apiws` traffic is passed through without Telegram WebSocket message splitting.

The diagnostic version adds two endpoints which do not affect the normal relay path:

- `GET /health` — proves that the Worker itself is reachable and running the expected code.
- `GET /probe?dst=<telegram-ip>` — waits for `socket.opened`, proving that Cloudflare can establish TCP to Telegram.
- `/apiws?...&probe=1` — upgrades to WebSocket and sends one JSON probe result after outbound TCP is established.

For safety this diagnostic Worker only permits IPv4 destinations beginning with `149.154.`, `91.108.` or `91.105.` and always connects to port 443.

## Deploy

1. Sign in to Cloudflare Dashboard.
2. Open **Compute → Workers & Pages**.
3. Create a Worker from the Hello World template.
4. Open **Edit code**.
5. Replace the generated code with the contents of `worker.js`.
6. Deploy.
7. Copy the resulting hostname, for example `example.username.workers.dev`. Do not include `https://` or a path when entering it in the Android test.

A custom domain is not required for the first test. The objective is to determine whether the phone can reach the Worker endpoint on Wi-Fi and mobile data.

## Android test

Build the `privateSideload` variant locally:

```powershell
.\gradlew assemblePrivateSideload
```

Installing this variant adds a second temporary launcher entry named **SE Worker Test**. It exists only in `privateSideload`; the normal application launcher is unchanged.

Open **SE Worker Test**, enter the Worker hostname, keep the configured DC2 Telegram target unless you intentionally want another target, then tap **Проверить Worker**.

Run the same probe twice:

1. Wi-Fi
2. mobile data with Wi-Fi disabled

The output reports these boundaries independently:

1. DNS
2. TCP to Worker port 443
3. TLS to Worker
4. Worker `/health`
5. Worker outbound TCP to Telegram
6. WebSocket `/apiws` plus Worker outbound-TCP acknowledgement

A successful final line is:

```text
RESULT: WORKER END-TO-END PROBE OK
```

If a stage fails, the first `FAIL` line is the important result. Copy the full output and include the network type when reporting it.

## Scope

This branch deliberately does **not** add the Worker to AUTO, CF pressure handling, pools, mobile rescue, or production routing. The test must establish that the relay is reachable end to end first.
