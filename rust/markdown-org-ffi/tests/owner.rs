//! That a repository whose directory belongs to somebody else still opens.
//!
//! In a file of its own because the setting behind it is global to the process
//! and is never turned back on. Seeing the refusal it lifts means running
//! before anything else in the process has opened a repository, and one test
//! in one file is the only way to have that ordering — cargo gives each
//! integration test file a process, and the tests inside one run in whatever
//! order the harness picks. The rest of the sync surface is tests/sync.rs.
//!
//! What this stands in for is the shared storage of Android, where the owner
//! never matches and the refusal is the whole reason a notes directory outside
//! the application's storage could be read but not committed to.

#![cfg(unix)]

use std::fs;
use std::os::unix::fs::{chown, MetadataExt};

use git2::{ErrorCode, Repository, Signature};
use markdown_org_ffi::{holds_repository, repository_status};

/// Who to hand the directory to: `nobody` on the images this runs in.
const OTHER_USER: u32 = 65_534;

/// The user that may hand a directory to anybody, and therefore has no excuse
/// for failing to.
const ROOT: u32 = 0;

#[test]
fn a_directory_owned_by_another_user_still_opens() {
    let dir = tempfile::tempdir().expect("tempdir");
    let path = dir.path();
    // In a block of its own so that everything holding the repository open is
    // closed before the directory is handed over: what is being tested is
    // opening it afresh, not reusing a handle from before the change.
    {
        let repository = Repository::init(path).expect("init");
        let tree_id = repository
            .index()
            .expect("index")
            .write_tree()
            .expect("write tree");
        let tree = repository.find_tree(tree_id).expect("tree");
        let who = Signature::now("Test", "test@example.invalid").expect("signature");
        repository
            .commit(Some("HEAD"), &who, &who, "initial", &tree, &[])
            .expect("commit");
    }

    // Whoever this process is: the directory was created by it a moment ago.
    let owner = fs::metadata(path).expect("metadata").uid();
    let other = if owner == OTHER_USER { 1 } else { OTHER_USER };

    // Giving a directory away needs privileges, and a run as an ordinary user
    // has no way to produce the state this is about at all — it is skipped
    // there rather than made to fail. Under root there is nothing to excuse:
    // a refusal then means something is wrong with the test itself, and the
    // container the core is tested in runs as root, so this is the case that
    // decides whether the check below is exercised at all.
    let handed_over = chown(path, Some(other), None);
    if owner == ROOT {
        handed_over.expect("root could not hand the directory over");
    } else if handed_over.is_err() {
        eprintln!("skipped: handing a directory over needs privileges this run lacks");
        return;
    }

    // libgit2 as it comes, to show the state is the one being talked about.
    // `Repository` carries no Debug, so the outcome is unwrapped by hand
    // rather than through `expect_err`.
    let Err(refused) = Repository::open(path) else {
        panic!("the owner check did not refuse");
    };
    assert_eq!(refused.code(), ErrorCode::Owner, "{refused}");

    // The same directory, through the core.
    assert!(holds_repository(path.display().to_string()));
    let status = repository_status(path.display().to_string())
        .expect("status")
        .expect("a repository is there");
    assert_eq!(status.head_summary, "initial");
}
