#![feature(test)]

extern crate test;

use polyval::{
    universal_hash::{KeyInit, UniversalHash},
    Polyval,
};
use test::Bencher;

// TODO(tarcieri): move this into the `universal-hash` crate
macro_rules! bench {
    ($name:ident, $bs:expr) => {
        #[bench]
        fn $name(b: &mut Bencher) {
            let key = Default::default();
            let mut m = Polyval::new(&key);
            let data = [0; $bs];

            b.iter(|| {
                m.update_padded(&data);
            });

            b.bytes = $bs;
        }
    };
}

bench!(bench1_10, 10);
bench!(bench2_100, 100);
bench!(bench3_1000, 1000);
bench!(bench3_10000, 10000);
