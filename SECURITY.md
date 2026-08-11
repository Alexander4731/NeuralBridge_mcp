# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.4.1-nb1 | Yes     |

## Reporting a Vulnerability

If you discover a security vulnerability in NeuralBridge, please report it responsibly.

**Do not open a public issue.**

### How to Report

Use [GitHub's private vulnerability reporting](https://github.com/Alexander4731/NeuralBridge_mcp/security/advisories/new) to submit your report.

### What to Include

- Description of the vulnerability
- Steps to reproduce
- Affected component (MCP server, companion app, protocol)
- Impact assessment (what an attacker could achieve)
- Any suggested fix, if you have one

### Response Timeline

- **Acknowledgment:** Within 48 hours
- **Initial assessment:** Within 7 days
- **Fix or mitigation:** Within 90 days (depending on severity)

We will coordinate disclosure with you. Credit will be given in the release notes unless you prefer to remain anonymous.

## Scope

### In Scope

- Unauthorized access to the MCP HTTP server from the local network
- Remote code execution via MCP tool calls
- Privilege escalation through the AccessibilityService
- Data exfiltration (UI tree data, screenshots, clipboard)
- Protocol vulnerabilities (JSON-RPC parsing, HTTP handling)
- Denial of service against the MCP server or companion app

### Out of Scope

- Attacks requiring physical access to the device
- Attacks requiring a rooted/jailbroken device
- Attacks requiring the user to install a malicious app alongside NeuralBridge
- Social engineering
- Vulnerabilities in dependencies (report these to the upstream project)

## Security Architecture

### Network exposure

NeuralBridge runs an MCP HTTP server (Ktor CIO) on `127.0.0.1:7474`. It is intentionally reachable only by processes on the same Android device. There is no TLS because traffic never leaves the local network namespace.

A legacy TCP/protobuf server on port 38472 is still present but binds to **localhost only** and is not used by MCP clients.

### Authentication

Every `/mcp` POST requires `Authorization: Bearer <token>`. New installations receive a 256-bit random token stored in private app preferences. Existing installations retain their previously generated token. Comparison uses `MessageDigest.isEqual`, and unauthorized responses include `WWW-Authenticate: Bearer`.

Android apps share the device network namespace, so loopback binding alone is not treated as authentication. The app shows only a shortened token preview and provides an explicit copy action for configuring the local MCP client.

### CORS

Native clients may omit `Origin`. Every supplied `Origin` is parsed and must use HTTP(S) with `localhost`, `127.0.0.1`, or `::1`; all others receive HTTP 403. CORS responses echo only an accepted origin and include `Vary: Origin`.

### AccessibilityService

The companion app uses Android's AccessibilityService API, which grants full UI control (taps, swipes, text input, reading the UI tree). This requires explicit user enablement in device Settings. MediaProjection (for screenshots) requires a separate user consent dialog.

### Known risks

- **Full device control:** A client with the bearer token can invoke gestures, text input, UI reads, and screenshot capture.
- **Root boundary:** Device root can bypass Android app sandbox protections. Root commands remain in Termux and are not exposed as NeuralBridge MCP tools.
- **Token handling:** Copying the token places it on the Android clipboard. Clear or replace the clipboard after configuring Termux on sensitive devices.
- **Local denial of service:** Another local app can connect to the loopback port, but cannot make authenticated tool calls without the token.

Treat the token like a local password. Do not put it in Git, screenshots, shell history, issue reports, or shared logs.
