//! The card through VFIO (S4): BAR0 mapped into the process, host memory pinned and mapped into the
//! card's IOMMU domain, the command register's Bus Master Enable set. The only `unsafe` code in the
//! crate lives here: the VFIO ioctls, `mmap`, and the volatile accesses to memory the card writes.
//!
//! The device must be bound to `vfio-pci` and its IOMMU group must be viable (every device in the group
//! bound to vfio-pci or to nothing). Pinning needs CAP_IPC_LOCK or a large enough RLIMIT_MEMLOCK.

use std::ffi::CString;
use std::fs::{self, File, OpenOptions};
use std::io;
use std::os::raw::{c_int, c_long, c_ulong, c_void};
use std::os::unix::fs::FileExt;
use std::os::unix::io::{AsRawFd, FromRawFd};
use std::ptr;

use crate::bar::Bar;
use crate::link::HostMem;

extern "C" {
    fn ioctl(fd: c_int, request: c_ulong, ...) -> c_int;
    fn mmap(addr: *mut c_void, len: usize, prot: c_int, flags: c_int, fd: c_int, offset: c_long) -> *mut c_void;
    fn munmap(addr: *mut c_void, len: usize) -> c_int;
}
const PROT_READ: c_int = 1;
const PROT_WRITE: c_int = 2;
const MAP_SHARED: c_int = 0x01;
const MAP_PRIVATE: c_int = 0x02;
const MAP_ANONYMOUS: c_int = 0x20;
const MAP_POPULATE: c_int = 0x8000;
const MAP_FAILED: *mut c_void = !0usize as *mut c_void;

// <linux/vfio.h>: _IO(VFIO_TYPE, VFIO_BASE + n), VFIO_TYPE ';', VFIO_BASE 100
const fn vfio_io(n: c_ulong) -> c_ulong {
    ((b';' as c_ulong) << 8) | (100 + n)
}
const GET_API_VERSION: c_ulong = vfio_io(0);
const CHECK_EXTENSION: c_ulong = vfio_io(1);
const SET_IOMMU: c_ulong = vfio_io(2);
const GROUP_GET_STATUS: c_ulong = vfio_io(3);
const GROUP_SET_CONTAINER: c_ulong = vfio_io(4);
const GROUP_GET_DEVICE_FD: c_ulong = vfio_io(6);
const DEVICE_GET_REGION_INFO: c_ulong = vfio_io(8);
const IOMMU_MAP_DMA: c_ulong = vfio_io(13);
const API_VERSION: c_int = 0;
const TYPE1V2_IOMMU: c_ulong = 3;
const GROUP_FLAGS_VIABLE: u32 = 1;
const DMA_MAP_FLAG_READ: u32 = 1;
const DMA_MAP_FLAG_WRITE: u32 = 2;
const PCI_BAR0_REGION_INDEX: u32 = 0;
const PCI_CONFIG_REGION_INDEX: u32 = 7;
const PCI_COMMAND: u64 = 4;
const PCI_COMMAND_MEMORY: u16 = 1 << 1;
const PCI_COMMAND_MASTER: u16 = 1 << 2;

#[repr(C)]
#[derive(Default)]
struct GroupStatus {
    argsz: u32,
    flags: u32,
}
#[repr(C)]
#[derive(Default)]
struct RegionInfo {
    argsz: u32,
    flags: u32,
    index: u32,
    cap_offset: u32,
    size: u64,
    offset: u64,
}
#[repr(C)]
struct DmaMap {
    argsz: u32,
    flags: u32,
    vaddr: u64,
    iova: u64,
    size: u64,
}

fn check(what: &str, r: c_int) -> io::Result<c_int> {
    if r < 0 {
        let e = io::Error::last_os_error();
        return Err(io::Error::new(e.kind(), format!("{what}: {e}")));
    }
    Ok(r)
}
fn other(msg: String) -> io::Error {
    io::Error::other(msg)
}

