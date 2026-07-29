//! Tests for the sync surface.
//!
//! Everything runs against a repository on disk reached by path. That is the
//! same code path as `https://` right up to the transport, and it keeps the
//! tests off the network — a test that needs GitHub to be reachable is a test
//! that fails for reasons that have nothing to do with the change.

use std::fs;
use std::path::Path;

use git2::{Repository, Signature};
use markdown_org_ffi::{
    commit_changes, repository_status, sync_repository, CommitAuthor, SyncError, SyncRequest,
};

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
    let parents = match repository
        .head()
        .ok()
        .and_then(|head| head.peel_to_commit().ok())
    {
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
    commit(
        &upstream,
        &[("notes.md", "# TODO Something else\n")],
        "theirs",
    );
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
        matches!(
            error,
            SyncError::Repository { .. } | SyncError::Network { .. }
        ),
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

fn author() -> CommitAuthor {
    CommitAuthor {
        name: "Test".to_string(),
        email: "test@example.invalid".to_string(),
    }
}

#[test]
fn committing_an_edit_leaves_the_working_copy_clean() {
    let remote = origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    sync_repository(request(&checkout, remote.path())).expect("clone");
    fs::write(checkout.join("notes.md"), "# DONE Write the report\n").expect("write");

    let id = commit_changes(
        checkout.display().to_string(),
        "Mark \"Write the report\" as DONE".to_string(),
        author(),
    )
    .expect("commit")
    .expect("something to commit");

    assert_eq!(id.len(), 40);
    let status = repository_status(checkout.display().to_string())
        .expect("status")
        .expect("cloned");
    // The point of committing right after an edit: a dirty checkout is what
    // makes the next fast-forward refuse.
    assert!(!status.dirty);
    assert_eq!(status.head_summary, "Mark \"Write the report\" as DONE");
    assert_eq!(status.head_id, id);
}

#[test]
fn committing_with_nothing_to_commit_leaves_head_where_it_was() {
    let remote = origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    let before = sync_repository(request(&checkout, remote.path())).expect("clone");

    let committed = commit_changes(
        checkout.display().to_string(),
        "nothing happened".to_string(),
        author(),
    )
    .expect("commit");

    assert!(committed.is_none(), "an empty commit must not be created");
    let status = repository_status(checkout.display().to_string())
        .expect("status")
        .expect("cloned");
    assert_eq!(status.head_id, before.head.head_id);
}

#[test]
fn committing_picks_up_a_new_file_as_well_as_a_changed_one() {
    let remote = origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    sync_repository(request(&checkout, remote.path())).expect("clone");
    fs::write(checkout.join("notes.md"), "# DONE Write the report\n").expect("write");
    fs::write(checkout.join("inbox.md"), "# TODO Captured\n").expect("write");

    commit_changes(
        checkout.display().to_string(),
        "edit and capture".to_string(),
        author(),
    )
    .expect("commit")
    .expect("something to commit");

    let status = repository_status(checkout.display().to_string())
        .expect("status")
        .expect("cloned");
    assert!(
        !status.dirty,
        "an untracked file left behind would block the next sync"
    );
}

#[test]
fn a_commit_message_that_is_not_utf8_is_shown_approximately_rather_than_as_nothing() {
    // A commit made elsewhere may carry an `encoding` header and a message in
    // a single-byte encoding. An empty summary is indistinguishable from a
    // commit that has none, so the bytes are rendered lossily instead.
    let remote = origin(&[("notes.md", NOTES)]);
    let repository = Repository::open(remote.path()).expect("open");
    let workdir = repository.workdir().expect("not bare").to_path_buf();
    fs::write(workdir.join("later.md"), NOTES).expect("write");
    let mut index = repository.index().expect("index");
    index
        .add_all(["*"], git2::IndexAddOption::DEFAULT, None)
        .expect("add");
    index.write().expect("write index");
    let tree = repository
        .find_tree(index.write_tree().expect("write tree"))
        .expect("tree");
    let who = Signature::now("Test", "test@example.invalid").expect("signature");
    let parent = repository
        .head()
        .expect("head")
        .peel_to_commit()
        .expect("commit");
    // `Отчёт` in CP1251.
    let message = [0xCE, 0xF2, 0xF7, 0xB8, 0xF2];
    repository
        .commit_create_buffer(&who, &who, "", &tree, &[&parent])
        .map(|buffer| buffer.as_str().expect("header is ascii").to_string())
        .map(|header| {
            let mut raw = header.into_bytes();
            raw.extend_from_slice(&message);
            let id = repository
                .odb()
                .expect("odb")
                .write(git2::ObjectType::Commit, &raw);
            repository
                .reference("HEAD", id.expect("write commit"), true, "test")
                .expect("update HEAD");
        })
        .expect("build commit");

    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    sync_repository(request(&checkout, remote.path())).expect("clone");

    let status = repository_status(checkout.display().to_string())
        .expect("status")
        .expect("cloned");

    assert!(
        !status.head_summary.is_empty(),
        "the summary was dropped instead of being rendered lossily"
    );
}

#[test]
fn a_temporary_file_left_by_an_interrupted_write_is_not_committed() {
    // `Document::save` writes beside the note and renames over it. A process
    // killed between the two leaves that temporary behind, and committing it
    // would push a half-written note to the remote under a name of its own.
    let remote = origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    sync_repository(request(&checkout, remote.path())).expect("clone");

    fs::write(checkout.join("notes.md"), "# DONE Write the report\n").expect("write");
    fs::write(checkout.join(".markdown-org-abc123.tmp"), "half a file").expect("write");

    commit_changes(
        checkout.display().to_string(),
        "edit the report".to_string(),
        author(),
    )
    .expect("commit")
    .expect("something to commit");

    let repository = Repository::open(&checkout).expect("open");
    let tree = repository
        .head()
        .expect("head")
        .peel_to_tree()
        .expect("tree");
    assert!(
        tree.get_name(".markdown-org-abc123.tmp").is_none(),
        "the temporary was committed"
    );
}

#[test]
fn committing_outside_a_repository_is_an_error() {
    let plain = tempfile::tempdir().expect("tempdir");

    let error = commit_changes(
        plain.path().display().to_string(),
        "no repository here".to_string(),
        author(),
    )
    .expect_err("must fail");

    assert!(matches!(error, SyncError::Repository { .. }), "{error:?}");
}
