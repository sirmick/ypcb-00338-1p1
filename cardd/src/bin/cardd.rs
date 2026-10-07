//! `cardd`: the host side of the card's guest SoC.
//!
//!   cardd boot <bdf> <fw_jump.bin> <Image> [--socket PATH]
//!       Holds the cores in reset, waits for main memory, loads OpenSBI (device tree built in) at
//!       0x8000_0000 and the kernel (initramfs built in) at 0x8020_0000 through the boot window, reads both
//!       back, releases the cores, then serves the guest's console on a Unix socket (default
//!       /run/cardd/console.sock) until killed. One client at a time; a new client takes over, and gets
//!       the last 64 KiB of output first. Needs root and the card bound to vfio-pci.
//!   cardd console [PATH]
//!       Attaches this terminal (raw mode) to that socket. Ctrl-] detaches.

use std::collections::VecDeque;
use std::io::{ErrorKind, Read, Write};
use std::os::unix::net::{UnixListener, UnixStream};
use std::process::{exit, Command, Stdio};
use std::time::{Duration, Instant};

use cardd::backend::{self, BootMem, Link};
use cardd::contract::BOOT_WINDOW;
use cardd::link;
use cardd::mem::GuestMem;
use cardd::vfio::Device;

const SOCKET: &str = "/run/cardd/console.sock";
const SCROLLBACK: usize = 64 * 1024;
const DETACH: u8 = 0x1d; // Ctrl-]
const INBOX_LOG2: u32 = 6;
const INBOX_IOVA: u64 = 0x1000_0000;
const STAGING_IOVA: u64 = 0x1_0000_0000;
const STAGING_SIZE: usize = 1 << 20;
const FW_AT: u64 = BOOT_WINDOW.0;
const KERNEL_AT: u64 = BOOT_WINDOW.0 + 0x20_0000;

fn usage() -> ! {
    eprintln!("usage: cardd boot <bdf> <fw_jump.bin> <Image> [--socket PATH]\n       cardd console [PATH]");
    exit(2)
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        Some("boot") if args.len() == 4 || (args.len() == 6 && args[4] == "--socket") => {
            boot(&args[1], &args[2], &args[3], args.get(5).map_or(SOCKET, String::as_str))
        }
        Some("console") if args.len() <= 2 => console(args.get(1).map_or(SOCKET, String::as_str)),
        _ => usage(),
    }
}

fn load(link: &mut Link, name: &str, at: u64, image: &[u8]) {
    let t0 = Instant::now();
    BootMem(link).write(at, image).unwrap_or_else(|e| panic!("loading {name}: {e:?}"));
    let secs = t0.elapsed().as_secs_f64();
    let mut back = vec![0u8; image.len()];
    BootMem(link).read(at, &mut back).unwrap_or_else(|e| panic!("reading back {name}: {e:?}"));
    if let Some(first) = back.iter().zip(image).position(|(a, b)| a != b) {
        panic!("{name} differs from what was loaded at offset {first:#x}");
    }
    let mb = image.len() as f64 / 1e6;
    eprintln!("[cardd] {name}: {mb:.1} MB at {at:#x} in {secs:.2} s ({:.1} MB/s), read back and checked", mb / secs);
}