/// An open VFIO device: its container (the IOMMU domain), group and device file descriptors.
pub struct Device {
    container: File,
    _group: File,
    device: File,
    config_offset: u64,
}

impl Device {
    /// Opens the PCI device `bdf` (e.g. `0000:04:00.0`), which must be bound to vfio-pci.
    pub fn open(bdf: &str) -> io::Result<Device> {
        let link = fs::read_link(format!("/sys/bus/pci/devices/{bdf}/iommu_group"))
            .map_err(|e| other(format!("{bdf} has no IOMMU group ({e}): is the IOMMU on?")))?;
        let group_no = link.file_name().and_then(|n| n.to_str()).ok_or_else(|| other("bad iommu_group link".into()))?.to_string();
        let container = OpenOptions::new().read(true).write(true).open("/dev/vfio/vfio")?;
        let group = OpenOptions::new().read(true).write(true).open(format!("/dev/vfio/{group_no}"))
            .map_err(|e| other(format!("/dev/vfio/{group_no}: {e} (bound to vfio-pci? root?)")))?;
        unsafe {
            if check("VFIO_GET_API_VERSION", ioctl(container.as_raw_fd(), GET_API_VERSION))? != API_VERSION {
                return Err(other("unknown VFIO API version".into()));
            }
            if check("VFIO_CHECK_EXTENSION", ioctl(container.as_raw_fd(), CHECK_EXTENSION, TYPE1V2_IOMMU))? != 1 {
                return Err(other("no VFIO type 1 v2 IOMMU".into()));
            }
            let mut st = GroupStatus { argsz: std::mem::size_of::<GroupStatus>() as u32, ..Default::default() };
            check("VFIO_GROUP_GET_STATUS", ioctl(group.as_raw_fd(), GROUP_GET_STATUS, &mut st as *mut GroupStatus))?;
            if st.flags & GROUP_FLAGS_VIABLE == 0 {
                return Err(other(format!("IOMMU group {group_no} is not viable: bind all its devices to vfio-pci")));
            }
            let cfd: c_int = container.as_raw_fd();
            check("VFIO_GROUP_SET_CONTAINER", ioctl(group.as_raw_fd(), GROUP_SET_CONTAINER, &cfd as *const c_int))?;
            check("VFIO_SET_IOMMU", ioctl(container.as_raw_fd(), SET_IOMMU, TYPE1V2_IOMMU))?;
            let name = CString::new(bdf).map_err(|_| other("bad device name".into()))?;
            let dfd = check("VFIO_GROUP_GET_DEVICE_FD", ioctl(group.as_raw_fd(), GROUP_GET_DEVICE_FD, name.as_ptr()))?;
            let device = File::from_raw_fd(dfd);
            let mut d = Device { container, _group: group, device, config_offset: 0 };
            d.config_offset = d.region(PCI_CONFIG_REGION_INDEX)?.offset;
            Ok(d)
        }
    }

    fn region(&self, index: u32) -> io::Result<RegionInfo> {
        let mut r = RegionInfo { argsz: std::mem::size_of::<RegionInfo>() as u32, index, ..Default::default() };
        check("VFIO_DEVICE_GET_REGION_INFO", unsafe { ioctl(self.device.as_raw_fd(), DEVICE_GET_REGION_INFO, &mut r as *mut RegionInfo) })?;
        Ok(r)
    }

    pub fn config_read16(&self, offset: u64) -> io::Result<u16> {
        let mut b = [0u8; 2];
        self.device.read_exact_at(&mut b, self.config_offset + offset)?;
        Ok(u16::from_le_bytes(b))
    }
    pub fn config_write16(&self, offset: u64, v: u16) -> io::Result<()> {
        self.device.write_all_at(&v.to_le_bytes(), self.config_offset + offset)
    }

    /// Enables memory decoding and the card's DMA (Bus Master Enable).
    pub fn enable_bus_master(&self) -> io::Result<()> {
        let c = self.config_read16(PCI_COMMAND)?;
        self.config_write16(PCI_COMMAND, c | PCI_COMMAND_MEMORY | PCI_COMMAND_MASTER)
    }

