use jni::JNIEnv;
use jni::objects::{JClass, JString, JObject, GlobalRef};
use jni::sys::{jint, jstring};
use jni::JavaVM;

use arti_client::TorClient;
use arti_client::config::TorClientConfigBuilder;
use tor_rtcompat::PreferredRuntime;

use std::sync::{Arc, Mutex};
use std::path::PathBuf;
use std::time::Duration;
use anyhow::Result;

// ============================================================================
// Global State
// ============================================================================

/// Everything one start owns. Stopping drops all of it: no client, no runtime, no task.
struct Session {
    runtime: tokio::runtime::Runtime,
    client: Arc<TorClient<PreferredRuntime>>,
    socks_task: Option<tokio::task::JoinHandle<()>>,
}

/// The current session, if any. A new start or a stop always replaces it.
static SESSION: lifecycle::Lifecycle<Session> = lifecycle::Lifecycle::new();

/// Global JavaVM reference (cached on first JNI call)
static JAVA_VM: Mutex<Option<JavaVM>> = Mutex::new(None);

/// Global log callback reference
static LOG_CALLBACK: Mutex<Option<GlobalRef>> = Mutex::new(None);

/// How long a stop waits for the runtime's worker and blocking threads.
const SHUTDOWN_TIMEOUT: Duration = Duration::from_secs(3);

/// How often an in-progress bootstrap checks whether a stop cancelled it.
const CANCEL_POLL: Duration = Duration::from_millis(50);

/// Aborts the SOCKS task, drops the client inside its runtime, then shuts the runtime down,
/// which ends every Arti background task (circuits, guards, directory fetches).
fn teardown(session: Session) {
    let Session { runtime, client, socks_task } = session;
    if let Some(task) = socks_task {
        task.abort();
    }
    {
        let _context = runtime.enter();
        drop(client);
    }
    runtime.shutdown_timeout(SHUTDOWN_TIMEOUT);
}

// ============================================================================
// Logging Integration
// ============================================================================

/// Send log message to Java callback
fn send_log_to_java(message: String) {
    let vm_opt = JAVA_VM.lock().unwrap();
    let callback_opt = LOG_CALLBACK.lock().unwrap();

    if let (Some(vm), Some(callback)) = (vm_opt.as_ref(), callback_opt.as_ref()) {
        if let Ok(mut env) = vm.attach_current_thread() {
            if let Ok(jmessage) = env.new_string(&message) {
                let _ = env.call_method(
                    callback.as_obj(),
                    "onLogLine",
                    "(Ljava/lang/String;)V",
                    &[(&jmessage).into()]
                );
            }
        }
    }
}

/// Macro for logging to both Android logcat and Java callback
macro_rules! log_info {
    ($($arg:tt)*) => {{
        let msg = format!($($arg)*);
        android_logger::log(&format!("Arti: {}", msg));
        send_log_to_java(msg);
    }};
}

macro_rules! log_error {
    ($($arg:tt)*) => {{
        let msg = format!("ERROR: {}", format!($($arg)*));
        android_logger::log(&format!("Arti: {}", msg));
        send_log_to_java(msg);
    }};
}

// ============================================================================
// JNI Functions
// ============================================================================

/// Get Arti version string
#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_getVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    // Cache JavaVM on first call
    if JAVA_VM.lock().unwrap().is_none() {
        if let Ok(vm) = env.get_java_vm() {
            *JAVA_VM.lock().unwrap() = Some(vm);
        }
    }

    let version = format!("Arti {} (custom build with rustls)", env!("CARGO_PKG_VERSION"));
    let output = env.new_string(version).expect("Couldn't create java string!");
    output.into_raw()
}

/// Set log callback for Arti logs
#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_setLogCallback(
    env: JNIEnv,
    _class: JClass,
    callback: JObject,
) {
    // Cache JavaVM if not already cached
    if JAVA_VM.lock().unwrap().is_none() {
        if let Ok(vm) = env.get_java_vm() {
            *JAVA_VM.lock().unwrap() = Some(vm);
        }
    }

    // Store global reference to callback
    if let Ok(global_ref) = env.new_global_ref(callback) {
        *LOG_CALLBACK.lock().unwrap() = Some(global_ref);
        log_info!("Log callback registered");
    }
}

