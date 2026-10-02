// SPDX-License-Identifier: GPL-3.0-or-later

use serde::Serialize;
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};
use std::env;
use std::ffi::OsString;
use std::fs::{self, File, OpenOptions};
use std::io::{self, BufRead, BufReader, Read, Write};
use std::mem;
use std::os::fd::{AsRawFd, FromRawFd};
use std::os::unix::fs::{FileTypeExt, MetadataExt};
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

const DEFAULT_PORT: u32 = 4050;
const DEFAULT_SERIAL: &str = "/dev/ttyS3";
const VMADDR_CID_ANY: u32 = 0xffff_ffff;
const MAX_FRAME: usize = 1024 * 1024;
const MAX_ARGS: usize = 256;
const MAX_ARG_LEN: usize = 64 * 1024;

#[repr(C)]
struct SockAddrVm {
    family: libc::sa_family_t,
    reserved1: u16,
    port: u32,
    cid: u32,
    zero: [u8; 4],
}

#[derive(Clone, Debug)]
struct DesktopApp {
    app_id: String,
    name: String,
    generic_name: String,
    icon_key: String,
    terminal: bool,
    desktop_file: PathBuf,
    exec: String,
    supports_files: bool,
    supports_uris: bool,
}

#[derive(Serialize)]
struct PublicApp<'a> {
    app_id: &'a str,
    name: &'a str,
    generic_name: &'a str,
    icon_key: &'a str,
    terminal: bool,
    supports_files: bool,
    supports_uris: bool,
}

fn main() -> io::Result<()> {
    let port = env::var("DROIDBRIDGE_PORT")
        .ok()
        .and_then(|s| s.parse::<u32>().ok())
        .unwrap_or(DEFAULT_PORT);

    match bind_vsock(port) {
        Ok(listener) => {
            eprintln!("droidbridge-agent: listening on vsock port {port}");
            serve_vsock(listener)
        }
        Err(vsock_error) => {
            let serial = env::var("DROIDBRIDGE_SERIAL")
                .unwrap_or_else(|_| DEFAULT_SERIAL.to_string());
            eprintln!(
                "droidbridge-agent: vsock unavailable ({vsock_error}); using serial {serial}"
            );
            serve_serial_path(&serial)
        }
    }
}

fn serve_vsock(listener: libc::c_int) -> io::Result<()> {
    loop {
        let fd = unsafe {
            libc::accept4(
                listener,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                libc::SOCK_CLOEXEC,
            )
        };
        if fd < 0 {
            let e = io::Error::last_os_error();
            if e.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(e);
        }
        std::thread::spawn(move || {
            let mut stream = unsafe { File::from_raw_fd(fd) };
            if let Err(e) = serve_client(&mut stream) {
                eprintln!("droidbridge-agent: client error: {e}");
            }
        });
    }
}

fn serve_serial_path(path: &str) -> io::Result<()> {
    let mut stream = OpenOptions::new().read(true).write(true).open(path)?;
    configure_serial_raw(stream.as_raw_fd())?;
    serve_serial(&mut stream)
}

fn configure_serial_raw(fd: libc::c_int) -> io::Result<()> {
    let mut termios: libc::termios = unsafe { mem::zeroed() };
    if unsafe { libc::tcgetattr(fd, &mut termios) } != 0 {
        return Err(io::Error::last_os_error());
    }
    unsafe { libc::cfmakeraw(&mut termios) };
    termios.c_cflag |= libc::CLOCAL | libc::CREAD;
    if unsafe { libc::tcsetattr(fd, libc::TCSANOW, &termios) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

fn serve_serial(stream: &mut File) -> io::Result<()> {
    let mut line = Vec::<u8>::new();
    let mut byte = [0u8; 1];
    loop {
        let n = stream.read(&mut byte)?;
        if n == 0 {
            return Ok(());
        }
        match byte[0] {
            b'\n' => {
                if line.is_empty() {
                    continue;
                }
                let request: Value = serde_json::from_slice(&line)
                    .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
                let response = handle_request(request);
                let payload = serde_json::to_vec(&response)
                    .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
                if payload.len() > MAX_FRAME {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "serial response too large",
                    ));
                }
                stream.write_all(&payload)?;
                stream.write_all(b"\n")?;
                stream.flush()?;
                line.clear();
            }
            b'\r' => {}
            value => {
                if line.len() >= MAX_FRAME {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "serial request too large",
                    ));
                }
                line.push(value);
            }
        }
    }
}

