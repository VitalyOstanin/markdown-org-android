//! Runs the UniFFI binding generator.
//!
//! A workspace needs this as a crate of its own: the generator ships as a
//! library, and only a binary target can be invoked with `cargo run -p`.
//! See the UniFFI manual, "Foreign-language bindings".

fn main() {
    uniffi::uniffi_bindgen_main()
}
