//! Tests for the sync surface.
//!
//! Everything runs against a repository on disk reached by path. That is the
//! same code path as `https://` right up to the transport, and it keeps the
//! tests off the network — a test that needs GitHub to be reachable is a test
//! that fails for reasons that have nothing to do with the change.

use std::fs;
use std::path::Path;

use git2::{Repository, Signature};
use markdown_org_ffi::{repository_status, sync_repository, SyncError, SyncRequest};

/// A repository with one commit in it, standing in for the remote.
fn origin(files: &[(&str, &str)]) -> tempfile::TempDir {
    let dir = tempfile::tempdir().expect("tempdir");
    let repository = Repository::init(dir.path()).expect("init");
    commit(&repository, files, "initial");
    dir
}

fn commit(repository: &Repository, files: &[(&str, &str)], message: &str) {
    let workdir = repository.workdir().expect("not bare").to_path_buf();
    for (name, body) in files {
        let path = workdir.join(name);
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).expect("create parent");
        }
        fs::write(&path, body).expect("write");
    }

    let mut index = repository.index().expect("index");
    index
        .add_all(["*"], git2::IndexAddOption::DEFAULT, None)
        .expect("add");
    index.write().expect("write index");
    let tree = repository
        .find_tree(index.write_tree().expect("write tree"))
        .expect("tree");

    // A local identity, never a global one: the test must not depend on, or
    // touch, whatever git configuration the machine has.
    let who = Signature::now("Test", "test@example.invalid").expect("signature");
    let parents = match repository.head().ok().and_then(|head| head.peel_to_commit().ok()) {
        Some(parent) => vec![parent],
        None => Vec::new(),
    };
    let parents: Vec<&git2::Commit> = parents.iter().collect();
    repository
        .commit(Some("HEAD"), &who, &who, message, &tree, &parents)
        .expect("commit");
}

fn request(dir: &Path, url: &Path) -> SyncRequest {
    SyncRequest {
        dir: dir.display().to_string(),
        url: url.display().to_string(),
        token: None,
        branch: None,
        ca_bundle_pem: None,
    }
}

const NOTES: &str = "# TODO Write the report\n`SCHEDULED: <2026-03-02 Mon 10:00>`\n";

#[test]
fn the_first_sync_clones() {
    let remote = origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");

    let outcome = sync_repository(request(&checkout, remote.path())).expect("sync");

    assert!(outcome.cloned);
    assert_eq!(outcome.commits_applied, 0);
    assert_eq!(outcome.head.head_summary, "initial");
    assert!(checkout.join("notes.md").exists());
}

#[test]
fn a_second_sync_fast_forwards() {
    let remote = origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    sync_repository(request(&checkout, remote.path())).expect("clone");

    let upstream = Repository::open(remote.path()).expect("open");
    commit(&upstream, &[("later.md", NOTES)], "add later.md");

    let outcome = sync_repository(request(&checkout, remote.path())).expect("sync");

    assert!(!outcome.cloned);
    assert_eq!(outcome.commits_applied, 1);
    assert_eq!(outcome.head.head_summary, "add later.md");
    assert!(
        checkout.join("later.md").exists(),
        "a fast-forward has to reach the working copy, not just the ref"
    );
}

#[test]
fn syncing_an_unchanged_remote_applies_nothing() {
    let remote = origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    sync_repository(request(&checkout, remote.path())).expect("clone");

    let outcome = sync_repository(request(&checkout, remote.path())).expect("sync");

    assert_eq!(outcome.commits_applied, 0);
}

#[test]
fn a_local_commit_stops_the_sync_instead_of_merging() {
    let remote = origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    sync_repository(request(&checkout, remote.path())).expect("clone");

    let upstream = Repository::open(remote.path()).expect("open");
    commit(&upstream, &[("theirs.md", NOTES)], "theirs");
    let mine = Repository::open(&checkout).expect("open");
    commit(&mine, &[("mine.md", NOTES)], "mine");

    let error = sync_repository(request(&checkout, remote.path())).expect_err("must fail");

    // Reported, not resolved: merging belongs with the editing this
    // application does not do yet.
    assert!(matches!(error, SyncError::Diverged { .. }), "got {error:?}");
}

#[test]
fn uncommitted_changes_stop_the_sync_before_the_checkout() {
    let remote = origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    sync_repository(request(&checkout, remote.path())).expect("clone");

    let upstream = Repository::open(remote.path()).expect("open");
    commit(&upstream, &[("notes.md", "# TODO Something else\n")], "theirs");
    fs::write(checkout.join("notes.md"), "# TODO Edited here\n").expect("write");

    let error = sync_repository(request(&checkout, remote.path())).expect_err("must fail");

    assert!(matches!(error, SyncError::Dirty { .. }), "got {error:?}");
    assert_eq!(
        fs::read_to_string(checkout.join("notes.md")).expect("read"),
        "# TODO Edited here\n",
        "the local edit must survive a refused sync",
    );
}

#[test]
fn an_unreachable_remote_fails_without_leaving_a_repository() {
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    let nowhere = local.path().join("nowhere");

    let error = sync_repository(request(&checkout, &nowhere)).expect_err("must fail");

    assert!(
        matches!(error, SyncError::Repository { .. } | SyncError::Network { .. }),
        "got {error:?}",
    );
    assert!(
        repository_status(checkout.display().to_string())
            .expect("status")
            .is_none(),
        "a failed clone must not leave something the next call mistakes for a checkout",
    );
}

#[test]
fn the_status_of_an_empty_directory_is_absent_rather_than_an_error() {
    let local = tempfile::tempdir().expect("tempdir");

    let status = repository_status(local.path().display().to_string()).expect("status");

    assert!(status.is_none());
}

#[test]
fn the_status_reports_the_remote_the_branch_and_the_head() {
    let remote = origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    sync_repository(request(&checkout, remote.path())).expect("clone");

    let status = repository_status(checkout.display().to_string())
        .expect("status")
        .expect("cloned");

    assert_eq!(status.url, remote.path().display().to_string());
    assert_eq!(status.head_summary, "initial");
    assert_eq!(status.head_id.len(), 40);
    assert!(status.head_time > 0);
    assert!(!status.dirty);
}

#[test]
fn an_edited_working_copy_reports_itself_dirty() {
    let remote = origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    sync_repository(request(&checkout, remote.path())).expect("clone");

    fs::write(checkout.join("notes.md"), "# TODO Edited\n").expect("write");
    let status = repository_status(checkout.display().to_string())
        .expect("status")
        .expect("cloned");

    assert!(status.dirty);
}