fn bind_vsock(port: u32) -> io::Result<libc::c_int> {
    let fd = unsafe { libc::socket(libc::AF_VSOCK, libc::SOCK_STREAM | libc::SOCK_CLOEXEC, 0) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }

    let addr = SockAddrVm {
        family: libc::AF_VSOCK as libc::sa_family_t,
        reserved1: 0,
        port,
        cid: VMADDR_CID_ANY,
        zero: [0; 4],
    };

    let rc = unsafe {
        libc::bind(
            fd,
            &addr as *const SockAddrVm as *const libc::sockaddr,
            mem::size_of::<SockAddrVm>() as libc::socklen_t,
        )
    };
    if rc < 0 {
        let e = io::Error::last_os_error();
        unsafe { libc::close(fd) };
        return Err(e);
    }
    if unsafe { libc::listen(fd, 16) } < 0 {
        let e = io::Error::last_os_error();
        unsafe { libc::close(fd) };
        return Err(e);
    }
    Ok(fd)
}

fn serve_client(stream: &mut File) -> io::Result<()> {
    loop {
        let frame = match read_frame(stream)? {
            Some(v) => v,
            None => return Ok(()),
        };
        let response = handle_request(frame);
        write_frame(stream, &response)?;
    }
}

fn read_frame(stream: &mut File) -> io::Result<Option<Value>> {
    let mut len = [0u8; 4];
    match stream.read_exact(&mut len) {
        Ok(()) => {}
        Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e),
    }
    let n = u32::from_be_bytes(len) as usize;
    if n == 0 || n > MAX_FRAME {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid frame length"));
    }
    let mut payload = vec![0u8; n];
    stream.read_exact(&mut payload)?;
    serde_json::from_slice(&payload)
        .map(Some)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))
}

fn write_frame(stream: &mut File, value: &Value) -> io::Result<()> {
    let payload = serde_json::to_vec(value)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    if payload.len() > MAX_FRAME {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "response too large"));
    }
    stream.write_all(&(payload.len() as u32).to_be_bytes())?;
    stream.write_all(&payload)?;
    stream.flush()
}

fn handle_request(req: Value) -> Value {
    let id = req.get("id").cloned();
    let op = req.get("op").and_then(Value::as_str).unwrap_or("");
    let result = match op {
        "hello" => Ok(json!({
            "op": "hello",
            "version": 0,
            "agent": "droidbridge-agent",
            "capabilities": [
                "apps.list",
                "apps.launch"
            ]
        })),
        "apps.list" => match scan_apps() {
            Ok(apps) => {
                let public: Vec<PublicApp<'_>> = apps.values().map(|a| PublicApp {
                    app_id: &a.app_id,
                    name: &a.name,
                    generic_name: &a.generic_name,
                    icon_key: &a.icon_key,
                    terminal: a.terminal,
                    supports_files: a.supports_files,
                    supports_uris: a.supports_uris,
                }).collect();
                Ok(json!({ "apps": public }))
            }
            Err(e) => Err(("INTERNAL", e.to_string())),
        },
        "apps.launch" => launch_request(&req),
        _ => Err(("INVALID_ARGUMENT", format!("unknown operation: {op}"))),
    };

    match result {
        Ok(mut body) => {
            if let Some(obj) = body.as_object_mut() {
                obj.insert("ok".into(), Value::Bool(true));
                if let Some(id) = id {
                    obj.insert("id".into(), id);
                }
            }
            body
        }
        Err((code, message)) => {
            let mut out = json!({ "ok": false, "error": code, "message": message });
            if let (Some(id), Some(obj)) = (id, out.as_object_mut()) {
                obj.insert("id".into(), id);
            }
            out
        }
    }
}