    /// Maps BAR0 into the process.
    pub fn bar0(&self) -> io::Result<MappedBar> {
        let r = self.region(PCI_BAR0_REGION_INDEX)?;
        let p = unsafe { mmap(ptr::null_mut(), r.size as usize, PROT_READ | PROT_WRITE, MAP_SHARED, self.device.as_raw_fd(), r.offset as c_long) };
        if p == MAP_FAILED {
            return Err(other(format!("mmap BAR0: {}", io::Error::last_os_error())));
        }
        Ok(MappedBar { base: p as *mut u32, size: r.size as usize })
    }

    /// Allocates `size` bytes of zeroed host memory, pins it and maps it for the card's DMA at `iova`.
    pub fn dma_buffer(&self, size: usize, iova: u64) -> io::Result<DmaBuffer> {
        let p = unsafe { mmap(ptr::null_mut(), size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_POPULATE, -1, 0) };
        if p == MAP_FAILED {
            return Err(other(format!("mmap {size} bytes: {}", io::Error::last_os_error())));
        }
        let buf = DmaBuffer { base: p as *mut u8, size, iova };
        let map = DmaMap {
            argsz: std::mem::size_of::<DmaMap>() as u32,
            flags: DMA_MAP_FLAG_READ | DMA_MAP_FLAG_WRITE,
            vaddr: p as u64,
            iova,
            size: size as u64,
        };
        check("VFIO_IOMMU_MAP_DMA", unsafe { ioctl(self.container.as_raw_fd(), IOMMU_MAP_DMA, &map as *const DmaMap) })?;
        Ok(buf)
    }
}

/// BAR0, mapped. Every access is one 32-bit volatile load or store, as the card expects.
pub struct MappedBar {
    base: *mut u32,
    size: usize,
}

impl MappedBar {
    fn at(&self, offset: usize) -> *mut u32 {
        assert!(offset % 4 == 0 && offset + 4 <= self.size, "BAR0 offset {offset:#x}");
        unsafe { self.base.add(offset / 4) }
    }
}
impl Bar for MappedBar {
    fn read32(&mut self, offset: usize) -> u32 {
        unsafe { ptr::read_volatile(self.at(offset)) }
    }
    fn write32(&mut self, offset: usize, value: u32) {
        unsafe { ptr::write_volatile(self.at(offset), value) }
    }
}
impl Drop for MappedBar {
    fn drop(&mut self) {
        unsafe { munmap(self.base as *mut c_void, self.size) };
    }
}

/// Pinned host memory the card reaches at `iova`. The card writes it behind the compiler's back, so
/// every access is volatile. The mapping lasts as long as the container; the memory is never freed
/// while the card might still write it.
pub struct DmaBuffer {
    base: *mut u8,
    size: usize,
    pub iova: u64,
}

impl DmaBuffer {
    pub fn len(&self) -> usize {
        self.size
    }
    pub fn is_empty(&self) -> bool {
        self.size == 0
    }
}
impl HostMem for DmaBuffer {
    fn read(&mut self, offset: usize, buf: &mut [u8]) {
        assert!(offset.checked_add(buf.len()).is_some_and(|e| e <= self.size), "DMA buffer read out of range");
        for (i, b) in buf.iter_mut().enumerate() {
            *b = unsafe { ptr::read_volatile(self.base.add(offset + i)) };
        }
    }
    fn write(&mut self, offset: usize, data: &[u8]) {
        assert!(offset.checked_add(data.len()).is_some_and(|e| e <= self.size), "DMA buffer write out of range");
        for (i, &b) in data.iter().enumerate() {
            unsafe { ptr::write_volatile(self.base.add(offset + i), b) };
        }
        // the card reads staging after the doorbell: make the bytes visible before any later BAR write
        std::sync::atomic::fence(std::sync::atomic::Ordering::SeqCst);
    }
}
