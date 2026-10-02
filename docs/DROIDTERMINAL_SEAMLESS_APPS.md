# DroidTerminal Seamless Linux Apps

## Goal

Turn the validated PJZ110 / SM8750 Gunyah VM stack into a terminal-first Linux subsystem that can expose Linux GUI applications on the Android launcher/home screen.

Target user experience:

1. install a Linux GUI application inside the guest;
2. DroidTerminal discovers its `.desktop` entry and icon;
3. the app appears as an Android home-screen shortcut;
4. tapping the shortcut wakes/starts the Linux VM if required;
5. only the requested Linux application is launched;
6. its GUI is presented in a dedicated Android Activity/window;
7. Android back/home/recents behave like a normal Android app;
8. closing the Android Activity closes or detaches the corresponding Linux window, not the whole VM.

This is intentionally closer to WSLg / seamless-app integration than to a full remote desktop.

## Validated host baseline

PJZ110 / SM8750 has already validated:

- crosvm + Gunyah
- pseudo-unprotected protected VM mode
- hugepages + chunked lend-mTHP
- virtio-blk
- virtio-net
- virtio-rng
- SimpleFB
- virtio-gpu 2D
- virglrenderer Native Context + DRM2KGSL
- virglrenderer Vulkan (Venus) + Turnip
- GfxStream + Turnip

This branch starts from that validated state.

## Architecture

```
Android launcher
      |
      | pinned shortcut / app entry
      v
LinuxAppLaunchActivity
      |
      v
DroidTerminalHost
      |
      +-- VM lifecycle ----------------------+
      |                                      |
      +-- app registry                       |
      +-- window/session registry            |
      +-- input / clipboard / file-open      |
      |                                      v
      |                               crosvm + Gunyah
      |                                      |
      |                                   vsock
      |                                      |
      +------------------------------ droidbridge-agent
                                             |
                       +---------------------+---------------------+
                       |                     |                     |
                 app discovery          process launch        GUI bridge
                 .desktop files         env/session           Wayland/X11
```

## Component boundaries

### 1. DroidTerminal Android shell

Owns Android-facing behavior only:

- terminal tabs;
- Linux distribution management;
- app catalog;
- home-screen shortcut publishing;
- Activity/window lifecycle;
- Android input method;
- clipboard integration;
- Android file picker / share sheet integration;
- notifications;
- VM wake/suspend policy.

It must not directly open `/dev/gunyah`.

### 2. Privileged host daemon

A small privileged daemon owns:

- crosvm lifecycle;
- Gunyah device access;
- TAP/bridge setup;
- hugepage and lend-mTHP preparation;
- VM suspend/resume/stop;
- vsock transport setup;
- display/exporter setup.

The UI app talks to the daemon over a narrow Binder or Unix-domain RPC interface.

### 3. Guest agent: droidbridge-agent

Runs inside the Linux guest and provides a stable host/guest protocol.

Responsibilities:

- enumerate `/usr/share/applications/*.desktop`;
- enumerate `~/.local/share/applications/*.desktop`;
- resolve localized app names;
- resolve application icons;
- report MIME types and supported URI/file handlers;
- launch apps by immutable app ID;
- report process/window lifecycle;
- proxy clipboard;
- expose file-open/save requests;
- start the GUI bridge;
- expose health/version/capability information.

The Android side must send an app ID, never an arbitrary shell command.

## Launcher integration

### Stage A: standard Android pinned shortcuts

The first implementation uses Android's ShortcutManager.

Each Linux app becomes a pinned shortcut whose Intent contains only:

- VM ID;
- Linux app ID.

The shortcut icon comes from the guest app's desktop icon.

This gives the requested "tap the Linux app directly from the phone desktop" workflow without requiring ColorOS launcher modification.

### Stage B: System Edition

The privileged/system build can add deeper integration:

- shortcut publication without repeated user prompts where platform policy permits;
- Settings > Developer options > Linux environment redirect;
- boot-time Linux subsystem service;
- stronger process lifetime integration;
- optional ColorOS launcher plugin/integration if a stable interface is available.

Do not depend on OEM platform-signature-only permissions for the core design.

## Linux app identity

A Linux application is represented by an opaque stable ID derived from the desktop entry, for example:

```
org.libreoffice.LibreOffice.writer
com.jetbrains.CLion
org.gnome.Nautilus
```

The host stores:

```json
{
  "vm_id": "...",
  "app_id": "org.libreoffice.LibreOffice.writer",
  "name": "LibreOffice Writer",
  "desktop_file": "/usr/share/applications/libreoffice-writer.desktop",
  "icon_digest": "...",
  "supports_files": true,
  "supports_uris": true
}
```

The Android shortcut never stores `Exec=` or any shell fragment.

## Seamless GUI strategy

### Phase 1: one Linux app per Android Activity using a dedicated guest surface

For the first functional version, the host starts the requested app in its own guest graphical session and exports that session into one Android Activity.