fn scan_apps() -> io::Result<BTreeMap<String, DesktopApp>> {
    let mut out = BTreeMap::new();
    for root in application_roots() {
        if !root.is_dir() {
            continue;
        }
        visit_desktop_dir(&root, &root, &mut out)?;
    }
    Ok(out)
}

fn application_roots() -> Vec<PathBuf> {
    // Lower-priority directories first; per-user entries overwrite system entries with the same
    // desktop id. The agent is a system service, so HOME alone normally points at /root and would
    // otherwise miss applications installed by the actual desktop user.
    let mut roots = BTreeSet::new();
    roots.insert(PathBuf::from("/usr/share/applications"));
    roots.insert(PathBuf::from("/usr/local/share/applications"));

    if let Some(home) = env::var_os("HOME") {
        roots.insert(PathBuf::from(home).join(".local/share/applications"));
    }

    if let Ok(passwd) = fs::read_to_string("/etc/passwd") {
        for line in passwd.lines() {
            let fields = line.split(':').collect::<Vec<_>>();
            if fields.len() < 7 {
                continue;
            }
            let uid = match fields[2].parse::<u32>() {
                Ok(uid) => uid,
                Err(_) => continue,
            };
            if uid < 1000 || uid == 65534 {
                continue;
            }
            let home = Path::new(fields[5]);
            if home.is_absolute() && home != Path::new("/") {
                roots.insert(home.join(".local/share/applications"));
            }
        }
    }

    roots.into_iter().collect()
}

fn visit_desktop_dir(
    root: &Path,
    dir: &Path,
    out: &mut BTreeMap<String, DesktopApp>,
) -> io::Result<()> {
    for entry in fs::read_dir(dir)? {
        let entry = match entry {
            Ok(v) => v,
            Err(_) => continue,
        };
        let path = entry.path();
        let ty = match entry.file_type() {
            Ok(v) => v,
            Err(_) => continue,
        };
        if ty.is_dir() {
            let _ = visit_desktop_dir(root, &path, out);
            continue;
        }
        if path.extension().and_then(|s| s.to_str()) != Some("desktop") {
            continue;
        }
        if let Ok(Some(app)) = parse_desktop(root, &path) {
            out.insert(app.app_id.clone(), app);
        }
    }
    Ok(())
}

fn parse_desktop(root: &Path, path: &Path) -> io::Result<Option<DesktopApp>> {
    let file = File::open(path)?;
    let reader = BufReader::new(file);
    let mut in_entry = false;
    let mut kv = BTreeMap::<String, String>::new();

    for line in reader.lines() {
        let line = line?;
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if line.starts_with('[') && line.ends_with(']') {
            in_entry = line == "[Desktop Entry]";
            continue;
        }
        if !in_entry {
            continue;
        }
        if let Some((k, v)) = line.split_once('=') {
            kv.entry(k.trim().to_string()).or_insert_with(|| v.to_string());
        }
    }

    if kv.get("Type").map(String::as_str) != Some("Application")
        || parse_bool(kv.get("Hidden"))
        || parse_bool(kv.get("NoDisplay"))
    {
        return Ok(None);
    }

    let exec = match kv.get("Exec") {
        Some(v) if !v.trim().is_empty() => v.trim().to_string(),
        _ => return Ok(None),
    };
    let name = kv.get("Name").cloned().unwrap_or_else(|| desktop_id(root, path));
    let app_id = desktop_id(root, path);
    let supports_files = exec.contains("%f") || exec.contains("%F");
    let supports_uris = exec.contains("%u") || exec.contains("%U");

    Ok(Some(DesktopApp {
        app_id,
        name,
        generic_name: kv.get("GenericName").cloned().unwrap_or_default(),
        icon_key: kv.get("Icon").cloned().unwrap_or_default(),
        terminal: parse_bool(kv.get("Terminal")),
        desktop_file: path.to_path_buf(),
        exec,
        supports_files,
        supports_uris,
    }))
}

