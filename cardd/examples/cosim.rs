//! Co-simulation driver: cardd's real backend against the card's RTL in SpinalSim
//! (soc/src/test/scala/card/CosimSpec.scala). The bench runs this as a child process; every BAR
//! access and every read or write of host memory is one line on stdout, carried out by the bench
//! against the simulated card, which advances simulated time as it goes.
//!
//!   W <offset> <value>      BAR0 write
//!   R <offset>              BAR0 read            -> <value>
//!   I <offset> <len>        inbox read           -> <hex>
//!   S <offset> <len>        staging read         -> <hex>
//!   T <offset> <hex>        staging write
//!   G                       run the guest's side -> ok
//!   D <requests served>     finished
//!
//! Usage: cosim <inbox base> <staging base> <staging size> <disk image>

use std::cell::RefCell;
use std::io::{self, BufRead, Write};
use std::rc::Rc;

use cardd::backend::{self, BlkSlot, Link};
use cardd::bar::Bar;
use cardd::dev::blk::{Disk, SECTOR};
use cardd::link::{self, HostMem};
use cardd::slot::Slot;

struct Pipe {
    input: io::StdinLock<'static>,
}
impl Pipe {
    fn tell(&mut self, line: String) {
        let mut o = io::stdout().lock();
        writeln!(o, "{line}").unwrap();
        o.flush().unwrap();
    }
    fn ask(&mut self, line: String) -> String {
        self.tell(line);
        let mut r = String::new();
        self.input.read_line(&mut r).unwrap();
        r.trim().to_string()
    }
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}
fn unhex(s: &str, out: &mut [u8]) {
    for (i, b) in out.iter_mut().enumerate() {
        *b = u8::from_str_radix(&s[2 * i..2 * i + 2], 16).unwrap();
    }
}

struct PBar(Rc<RefCell<Pipe>>);
impl Bar for PBar {
    fn read32(&mut self, offset: usize) -> u32 {
        self.0.borrow_mut().ask(format!("R {offset}")).parse().unwrap()
    }
    fn write32(&mut self, offset: usize, value: u32) {
        self.0.borrow_mut().tell(format!("W {offset} {value}"));
    }
}

struct PMem(Rc<RefCell<Pipe>>, &'static str);
impl HostMem for PMem {
    fn read(&mut self, offset: usize, buf: &mut [u8]) {
        let r = self.0.borrow_mut().ask(format!("{} {offset} {}", self.1, buf.len()));
        unhex(&r, buf);
    }
    fn write(&mut self, offset: usize, data: &[u8]) {
        assert_eq!(self.1, "S", "the host never writes its inbox");
        self.0.borrow_mut().tell(format!("T {offset} {}", hex(data)));
    }
}

struct FileDisk(Vec<u8>);
impl Disk for FileDisk {
    fn sectors(&self) -> u64 { self.0.len() as u64 / SECTOR }
    fn read(&mut self, s: u64, buf: &mut [u8]) -> Result<(), ()> {
        let o = (s * SECTOR) as usize;
        buf.copy_from_slice(self.0.get(o..o + buf.len()).ok_or(())?);
        Ok(())
    }
    fn write(&mut self, s: u64, data: &[u8]) -> Result<(), ()> {
        let o = (s * SECTOR) as usize;
        self.0.get_mut(o..o + data.len()).ok_or(())?.copy_from_slice(data);
        Ok(())
    }
    fn flush(&mut self) -> Result<(), ()> { Ok(()) }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let num = |i: usize| u64::from_str_radix(args[i].trim_start_matches("0x"), 16).unwrap();
    let (inbox, staging, staging_size) = (num(1), num(2), num(3) as usize);
    let disk = std::fs::read(&args[4]).expect("disk image");

    let pipe = Rc::new(RefCell::new(Pipe { input: io::stdin().lock() }));
    let mut link = Link::new(Box::new(PBar(pipe.clone())), Box::new(PMem(pipe.clone(), "I")), 6, Box::new(PMem(pipe.clone(), "S")), staging_size);
    link::set_up(link.bar.as_mut(), inbox, 6, staging, staging_size as u32).expect("set up");
    let mut blk = BlkSlot { slot: 0, state: Slot::default(), queue: None, disk: Box::new(FileDisk(disk)) };
    link::program_slot(link.bar.as_mut(), 0, &blk.device(256));
    assert_eq!(pipe.borrow_mut().ask("G".into()), "ok");
    let mut served = 0;
    while served < 1 {
        served += backend::serve(&mut link, &mut blk).unwrap_or_else(|e| panic!("backend: {e:?}"));
    }
    pipe.borrow_mut().tell(format!("D {served}"));
}
