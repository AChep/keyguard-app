use super::Destination;

// No destination can be captured on this platform.
pub(super) enum Target {}

pub(super) fn capture() -> Option<Target> {
    None
}
pub(super) fn permission() -> bool {
    false
}
impl Destination for Target {
    fn activate(&mut self) -> bool {
        match *self {}
    }
    fn focused(&mut self) -> bool {
        match *self {}
    }
    fn keys_released(&mut self) -> bool {
        match *self {}
    }
    fn press(&mut self, _: char) -> bool {
        match *self {}
    }
}
