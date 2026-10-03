# DroidBridge host/guest protocol v0

Transport: AF_VSOCK.

This protocol is intentionally capability-oriented and does not expose arbitrary shell execution.

## Handshake

Host -> guest:

```json
{"op":"hello","version":0,"host":"droidterminal"}
```

Guest -> host:

```json
{
  "op":"hello",
  "version":0,
  "agent":"droidbridge-agent",
  "capabilities":[
    "apps.list",
    "apps.launch",
    "apps.icons",
    "windows.events",
    "clipboard.text"
  ]
}
```

## List applications

Request:

```json
{"id":1,"op":"apps.list"}
```

Response:

```json
{
  "id":1,
  "ok":true,
  "apps":[
    {
      "app_id":"org.libreoffice.LibreOffice.writer",
      "name":"LibreOffice Writer",
      "generic_name":"Word Processor",
      "icon_key":"libreoffice-writer",
      "terminal":false,
      "supports_files":true,
      "supports_uris":false
    }
  ]
}
```

No `Exec=` value is sent to Android.

## Fetch icon

```json
{"id":2,"op":"apps.icon","app_id":"org.libreoffice.LibreOffice.writer","size":192}
```

Response metadata precedes a bounded PNG/WebP payload.

## Launch application

```json
{
  "id":3,
  "op":"apps.launch",
  "app_id":"org.libreoffice.LibreOffice.writer",
  "files":[],
  "uris":[],
  "display_session":"auto"
}
```

Response:

```json
{
  "id":3,
  "ok":true,
  "launch_id":"2a9c...",
  "pid":1234
}
```

The guest agent resolves the app ID against its current desktop-entry database and performs field-code expansion internally.

## Events

```json
{"op":"event.app.started","launch_id":"2a9c...","pid":1234}
{"op":"event.window.created","launch_id":"2a9c...","window_id":"w1","title":"Document 1"}
{"op":"event.window.closed","window_id":"w1"}
{"op":"event.app.exited","launch_id":"2a9c...","exit_code":0}
```

## Clipboard text

Host -> guest:

```json
{"op":"clipboard.set","mime":"text/plain;charset=utf-8","text":"..."}
```

Guest -> host event uses the same shape.

## Failure model

Every request with `id` receives exactly one response:

```json
{"id":3,"ok":false,"error":"APP_NOT_FOUND","message":"..."}
```

Initial stable errors:

- VERSION_MISMATCH
- APP_NOT_FOUND
- APP_DISABLED
- VM_NOT_READY
- GUI_UNAVAILABLE
- INVALID_ARGUMENT
- INTERNAL

## Framing

v0 uses:

- 4-byte big-endian unsigned payload length;
- UTF-8 JSON payload;
- maximum JSON payload: 1 MiB;
- binary blobs use a JSON metadata frame followed by a length-bounded binary frame.

No message may contain an arbitrary host command to execute.