fn desktop_id(root: &Path, path: &Path) -> String {
    let rel = path.strip_prefix(root).unwrap_or(path);
    let mut id = rel.to_string_lossy().replace('/', "-");
    if id.ends_with(".desktop") {
        id.truncate(id.len() - ".desktop".len());
    }
    id
}

fn parse_bool(v: Option<&String>) -> bool {
    matches!(v.map(|s| s.as_str()), Some("true") | Some("True") | Some("TRUE") | Some("1"))
}

#[derive(Clone, Debug)]
struct GraphicalSession {
    backend: &'static str,
    uid: u32,
    gid: u32,
    home: Option<String>,
    user: Option<String>,
    vars: Vec<(OsString, OsString)>,
}

fn graphical_session() -> Option<GraphicalSession> {
    if let Some(session) = inherited_wayland_session() {
        return Some(session);
    }
    discover_wayland_session()
}

fn inherited_wayland_session() -> Option<GraphicalSession> {
    let display = env::var("WAYLAND_DISPLAY").ok()?;
    if display.is_empty() {
        return None;
    }
    let runtime = env::var("XDG_RUNTIME_DIR").ok()?;
    let socket = if Path::new(&display).is_absolute() {
        PathBuf::from(&display)
    } else {
        PathBuf::from(&runtime).join(&display)
    };
    let meta = fs::metadata(&socket).ok()?;
    if !meta.file_type().is_socket() {
        return None;
    }
    Some(session_for_wayland(meta.uid(), meta.gid(), runtime, display))
}

fn discover_wayland_session() -> Option<GraphicalSession> {
    let root = Path::new("/run/user");
    let mut user_dirs = fs::read_dir(root).ok()?
        .filter_map(Result::ok)
        .filter_map(|entry| {
            let uid = entry.file_name().to_string_lossy().parse::<u32>().ok()?;
            Some((uid, entry.path()))
        })
        .collect::<Vec<_>>();
    // Prefer ordinary users over root, then stable uid order.
    user_dirs.sort_by_key(|(uid, _)| (*uid == 0, *uid));

    for (uid, dir) in user_dirs {
        let mut sockets = match fs::read_dir(&dir) {
            Ok(entries) => entries
                .filter_map(Result::ok)
                .filter(|entry| {
                    let name = entry.file_name();
                    let name = name.to_string_lossy();
                    name.starts_with("wayland-") && !name.ends_with(".lock")
                })
                .collect::<Vec<_>>(),
            Err(_) => continue,
        };
        sockets.sort_by_key(|entry| entry.file_name());
        for entry in sockets {
            let meta = match entry.metadata() {
                Ok(meta) if meta.file_type().is_socket() => meta,
                _ => continue,
            };
            let display = entry.file_name().to_string_lossy().to_string();
            return Some(session_for_wayland(
                uid,
                meta.gid(),
                dir.to_string_lossy().to_string(),
                display,
            ));
        }
    }
    None
}

fn session_for_wayland(
    uid: u32,
    fallback_gid: u32,
    runtime: String,
    display: String,
) -> GraphicalSession {
    let identity = passwd_identity(uid);
    let gid = identity.as_ref().map(|v| v.1).unwrap_or(fallback_gid);
    let user = identity.as_ref().map(|v| v.0.clone());
    let home = identity.as_ref().map(|v| v.2.clone());
    let mut vars = vec![
        (OsString::from("XDG_RUNTIME_DIR"), OsString::from(&runtime)),
        (OsString::from("WAYLAND_DISPLAY"), OsString::from(&display)),
    ];
    let bus = PathBuf::from(&runtime).join("bus");
    if bus.exists() {
        vars.push((
            OsString::from("DBUS_SESSION_BUS_ADDRESS"),
            OsString::from(format!("unix:path={}", bus.to_string_lossy())),
        ));
    }
    GraphicalSession {
        backend: "wayland",
        uid,
        gid,
        home,
        user,
        vars,
    }
}