fn boot(bdf: &str, fw: &str, kernel: &str, socket: &str) {
    let fw = std::fs::read(fw).unwrap_or_else(|e| panic!("{fw}: {e}"));
    let kernel = std::fs::read(kernel).unwrap_or_else(|e| panic!("{kernel}: {e}"));
    let dev = Device::open(bdf).unwrap_or_else(|e| panic!("{bdf}: {e}"));
    dev.enable_bus_master().expect("enabling bus master");
    let mut bar = dev.bar0().expect("mapping BAR0");
    let inbox = dev.dma_buffer(64 << INBOX_LOG2, INBOX_IOVA).expect("inbox");
    let staging = dev.dma_buffer(STAGING_SIZE, STAGING_IOVA).expect("staging");
    link::set_up(&mut bar, INBOX_IOVA, INBOX_LOG2, STAGING_IOVA, STAGING_SIZE as u32).expect("set_up");
    let mut link = Link::new(Box::new(bar), Box::new(inbox), INBOX_LOG2, Box::new(staging), STAGING_SIZE);

    if let Some(dir) = std::path::Path::new(socket).parent() {
        std::fs::create_dir_all(dir).expect("creating the socket's directory");
    }
    let _ = std::fs::remove_file(socket);
    let listener = UnixListener::bind(socket).unwrap_or_else(|e| panic!("{socket}: {e}"));
    listener.set_nonblocking(true).unwrap();

    link.set_guest_reset(true);
    let t0 = Instant::now();
    while !link.ram_ready() {
        assert!(t0.elapsed() < Duration::from_secs(10), "main memory never became ready (DDR3 calibration?)");
        std::thread::sleep(Duration::from_millis(10));
    }
    load(&mut link, "firmware", FW_AT, &fw);
    load(&mut link, "kernel", KERNEL_AT, &kernel);
    eprintln!("[cardd] releasing the cores; console on {socket}");
    link.set_guest_reset(false);

    let mut scrollback: VecDeque<u8> = VecDeque::with_capacity(SCROLLBACK);
    let mut client: Option<UnixStream> = None;
    let mut buf = [0u8; 4096];
    loop {
        let mut idle = true;
        if let Ok((s, _)) = listener.accept() {
            s.set_nonblocking(true).unwrap();
            let (a, b) = scrollback.as_slices();
            let mut s = s;
            if s.write_all(a).and_then(|_| s.write_all(b)).is_ok() {
                client = Some(s); // a new client takes over from the old one
            }
        }
        while let Some((h, b)) = link.next_record().expect("inbox") {
            idle = false;
            if let Some(bytes) = backend::console_tx(&h, &b) {
                for &x in &bytes {
                    if scrollback.len() == SCROLLBACK {
                        scrollback.pop_front();
                    }
                    scrollback.push_back(x);
                }
                if let Some(s) = client.as_mut() {
                    if s.write_all(&bytes).is_err() {
                        client = None;
                    }
                }
            }
        }
        if let Some(s) = client.as_mut() {
            match s.read(&mut buf) {
                Ok(0) => client = None,
                Ok(n) => {
                    idle = false;
                    link.console_rx(&buf[..n]).expect("console input");
                }
                Err(e) if e.kind() == ErrorKind::WouldBlock => {}
                Err(_) => client = None,
            }
        }
        if idle {
            std::thread::sleep(Duration::from_micros(500));
        }
    }
}

/// The terminal's settings, restored when the console detaches.
struct RawTerminal(Option<String>);
impl RawTerminal {
    fn enter() -> RawTerminal {
        let saved = Command::new("stty").arg("-g").stdin(Stdio::inherit()).output().ok()
            .filter(|o| o.status.success()).map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string());
        if saved.is_some() {
            let _ = Command::new("stty").args(["raw", "-echo"]).stdin(Stdio::inherit()).status();
        }
        RawTerminal(saved)
    }
}
impl Drop for RawTerminal {
    fn drop(&mut self) {
        if let Some(s) = &self.0 {
            let _ = Command::new("stty").arg(s).stdin(Stdio::inherit()).status();
        }
    }
}

fn console(socket: &str) {
    let stream = UnixStream::connect(socket).unwrap_or_else(|e| {
        eprintln!("{socket}: {e} (is `cardd boot` running?)");
        exit(1)
    });
    eprintln!("[cardd] attached to {socket}; Ctrl-] detaches\r");
    let term = RawTerminal::enter();
    let saved = term.0.clone();
    let mut from = stream.try_clone().unwrap();
    std::thread::spawn(move || {
        let mut buf = [0u8; 4096];
        let mut out = std::io::stdout();
        while let Ok(n) = from.read(&mut buf) {
            if n == 0 || out.write_all(&buf[..n]).and_then(|_| out.flush()).is_err() {
                break;
            }
        }
        drop(RawTerminal(saved)); // restores the terminal: exit() below skips the main thread's
        eprintln!("\r\n[cardd] the console closed");
        std::process::exit(0);
    });
    let mut to = stream;
    let mut buf = [0u8; 1024];
    let mut stdin = std::io::stdin();
    while let Ok(n) = stdin.read(&mut buf) {
        if n == 0 {
            break;
        }
        if let Some(i) = buf[..n].iter().position(|&b| b == DETACH) {
            let _ = to.write_all(&buf[..i]);
            break;
        }
        if to.write_all(&buf[..n]).is_err() {
            break;
        }
    }
    drop(term);
    eprintln!("\r\n[cardd] detached");
}
