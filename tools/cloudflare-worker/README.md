# Cloudflare Worker diagnostic relay

This is an isolated diagnostic step before wiring Cloudflare Worker fallback into `ProxyServer`.

The relay behavior of `worker.js` follows the current upstream `Flowseal/tg-ws-proxy` Worker model:

- Android connects to the Worker domain over HTTPS/WSS.
- `/apiws?dst=<telegram-ip>&dc=<dc>` upgrades to WebSocket.
- the Worker opens raw TCP to `<telegram-ip>:443` through `cloudflare:sockets`.
- normal `/apiws` traffic is passed through without Telegram WebSocket message splitting.

The diagnostic version adds endpoints which do not affect the normal relay path:

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

Cloudflare itself recommends a Custom Domain or Worker Route instead of `workers.dev` for production. The Android probe intentionally starts with `workers.dev` because it is useful for separating Worker deployment problems from ISP/TLS-path problems.

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

The normal output reports these boundaries independently:

1. DNS
2. automatic TCP to Worker port 443
3. automatic TLS to Worker
4. Worker `/health`
5. Worker outbound TCP to Telegram
6. WebSocket `/apiws` plus Worker outbound-TCP acknowledgement

A successful final line is:

```text
RESULT: WORKER END-TO-END PROBE OK
```

## TLS failure characterization

If automatic TCP succeeds but automatic TLS fails, the probe no longer stops immediately. It automatically runs a bounded comparison intended to distinguish the common failure classes seen on filtered/mobile networks:

- explicit IPv4 Worker TLS while keeping Worker SNI and certificate hostname unchanged;
- explicit IPv6 Worker TLS while keeping Worker SNI and certificate hostname unchanged;
- up to two resolved addresses per family, so one bad Cloudflare edge does not immediately decide the result;
- forced TLS 1.2 when the default ClientHello fails on both families;
- a control TLS handshake using `cloudflare.com` as SNI/peer host on the **same physical Cloudflare edge IP**.

If an explicit IPv4 or IPv6 Worker TLS connection succeeds, the probe immediately tries `/apiws?probe=1` using that IP as the physical connection target while preserving the Worker hostname as SNI and HTTP Host. This mirrors the preferred-IP technique used by other Cloudflare tunnel implementations: the IP chooses a reachable Cloudflare edge, while the hostname still selects the Worker virtual host.

Possible high-signal results include:

```text
RESULT: WORKER END-TO-END PROBE OK VIA FORCED IPv4
```

This means the Worker itself is usable on that network and Android should support preferred/pinned Cloudflare IPv4 selection for the Worker transport.

```text
HINT: TLSv1.2 succeeds where the default ClientHello fails...
```

This points toward TLS-version/fingerprint filtering rather than a simple IP-family problem.

```text
HINT: the same Cloudflare edge answers TLS for cloudflare.com but not for <worker-host>...
```

This strongly points toward hostname/SNI-specific filtering. A Worker Custom Domain is then the next test.

If both the Worker hostname and the control SNI fail on the same tested Cloudflare edge addresses, the disruption is broader than `workers.dev` alone.

## Scope

This branch deliberately does **not** add the Worker to AUTO, CF pressure handling, pools, mobile rescue, or production routing. The diagnostic result should decide which production transport behavior is justified first: preferred IPv4/edge selection, TLS-specific handling, Custom Domain support, or abandoning Cloudflare for that network.
