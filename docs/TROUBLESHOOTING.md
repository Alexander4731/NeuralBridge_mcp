[← Back to README](../README.md)

# 🔧 Troubleshooting

### 🌐 Cannot connect to MCP server (HTTP)

```bash
# Verify the same-device loopback server is running
curl http://127.0.0.1:7474/health

# Verify the token and MCP endpoint
. ~/.config/neuralbridge/env
curl -i -H "Authorization: Bearer $NEURALBRIDGE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
  http://127.0.0.1:7474/mcp
```

Also check that the NeuralBridge toggle is enabled in the app; the MCP server only runs when the toggle is on. HTTP 401 means the token is missing or wrong. HTTP 403 means a non-loopback browser Origin was supplied.

---

### ♿ AccessibilityService not running

```bash
# Check current status
adb shell settings get secure enabled_accessibility_services

# Re-enable
adb shell settings put secure enabled_accessibility_services \
  com.neuralbridge.companion/.service.NeuralBridgeAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

On Android 15+, you may also need to enable **"Allow restricted settings"** for NeuralBridge in Settings > Apps.

---

### 📸 Screenshots use the fallback path

MediaProjection requires a one-time user consent. Open the NeuralBridge app on the device and trigger a screenshot, then tap "Start now" on the system dialog. On Android 14+, this consent resets when the app process dies. Android 11+ can use `AccessibilityService.takeScreenshot()` as a slower fallback; UI tree workflows need neither path.

---

### 🐌 High latency on first screenshot

The first screenshot after MediaProjection setup takes 150-300ms (warm-up). Subsequent screenshots run at ~60ms. This is a one-time cost per session.

---

### 💥 Companion app crashes or stops responding

```bash
# Check crash logs
adb logcat -s NeuralBridge:V

# Force restart
adb shell am force-stop com.neuralbridge.companion
adb shell am start -n com.neuralbridge.companion/.MainActivity

# Re-enable AccessibilityService after restart
adb shell settings put secure enabled_accessibility_services \
  com.neuralbridge.companion/.service.NeuralBridgeAccessibilityService
```
