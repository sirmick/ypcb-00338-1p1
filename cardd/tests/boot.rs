//! S5: the boot path against the card model. Images reach main memory only through the boot window
//! (while GUEST_RESET holds the cores); the console travels as CONSOLE_TX records and CONSOLE_RX
//! commands.
mod common;

use std::cell::RefCell;
use std::rc::Rc;

use cardd::backend::{self, BootMem, Link};
use cardd::contract::{copy_status, record, BOOT_WINDOW, DMA_WINDOWS};
use cardd::link;
use cardd::mem::{GuestMem, MemError};
use cardd::message;
use common::fake_card::{FakeBar, FakeInbox, FakeStaging, State};

fn card() -> (Rc<RefCell<State>>, Link) {
    let s = Rc::new(RefCell::new(State::default()));
    let mut bar = FakeBar(s.clone());
    link::set_up(&mut bar, 0x4000_0000, 4, 0x5000_0000, 4096).unwrap();
    let l = Link::new(Box::new(bar), Box::new(FakeInbox(s.clone())), 4, Box::new(FakeStaging(s.clone())), 4096);
    (s, l)
}

#[test]
fn images_load_through_the_boot_window_only_while_the_cores_are_held() {
    let (s, mut l) = card();
    assert!(l.guest_reset(), "the cores must start held");
    assert!(l.ram_ready());
    let image: Vec<u8> = (0..10_000u32).map(|i| (i * 7 + i / 300) as u8).collect(); // spans several staging loads
    let at = BOOT_WINDOW.0 + 0x20_0013; // an odd address: staging keeps its place in the line
    BootMem(&mut l).write(at, &image).unwrap();
    assert_eq!(s.borrow().guest_read(at, image.len()), image);
    let mut back = vec![0u8; image.len()];
    BootMem(&mut l).read(at, &mut back).unwrap();
    assert_eq!(back, image);

    l.set_guest_reset(false);
    assert!(!l.guest_reset());
    let before = s.borrow().guest_read(at, 64);
    assert_eq!(BootMem(&mut l).write(at, &[0xee; 64]), Err(MemError::Copy(copy_status::OUTSIDE_WINDOW)),
               "the card must refuse main memory once the cores run");
    assert_eq!(s.borrow().guest_read(at, 64), before);
}

#[test]
fn the_boot_window_is_main_memory_and_nothing_else() {
    let (s, mut l) = card();
    let dma = DMA_WINDOWS[0].0;
    for (guest, len) in [(dma, 64usize), (BOOT_WINDOW.0 - 1, 2), (BOOT_WINDOW.0 + BOOT_WINDOW.1 - 4, 8), (u64::MAX - 8, 64)] {
        assert_eq!(BootMem(&mut l).write(guest, &vec![1; len]), Err(MemError::OutsideWindow { guest, len }));
    }
    assert!(s.borrow().commands.is_empty(), "a refused range still reached the card");
}

#[test]
fn console_bytes_cross_in_both_directions() {
    let (s, mut l) = card();
    let text: Vec<u8> = b"root@card:~# ".iter().copied().cycle().take(130).collect();
    l.console_rx(&text).unwrap();
    assert_eq!(s.borrow().console_rx, text, "130 bytes must arrive whole, in three commands");

    let mut data = [0u8; 48];
    data[..5].copy_from_slice(b"hello");
    s.borrow_mut().push_record(&record::CONSOLE_TX, 0, &[("count", &[5]), ("data", &data)]);
    let (h, b) = std::iter::from_fn(|| l.next_record().unwrap()).find(|(h, _)| h.kind == record::CONSOLE_TX.kind).unwrap();
    assert_eq!(backend::console_tx(&h, &b).unwrap(), b"hello");
    // a hostile count cannot read past the data field
    let mut evil = message::encode(&record::CONSOLE_TX, 99, 0, &[("count", &[255])]).unwrap();
    evil[12] = b'x';
    let hdr = message::decode(&evil).unwrap();
    assert_eq!(backend::console_tx(&hdr, &evil).unwrap().len(), 48);
}
