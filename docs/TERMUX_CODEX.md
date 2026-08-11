# Termux + Codex

This fork keeps the Android control layer separate from the agent layer:

- NeuralBridge provides AccessibilityService-based observation and actions over MCP.
- Codex CLI runs in the existing Termux installation and supplies the model, planning, shell, and tool loop.
- The model provider and custom API relay remain in the existing Codex configuration.
- Root remains a Termux capability through `su -c`; NeuralBridge does not expose a root MCP tool.

## 1. Start NeuralBridge

Install the APK, enable its AccessibilityService, open the app, and enable the master switch. The server listens only on:

```text
http://127.0.0.1:7474/mcp
```

On the Status tab, tap **COPY TOKEN**. The visible preview is shortened; the copy action places the full token on the clipboard.

## 2. Store the token in Termux

Run the following and paste the token at the hidden prompt:

```bash
mkdir -p ~/.config/neuralbridge
chmod 700 ~/.config/neuralbridge
umask 077
read -rsp 'NeuralBridge token: ' NB_TOKEN; echo
printf "export NEURALBRIDGE_TOKEN='%s'\n" "$NB_TOKEN" > ~/.config/neuralbridge/env
unset NB_TOKEN
grep -qxF '. "$HOME/.config/neuralbridge/env"' ~/.bashrc 2>/dev/null || \
  printf '%s\n' '. "$HOME/.config/neuralbridge/env"' >> ~/.bashrc
. ~/.config/neuralbridge/env
```

The generated file is mode `600` under the current `umask`. Do not commit or share it. Replace the Android clipboard after setup if other apps should not retain the copied token.

## 3. Add the MCP server to Codex

```bash
codex mcp add neuralbridge \
  --url http://127.0.0.1:7474/mcp \
  --bearer-token-env-var NEURALBRIDGE_TOKEN

codex mcp get neuralbridge
```

If an older entry already exists, remove it once with `codex mcp remove neuralbridge`, then add the secured entry again.

This command changes only the MCP entry. It does not overwrite `~/.codex/config.toml`, the selected model, API key environment variables, or a custom OpenAI-compatible relay.

## 4. Verify without screenshots

Start Codex and ask it to list the NeuralBridge tools, then read the current screen with `android_get_ui_tree`. A normal automation loop should prefer:

1. `android_get_ui_tree`, `android_find_elements`, or `android_get_screen_context` only when combined context is useful.
2. Selector-based actions such as tap, input, scroll, and app launch.
3. `android_wait_for_idle`, `android_wait_for_element`, or `android_wait_for_gone` after state changes.
4. `android_screenshot` only for canvases, images, visual ambiguity, or explicit visual verification.

This is not a forced one-action-one-screenshot loop. Screenshot permission can remain ungranted for tree-based workflows.
The MCP `initialize` response repeats this tree-first policy so compatible clients receive it automatically.

## 5. Root boundary

KernelSU root remains available to Codex through Termux shell commands:

```bash
su -c 'id'
```

Use root only for commands that need it. Keeping root out of the Accessibility APK makes the MCP surface smaller and preserves a clear audit boundary between Android UI control and privileged shell work.

## Troubleshooting

```bash
curl http://127.0.0.1:7474/health
curl -i -H "Authorization: Bearer $NEURALBRIDGE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
  http://127.0.0.1:7474/mcp
```

- HTTP 401: the environment variable is missing or does not match the token in the app.
- HTTP 403: a non-loopback browser `Origin` was supplied.
- Connection refused: NeuralBridge is disabled, its AccessibilityService is not running, or port 7474 is already in use.