fn passwd_identity(uid: u32) -> Option<(String, u32, String)> {
    let text = fs::read_to_string("/etc/passwd").ok()?;
    for line in text.lines() {
        let fields = line.split(':').collect::<Vec<_>>();
        if fields.len() < 7 {
            continue;
        }
        let parsed_uid = match fields[2].parse::<u32>() {
            Ok(value) => value,
            Err(_) => continue,
        };
        if parsed_uid != uid {
            continue;
        }
        let gid = match fields[3].parse::<u32>() {
            Ok(value) => value,
            Err(_) => continue,
        };
        return Some((fields[0].to_string(), gid, fields[5].to_string()));
    }
    None
}

fn apply_graphical_session(cmd: &mut Command, session: &GraphicalSession) {
    for (key, value) in &session.vars {
        cmd.env(key, value);
    }
    if let Some(home) = &session.home {
        cmd.env("HOME", home);
    }
    if let Some(user) = &session.user {
        cmd.env("USER", user);
        cmd.env("LOGNAME", user);
    }
    if unsafe { libc::geteuid() } == 0 {
        cmd.uid(session.uid);
        cmd.gid(session.gid);
    }
}

fn launch_request(req: &Value) -> Result<Value, (&'static str, String)> {
    let app_id = req.get("app_id")
        .and_then(Value::as_str)
        .ok_or(("INVALID_ARGUMENT", "app_id is required".into()))?;
    if !valid_app_id(app_id) {
        return Err(("INVALID_ARGUMENT", "invalid app_id".into()));
    }

    let apps = scan_apps().map_err(|e| ("INTERNAL", e.to_string()))?;
    let app = apps.get(app_id)
        .ok_or(("APP_NOT_FOUND", "desktop entry not found".into()))?;

    let files = string_array(req.get("files"))?;
    let uris = string_array(req.get("uris"))?;
    let argv = expand_exec(app, &files, &uris)?;
    if argv.is_empty() {
        return Err(("APP_DISABLED", "desktop entry has no executable".into()));
    }

    let mut cmd = Command::new(&argv[0]);
    cmd.args(&argv[1..])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());

    let session = if app.terminal {
        None
    } else {
        let session = graphical_session().ok_or((
            "GUI_UNAVAILABLE",
            "no running Wayland session was discovered".to_string(),
        ))?;
        apply_graphical_session(&mut cmd, &session);
        Some(session)
    };

    let child = cmd.spawn().map_err(|e| ("APP_DISABLED", e.to_string()))?;
    let launch_id = format!("{}-{}", child.id(), monotonic_hint());
    Ok(json!({
        "launch_id": launch_id,
        "pid": child.id(),
        "display_backend": session.as_ref().map(|v| v.backend).unwrap_or("terminal")
    }))
}

fn string_array(value: Option<&Value>) -> Result<Vec<String>, (&'static str, String)> {
    let Some(value) = value else { return Ok(Vec::new()) };
    let Some(arr) = value.as_array() else {
        return Err(("INVALID_ARGUMENT", "files/uris must be arrays".into()));
    };
    if arr.len() > 64 {
        return Err(("INVALID_ARGUMENT", "too many files/uris".into()));
    }
    let mut out = Vec::with_capacity(arr.len());
    for v in arr {
        let Some(s) = v.as_str() else {
            return Err(("INVALID_ARGUMENT", "files/uris must contain strings".into()));
        };
        if s.len() > MAX_ARG_LEN {
            return Err(("INVALID_ARGUMENT", "file/uri is too long".into()));
        }
        out.push(s.to_string());
    }
    Ok(out)
}