This is simpler and safer than implementing a full multi-window compositor immediately.

Expected path:

```
LinuxAppLaunchActivity
  -> request session
  -> guest agent starts app under dedicated compositor/session
  -> crosvm exporter exposes that surface
  -> Android Activity owns display + input
```

A single app window may occupy the whole Android Activity initially. This already provides the important UX: an Android launcher icon opens one Linux app directly.

### Phase 2: true seamless multi-window bridge

Long-term target:

- one persistent Linux graphical session;
- guest Wayland windows discovered individually;
- each toplevel mapped to its own Android Activity/task;
- Android task close maps to Wayland close;
- resize/orientation maps to configure;
- Android IME maps to text-input;
- clipboard is bidirectional;
- drag/drop and file-open are bridged.

This requires a custom Wayland bridge rather than treating the whole guest framebuffer as one desktop.

## Why not use only VNC/RDP

VNC/RDP are useful fallbacks, but the final architecture should not make the entire Linux desktop one remote framebuffer because that prevents:

- native Android task switching per Linux app;
- correct Android window lifecycle;
- per-app icons;
- per-app file handling;
- app-specific resume/wake policy.

Remote desktop remains a compatibility fallback.

## VM lifecycle policy

The subsystem should behave more like WSL than a manually managed VM.

States:

- STOPPED
- BOOTING
- READY
- IDLE
- SUSPENDED
- FAILED

Shortcut launch behavior:

1. if READY: launch app immediately;
2. if SUSPENDED: resume then launch;
3. if STOPPED: boot, wait for guest agent readiness, launch;
4. if BOOTING: queue request;
5. if FAILED: show recoverable error, never loop-restart.

The VM is shared by all Linux apps in the same distribution.

## File integration

Android -> Linux:

- ACTION_VIEW
- ACTION_SEND
- Storage Access Framework URIs

The host stages or exposes the file through a controlled shared-file service and passes a guest-visible path/URI to the selected Linux app.

Linux -> Android:

- guest agent asks host to open a URL;
- guest agent asks host to share/export a file;
- save dialogs can optionally expose Android document providers later.

## Clipboard and input

Required:

- text clipboard bidirectional;
- Android IME;
- hardware keyboard;
- mouse;
- touch -> pointer mapping;
- back button -> configurable close/back behavior.

Later:

- image clipboard;
- drag and drop;
- stylus;
- high precision pointer lock.

## Security model

Never expose a generic host->guest shell RPC to launcher shortcuts.

Allowed launch message:

```
LaunchApp(vm_id, app_id, files[], uris[], activation_token)
```

Guest agent resolves `app_id` against its own discovered desktop-entry registry.

The privileged daemon accepts VM lifecycle operations only from the DroidTerminal app UID / authenticated Binder caller.

## PJZ110 default host preset

For the current device family:

- backend: crosvm
- hypervisor: Gunyah
- protection: pseudo-unprotected
- hugepages: enabled
- lend-mTHP: chunked
- RNG: enabled
- network: enabled
- GPU preferred: GfxStream + Turnip
- GPU fallback 1: Venus + Turnip
- GPU fallback 2: DRM2KGSL
- display fallback: SimpleFB
- PMU: off by default
- VPU: off by default
- SMT: off by default until separately justified

## Milestones

### M1 — Terminal shell
- rename/reframe app as DroidTerminal;
- terminal-first UI;
- reuse current DroidVM engine and daemon;
- one default Linux distribution;
- VM auto-boot/wake policy.

### M2 — Guest agent
- vsock RPC;
- app discovery;
- app launch;
- icons and desktop metadata;
- process lifecycle.

### M3 — Android desktop publishing
- pinned shortcuts;
- app icon sync;
- shortcut update/remove;
- Linux app launcher Activity.

### M4 — Single-app GUI
- one Linux application per Android Activity;
- keyboard/touch;
- clipboard;
- resize/orientation;
- file open.

### M5 — Seamless multi-window
- Wayland toplevel discovery;
- one Android task per Linux window;
- child/dialog ownership;
- close/minimize/restore mapping.

### M6 — System Edition
- privileged daemon packaging;
- system integration;
- Developer Options Linux entry handoff;
- optional replacement of the stock Android Terminal package only after independent-package validation.

## Non-goals for the first release

- replacing ColorOS launcher;
- spoofing OEM platform signatures;
- modifying empty QVirt guest partitions;
- raw protected-without-firmware probing;
- exposing arbitrary root shell RPC to launcher shortcuts.

## Immediate implementation order

1. keep DroidVM engine intact;
2. add `droidbridge-agent` protocol and app catalog;
3. add Android `LinuxAppRegistry`;
4. add shortcut publisher;
5. add `LinuxAppLaunchActivity`;
6. launch one GUI app into a dedicated Activity;
7. only then replace/re-skin the existing DroidVM management UI.
