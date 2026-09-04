//! Shared by the editing tests: a directory holding one file of notes, and
//! the two ways every case addresses it.
//!
//! Not a test target of its own — `tests/common/mod.rs` is the layout cargo
//! treats as a module rather than as another integration test binary.

use std::fs;
use std::path::Path;

use markdown_org_ffi::EditTarget;

/// A directory with `notes.md` in it, removed when the returned handle drops.
///
/// Unused by the properties, which generate the file they write rather than
/// spelling one out; the allowance is the same one `target` carries.
#[allow(dead_code)]
pub fn vault(body: &str) -> tempfile::TempDir {
    let dir = tempfile::tempdir().expect("tempdir");
    fs::write(dir.path().join("notes.md"), body).expect("write");
    dir
}

/// What an edit is aimed at: the file, the line, and the heading the caller
/// believes is on it.
///
/// Unused by the cases that write a task rather than edit one, which reach the
/// file by name instead — hence the allowance: this module is compiled into
/// every test binary, and each takes only the helpers it needs.
#[allow(dead_code)]
pub fn target(dir: &Path, line: u32, heading: &str) -> EditTarget {
    EditTarget {
        dir: dir.display().to_string(),
        file: "notes.md".to_string(),
        line,
        heading: heading.to_string(),
    }
}

/// The whole file, which is what the cases assert on.
pub fn body(dir: &Path) -> String {
    fs::read_to_string(dir.join("notes.md")).expect("read")
}