fn expand_exec(
    app: &DesktopApp,
    files: &[String],
    uris: &[String],
) -> Result<Vec<OsString>, (&'static str, String)> {
    let tokens = split_exec(&app.exec)
        .map_err(|e| ("APP_DISABLED", e))?;
    let mut out: Vec<OsString> = Vec::new();

    for token in tokens {
        match token.as_str() {
            "%f" => {
                if let Some(v) = files.first() { out.push(v.into()); }
            }
            "%F" => out.extend(files.iter().map(OsString::from)),
            "%u" => {
                if let Some(v) = uris.first() { out.push(v.into()); }
            }
            "%U" => out.extend(uris.iter().map(OsString::from)),
            "%i" => {
                if !app.icon_key.is_empty() {
                    out.push("--icon".into());
                    out.push(app.icon_key.clone().into());
                }
            }
            _ => {
                let expanded = token
                    .replace("%%", "%")
                    .replace("%c", &app.name)
                    .replace("%k", &app.desktop_file.to_string_lossy());
                // Unknown field codes are intentionally rejected instead of guessed.
                if contains_field_code(&expanded) {
                    return Err(("APP_DISABLED", format!("unsupported Exec field code in {expanded}")));
                }
                if !expanded.is_empty() {
                    out.push(expanded.into());
                }
            }
        }
        if out.len() > MAX_ARGS {
            return Err(("INVALID_ARGUMENT", "expanded argument list is too large".into()));
        }
    }
    Ok(out)
}

fn contains_field_code(s: &str) -> bool {
    let bytes = s.as_bytes();
    for i in 0..bytes.len().saturating_sub(1) {
        if bytes[i] == b'%' && bytes[i + 1].is_ascii_alphabetic() {
            return true;
        }
    }
    false
}

/// Minimal Exec= tokenizer: no shell, no substitutions, only quoting/backslash grouping.
fn split_exec(input: &str) -> Result<Vec<String>, String> {
    let mut out = Vec::new();
    let mut cur = String::new();
    let mut chars = input.chars().peekable();
    let mut quote: Option<char> = None;

    while let Some(ch) = chars.next() {
        match (quote, ch) {
            (None, '\'') | (None, '"') => quote = Some(ch),
            (Some(q), c) if c == q => quote = None,
            (_, '\\') => {
                let next = chars.next().ok_or_else(|| "trailing backslash in Exec".to_string())?;
                cur.push(next);
            }
            (None, c) if c.is_whitespace() => {
                if !cur.is_empty() {
                    out.push(mem::take(&mut cur));
                }
            }
            (_, c) => cur.push(c),
        }
    }
    if quote.is_some() {
        return Err("unterminated quote in Exec".into());
    }
    if !cur.is_empty() {
        out.push(cur);
    }
    Ok(out)
}

fn valid_app_id(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 255
        && s.bytes().all(|b| b.is_ascii_alphanumeric() || b"._:+@-".contains(&b))
}

fn monotonic_hint() -> u128 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tokenizes_without_shell() {
        assert_eq!(
            split_exec("writer --title \"hello world\" %F").unwrap(),
            vec!["writer", "--title", "hello world", "%F"]
        );
    }

    #[test]
    fn rejects_unterminated_quote() {
        assert!(split_exec("writer \"oops").is_err());
    }

    #[test]
    fn app_id_is_narrow() {
        assert!(valid_app_id("org.example.App"));
        assert!(!valid_app_id("org.example.App;rm"));
    }

    #[test]
    fn passwd_identity_parser_has_root_when_present() {
        if Path::new("/etc/passwd").exists() {
            let root = passwd_identity(0);
            assert!(root.is_some());
        }
    }

}