/// Initialize Arti: a fresh runtime and a freshly bootstrapped client on every call.
///
/// Returns 0 on success, -4 when a concurrent stop() cancelled the bootstrap.
#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_initialize(
    mut env: JNIEnv,
    _class: JClass,
    data_dir: JString,
) -> jint {
    // Cache JavaVM if not already cached
    if JAVA_VM.lock().unwrap().is_none() {
        if let Ok(vm) = env.get_java_vm() {
            *JAVA_VM.lock().unwrap() = Some(vm);
        }
    }

    let data_dir_str: String = match env.get_string(&data_dir) {
        Ok(s) => s.into(),
        Err(e) => {
            log_error!("Failed to convert data_dir: {:?}", e);
            return -1;
        }
    };

    log_info!("AMEx: state changed to Initialized");
    log_info!("Initializing Arti with data directory: {}", data_dir_str);

    // Waits out a stop in progress. A client left by an earlier start is released first:
    // a start never reuses guards or circuits from before.
    let (start, previous) = SESSION.begin_start();
    if let Some(session) = previous {
        log_info!("Releasing the previous Tor client");
        teardown(session);
    }

    let runtime = match tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
    {
        Ok(rt) => rt,
        Err(e) => {
            log_error!("Failed to create Tokio runtime: {:?}", e);
            return -2;
        }
    };

    // Create config with explicit Android paths
    let data_path = PathBuf::from(data_dir_str);
    let cache_dir = data_path.join("cache");
    let state_dir = data_path.join("state");

    // Create directories if they don't exist
    std::fs::create_dir_all(&cache_dir).ok();
    std::fs::create_dir_all(&state_dir).ok();

    let bootstrap = async {
        log_info!("Creating Arti client...");
        log_info!("Cache dir: {:?}", cache_dir);
        log_info!("State dir: {:?}", state_dir);

        // Create config with Android-specific directories
        let config = TorClientConfigBuilder::from_directories(state_dir, cache_dir)
            .build()?;

        // Create client with Android-specific config
        let client = TorClient::create_bootstrapped(config).await?;
        Ok::<_, anyhow::Error>(client)
    };

    // A stop() during bootstrap wins: the bootstrap future is dropped within CANCEL_POLL.
    let outcome = runtime.block_on(async {
        tokio::select! {
            result = bootstrap => Some(result),
            _ = wait_until_cancelled(&start) => None,
        }
    });

    // `start` stays held until the end of this function, so a waiting stop() returns only
    // once the runtime below is gone.
    match outcome {
        Some(Ok(client)) => {
            let session = Session { runtime, client: Arc::new(client), socks_task: None };
            match start.install(session) {
                Ok(()) => {
                    log_info!("Arti client created successfully");
                    log_info!("Arti initialized successfully");
                    0
                }
                Err(session) => {
                    log_info!("Arti stopped during bootstrap; client released");
                    teardown(session);
                    -4
                }
            }
        }
        Some(Err(e)) => {
            log_error!("Failed to initialize Arti: {:?}", e);
            runtime.shutdown_timeout(SHUTDOWN_TIMEOUT);
            -3
        }
        None => {
            log_info!("Arti bootstrap cancelled by stop");
            runtime.shutdown_timeout(SHUTDOWN_TIMEOUT);
            -4
        }
    }
}

async fn wait_until_cancelled(start: &lifecycle::Start<'_, Session>) {
    while !start.is_cancelled() {
        tokio::time::sleep(CANCEL_POLL).await;
    }
}

/// Start SOCKS proxy on specified port
#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_startSocksProxy(
    _env: JNIEnv,
    _class: JClass,
    port: jint,
) -> jint {
    log_info!("AMEx: state changed to Starting");
    log_info!("Starting SOCKS proxy on port {}", port);

    // Try to bind IMMEDIATELY to detect port conflicts before returning
    let addr = format!("127.0.0.1:{}", port);

    let started = SESSION.with_current(|session| {
        // Stop any existing SOCKS server first
        if let Some(handle) = session.socks_task.take() {
            log_info!("Aborting previous SOCKS server task");
            handle.abort();
        }

        let listener = match session.runtime.block_on(tokio::net::TcpListener::bind(&addr)) {
            Ok(l) => {
                log_info!("SOCKS proxy bound to {}", addr);
                l
            }
            Err(e) => {
                log_error!("Failed to bind SOCKS proxy to {}: {:?}", addr, e);
                return -3;
            }
        };

        let client = Arc::clone(&session.client);
        let addr = addr.clone();
        // Now spawn the background task with the already-bound listener
        let handle = session.runtime.spawn(async move {
            log_info!("SOCKS proxy listening on {}", addr);
            log_info!("Sufficiently bootstrapped; system SOCKS now functional");

            // Signal bootstrap completion to bitchat-android (expected by ArtiTorManager)
            // This sets bootstrapPercent to 100% and stops inactivity restarts
            tokio::time::sleep(tokio::time::Duration::from_millis(500)).await;
            log_info!("We have found that guard [scrubbed] is usable.");

            // Accept connections
            loop {
                match listener.accept().await {
                    Ok((stream, peer_addr)) => {
                        log_info!("SOCKS connection from: {}", peer_addr);
                        let client_clone = Arc::clone(&client);

                        tokio::spawn(async move {
                            if let Err(e) = handle_socks_connection(stream, client_clone).await {
                                log_error!("SOCKS connection error: {:?}", e);
                            }
                        });
                    }
                    Err(e) => {
                        log_error!("Failed to accept SOCKS connection: {:?}", e);
                        break; // Exit loop on error
                    }
                }
            }

            log_info!("SOCKS proxy task exiting");
        });

        // Store handle for cleanup
        session.socks_task = Some(handle);
        0
    });

    match started {
        Some(0) => {
            log_info!("SOCKS proxy started on port {}", port);
            0
        }
        Some(code) => code,
        None => {
            log_error!("Arti client not initialized - call initialize() first");
            -1
        }
    }
}

