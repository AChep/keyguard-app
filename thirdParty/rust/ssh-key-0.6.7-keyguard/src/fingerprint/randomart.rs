//! Support for the "drunken bishop" fingerprint algorithm, a.k.a. "randomart".
//!
//! The algorithm is described in the paper:
//!
//! "The drunken bishop: An analysis of the OpenSSH fingerprint visualization algorithm"
//!
//! <http://www.dirk-loss.de/sshvis/drunken_bishop.pdf>

use super::Fingerprint;
use core::fmt;

const WIDTH: usize = 17;
const HEIGHT: usize = 9;
const VALUES: &[u8; 17] = b" .o+=*BOX@%&#/^SE";
const NVALUES: u8 = VALUES.len() as u8 - 1;

type Field = [[u8; WIDTH]; HEIGHT];

/// "randomart" renderer.
pub(super) struct Randomart<'a> {
    header: &'a str,
    field: Field,
    footer: &'static str,
}

impl<'a> Randomart<'a> {
    /// Create new "randomart" from the given fingerprint.
    // TODO: Remove this when the pipeline toolchain is updated beyond 1.69
    #[allow(clippy::arithmetic_side_effects)]
    pub(super) fn new(header: &'a str, fingerprint: Fingerprint) -> Self {
        let mut field = Field::default();
        let mut x = WIDTH / 2;
        let mut y = HEIGHT / 2;

        for mut byte in fingerprint.as_bytes().iter().copied() {
            for _ in 0..4 {
                if byte & 0x1 == 0 {
                    x = x.saturating_sub(1);
                } else {
                    x = x.saturating_add(1);
                }

                if byte & 0x2 == 0 {
                    y = y.saturating_sub(1);
                } else {
                    y = y.saturating_add(1);
                }

                x = x.min(WIDTH.saturating_sub(1));
                y = y.min(HEIGHT.saturating_sub(1));

                if field[y][x] < NVALUES - 2 {
                    field[y][x] = field[y][x].saturating_add(1);
                }

                byte >>= 2;
            }
        }

        field[HEIGHT / 2][WIDTH / 2] = NVALUES - 1;
        field[y][x] = NVALUES;

        Self {
            header,
            field,
            footer: fingerprint.footer(),
        }
    }
}

impl fmt::Display for Randomart<'_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        writeln!(f, "+{:-^width$}+", self.header, width = WIDTH)?;

        for row in self.field {
            write!(f, "|")?;

            for c in row {
                write!(f, "{}", VALUES[c as usize] as char)?;
            }

            writeln!(f, "|")?;
        }

        write!(f, "+{:-^width$}+", self.footer, width = WIDTH)
    }
}

#[cfg(all(test, feature = "alloc"))]
mod tests {
    use super::Fingerprint;

    const EXAMPLE_FINGERPRINT: &str = "SHA256:UCUiLr7Pjs9wFFJMDByLgc3NrtdU344OgUM45wZPcIQ";
    const EXAMPLE_RANDOMART: &str = "\
+--[ED25519 256]--+
|o+oO==+ o..      |
|.o++Eo+o..       |
|. +.oO.o . .     |
| . o..B.. . .    |
|  ...+ .S. o     |
|  .o. . . . .    |
|  o..    o       |
|   B      .      |
|  .o*            |
+----[SHA256]-----+";

    #[test]
    fn generation() {
        let fingerprint = EXAMPLE_FINGERPRINT.parse::<Fingerprint>().unwrap();
        let randomart = fingerprint.to_randomart("[ED25519 256]");
        assert_eq!(EXAMPLE_RANDOMART, randomart);
    }
}
