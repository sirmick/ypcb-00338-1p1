//! BAR0 access. The real implementation maps the BAR through VFIO (S4); tests use a fake.

pub trait Bar {
    fn read32(&mut self, offset: usize) -> u32;
    fn write32(&mut self, offset: usize, value: u32);
}
