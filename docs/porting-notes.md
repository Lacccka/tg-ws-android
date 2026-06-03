# Porting Notes

## Repository rules

1. Never edit `third_party/tg-ws-proxy` directly. Treat it as the upstream source
   of truth and update it only through normal git submodule operations.
2. Every ported Kotlin class must mention the upstream Python file and function
   or class it mirrors in a KDoc comment near the class declaration.
3. All critical logic must have parity tests against Python-generated vectors
   before it is considered ported.
4. Do not mix unrelated upstream ports in one change. Prefer small commits that
   update one mapped module and its parity vectors.
5. If `tools/check_upstream.py` reports a changed hash, inspect the upstream diff
   before editing Kotlin code and record any required porting work in the commit
   or PR summary.

## Current scope

The initial Android structure includes only the runtime configuration model. The
proxy networking, socket bridge, WebSocket framing, fake TLS, pooling, and
balancing code remain intentionally unimplemented until parity vectors exist.

## Parity-test workflow

1. Generate vectors from the exact submodule revision recorded in git.
2. Store vectors under a future test-vector directory with enough metadata to
   identify the upstream file/function and submodule commit.
3. Add Android unit tests that compare Kotlin outputs with the Python-generated
   vectors.
4. Run `python3 tools/check_upstream.py` in every porting change to detect
   upstream drift.