/// Handle a single SOCKS connection
async fn handle_socks_connection(
    mut stream: tokio::net::TcpStream,
    client: Arc<TorClient<PreferredRuntime>>,
) -> Result<()> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    // Simple SOCKS5 handshake
    let mut buf = [0u8; 512];

    // Read version + methods
    let n = stream.read(&mut buf).await?;
    if n < 2 {
        return Err(anyhow::anyhow!("Invalid SOCKS handshake"));
    }

    // Send "no auth required" response
    stream.write_all(&[0x05, 0x00]).await?;

    // Read request
    let n = stream.read(&mut buf).await?;
    if n < 10 {
        return Err(anyhow::anyhow!("Invalid SOCKS request"));
    }

    // Parse SOCKS5 request: VER(1) CMD(1) RSV(1) ATYP(1) DST.ADDR DST.PORT(2)
    let version = buf[0];
    let cmd = buf[1];
    let atyp = buf[3];

    if version != 0x05 {
        return Err(anyhow::anyhow!("Unsupported SOCKS version: {}", version));
    }

    if cmd != 0x01 {
        // Only support CONNECT command
        stream.write_all(&[0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
        return Err(anyhow::anyhow!("Unsupported SOCKS command: {}", cmd));
    }

    // Parse target address and port
    let (target_host, target_port) = match atyp {
        0x01 => {
            // IPv4: 4 bytes
            let ip = format!("{}.{}.{}.{}", buf[4], buf[5], buf[6], buf[7]);
            let port = u16::from_be_bytes([buf[8], buf[9]]);
            (ip, port)
        }
        0x03 => {
            // Domain name: length byte + domain
            let len = buf[4] as usize;
            if n < 5 + len + 2 {
                return Err(anyhow::anyhow!("Invalid domain name length"));
            }
            let domain = String::from_utf8_lossy(&buf[5..5 + len]).to_string();
            let port = u16::from_be_bytes([buf[5 + len], buf[5 + len + 1]]);
            (domain, port)
        }
        0x04 => {
            // IPv6: 16 bytes + 2 bytes port = 22 bytes total
            if n < 22 {
                stream.write_all(&[0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
                return Err(anyhow::anyhow!("Truncated IPv6 request"));
            }
            let ip = format!(
                "{:02x}{:02x}:{:02x}{:02x}:{:02x}{:02x}:{:02x}{:02x}:{:02x}{:02x}:{:02x}{:02x}:{:02x}{:02x}:{:02x}{:02x}",
                buf[4], buf[5], buf[6], buf[7], buf[8], buf[9], buf[10], buf[11],
                buf[12], buf[13], buf[14], buf[15], buf[16], buf[17], buf[18], buf[19]
            );
            let port = u16::from_be_bytes([buf[20], buf[21]]);
            (ip, port)
        }
        _ => {
            stream.write_all(&[0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
            return Err(anyhow::anyhow!("Unsupported address type: {}", atyp));
        }
    };

    log_info!("SOCKS5 CONNECT to {}:{}", target_host, target_port);

    // Establish Tor connection
    let tor_stream = match client.connect((target_host.as_str(), target_port)).await {
        Ok(s) => s,
        Err(e) => {
            log_error!("Failed to connect through Tor: {:?}", e);
            // Send SOCKS5 error: general failure
            stream.write_all(&[0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
            return Err(e.into());
        }
    };

    log_info!("Tor connection established to {}:{}", target_host, target_port);

    // Send SOCKS5 success response
    stream.write_all(&[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;

    // Bidirectional data forwarding
    let (mut client_read, mut client_write) = stream.split();
    let (mut tor_read, mut tor_write) = tor_stream.split();

    let client_to_tor = async {
        tokio::io::copy(&mut client_read, &mut tor_write).await
    };

    let tor_to_client = async {
        tokio::io::copy(&mut tor_read, &mut client_write).await
    };

    // Run both directions concurrently, exit when either completes
    tokio::select! {
        result = client_to_tor => {
            if let Err(ref e) = result {
                log_error!("Client->Tor copy error: {:?}", e);
            }
        }
        result = tor_to_client => {
            if let Err(ref e) = result {
                log_error!("Tor->Client copy error: {:?}", e);
            }
        }
    };

    log_info!("SOCKS connection closed for {}:{}", target_host, target_port);

    Ok(())
}

/// Stop Arti: cancels a bootstrap in progress, then drops the client and shuts its runtime
/// down. When this returns no Tor task is left, so the data directory can be deleted and the
/// next initialize() builds a new client with new guards.
#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_stop(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    log_info!("AMEx: state changed to Stopping");
    log_info!("Stopping Arti...");

    let mut stop = SESSION.begin_stop();
    if let Some(session) = stop.take() {
        log_info!("Releasing the Tor client");
        teardown(session);
    }
    drop(stop);

    log_info!("AMEx: state changed to Stopped");
    log_info!("Arti stopped successfully");

    0
}

// ============================================================================
// Android Logger (simple implementation)
// ============================================================================

mod android_logger {
    use std::ffi::CString;

    #[allow(non_camel_case_types)]
    type c_int = i32;

    #[allow(non_camel_case_types)]
    type c_char = i8;

    extern "C" {
        fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    }

    const ANDROID_LOG_INFO: c_int = 4;

    pub fn log(message: &str) {
        unsafe {
            let tag = CString::new("ArtiNative").unwrap();
            let text = CString::new(message).unwrap();
            __android_log_write(ANDROID_LOG_INFO, tag.as_ptr() as *const c_char, text.as_ptr() as *const c_char);
        }
    }
}

// ============================================================================
// Start/stop bookkeeping (std only: no Arti, Tokio or JNI, so it is testable alone)
// ============================================================================

mod lifecycle {
    use std::sync::{Condvar, Mutex, MutexGuard, PoisonError};

    struct Inner<R> {
        /// Bumped by every stop; a start that sees it change has been cancelled.
        generation: u64,
        /// A start or stop is in progress; the other side waits.
        busy: bool,
        current: Option<R>,
    }

    /// Holds at most one session. Starts and stops never overlap, and a stop cancels a start
    /// in progress instead of waiting for it to finish.
    pub struct Lifecycle<R> {
        inner: Mutex<Inner<R>>,
        changed: Condvar,
    }

    impl<R> Lifecycle<R> {
        pub const fn new() -> Self {
            Self {
                inner: Mutex::new(Inner { generation: 0, busy: false, current: None }),
                changed: Condvar::new(),
            }
        }

        fn lock(&self) -> MutexGuard<'_, Inner<R>> {
            self.inner.lock().unwrap_or_else(PoisonError::into_inner)
        }

        fn wait_idle<'a>(&'a self, mut inner: MutexGuard<'a, Inner<R>>) -> MutexGuard<'a, Inner<R>> {
            while inner.busy {
                inner = self.changed.wait(inner).unwrap_or_else(PoisonError::into_inner);
            }
            inner
        }

        fn release(&self) {
            self.lock().busy = false;
            self.changed.notify_all();
        }

        /// Waits out any other start or stop, then returns the previous session for teardown:
        /// every start builds a fresh one.
        pub fn begin_start(&self) -> (Start<'_, R>, Option<R>) {
            let mut inner = self.wait_idle(self.lock());
            inner.busy = true;
            let previous = inner.current.take();
            (Start { owner: self, generation: inner.generation }, previous)
        }

        /// Cancels a start in progress, waits for it to return, and takes the current session.
        pub fn begin_stop(&self) -> Stop<'_, R> {
            let mut inner = self.lock();
            inner.generation = inner.generation.wrapping_add(1);
            let mut inner = self.wait_idle(inner);
            inner.busy = true;
            let current = inner.current.take();
            Stop { owner: self, current }
        }

        /// Runs `f` on the current session; None when there is none.
        pub fn with_current<T>(&self, f: impl FnOnce(&mut R) -> T) -> Option<T> {
            self.lock().current.as_mut().map(f)
        }
    }

    /// A start in progress. Dropping it lets a waiting stop proceed.
    pub struct Start<'a, R> {
        owner: &'a Lifecycle<R>,
        generation: u64,
    }

    impl<R> Start<'_, R> {
        pub fn is_cancelled(&self) -> bool {
            self.owner.lock().generation != self.generation
        }

        /// Publishes the session unless a stop came in meanwhile; then it is handed back.
        pub fn install(&self, session: R) -> Result<(), R> {
            let mut inner = self.owner.lock();
            if inner.generation != self.generation {
                return Err(session);
            }
            inner.current = Some(session);
            Ok(())
        }
    }

    impl<R> Drop for Start<'_, R> {
        fn drop(&mut self) {
            self.owner.release();
        }
    }

    /// A stop in progress: holds the session it took until torn down.
    pub struct Stop<'a, R> {
        owner: &'a Lifecycle<R>,
        current: Option<R>,
    }

    impl<R> Stop<'_, R> {
        pub fn take(&mut self) -> Option<R> {
            self.current.take()
        }
    }

    impl<R> Drop for Stop<'_, R> {
        fn drop(&mut self) {
            self.owner.release();
        }
    }

    #[cfg(test)]
    mod tests {
        use super::Lifecycle;
        use std::sync::mpsc;
        use std::sync::Arc;
        use std::thread;
        use std::time::{Duration, Instant};

        #[test]
        fn start_stop_start_builds_a_new_session() {
            let life = Lifecycle::new();
            let (start, previous) = life.begin_start();
            assert!(previous.is_none());
            assert!(start.install(1).is_ok());
            drop(start);

            let mut stop = life.begin_stop();
            assert_eq!(stop.take(), Some(1));
            drop(stop);
            assert_eq!(life.with_current(|s| *s), None);

            let (start, previous) = life.begin_start();
            assert!(previous.is_none(), "the stopped session is never handed to the next start");
            assert!(!start.is_cancelled());
            assert!(start.install(2).is_ok());
            drop(start);
            assert_eq!(life.with_current(|s| *s), Some(2));
        }

        #[test]
        fn a_start_without_stop_still_releases_the_previous_session() {
            let life = Lifecycle::new();
            let (start, _) = life.begin_start();
            start.install(1).unwrap();
            drop(start);
            let (start, previous) = life.begin_start();
            assert_eq!(previous, Some(1));
            start.install(2).unwrap();
            drop(start);
            assert_eq!(life.with_current(|s| *s), Some(2));
        }

        #[test]
        fn stop_during_start_returns_promptly_and_the_start_is_refused() {
            let life = Arc::new(Lifecycle::new());
            let (started_tx, started_rx) = mpsc::channel();
            let worker = {
                let life = Arc::clone(&life);
                thread::spawn(move || {
                    let (start, _) = life.begin_start();
                    started_tx.send(()).unwrap();
                    // A bootstrap that would take a minute unless cancelled.
                    let deadline = Instant::now() + Duration::from_secs(60);
                    while !start.is_cancelled() && Instant::now() < deadline {
                        thread::sleep(Duration::from_millis(5));
                    }
                    start.install(7)
                })
            };
            started_rx.recv().unwrap();
            let began = Instant::now();
            let mut stop = life.begin_stop();
            assert!(began.elapsed() < Duration::from_secs(2), "stop waited for the whole bootstrap");
            assert_eq!(stop.take(), None);
            drop(stop);
            assert_eq!(worker.join().unwrap(), Err(7), "a cancelled start must hand its session back");
            assert_eq!(life.with_current(|s| *s), None);
        }

        #[test]
        fn stop_waits_for_a_cancelled_start_to_finish_its_teardown() {
            let life = Arc::new(Lifecycle::<u32>::new());
            let (started_tx, started_rx) = mpsc::channel();
            let (torn_down_tx, torn_down_rx) = mpsc::channel();
            let worker = {
                let life = Arc::clone(&life);
                thread::spawn(move || {
                    let (start, _) = life.begin_start();
                    started_tx.send(()).unwrap();
                    while !start.is_cancelled() {
                        thread::sleep(Duration::from_millis(5));
                    }
                    thread::sleep(Duration::from_millis(50));
                    torn_down_tx.send(()).unwrap();
                })
            };
            started_rx.recv().unwrap();
            let stop = life.begin_stop();
            assert!(torn_down_rx.try_recv().is_ok(), "stop returned before the start released its runtime");
            drop(stop);
            worker.join().unwrap();
        }

        #[test]
        fn a_start_after_a_stop_is_not_cancelled() {
            let life = Lifecycle::new();
            drop(life.begin_stop());
            drop(life.begin_stop());
            let (start, _) = life.begin_start();
            assert!(!start.is_cancelled());
            assert!(start.install(3).is_ok());
        }
    }
}
