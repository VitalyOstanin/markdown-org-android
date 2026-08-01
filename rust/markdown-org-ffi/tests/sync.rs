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
    adopt_directory, commit_changes, holds_repository, load_ca_bundle, push_changes,
    repository_status, sync_repository, take_remote_notes, Adoption, CommitAuthor, SyncError,
    SyncRequest,
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
    }
}

fn request_on(dir: &Path, url: &Path, branch: &str) -> SyncRequest {
    SyncRequest {
        branch: Some(branch.to_string()),
        ..request(dir, url)
    }
}

/// Name of the branch the local git created, rather than an assumed `master`:
/// `init.defaultBranch` is a machine setting and the tests must not depend on
/// what it says.
fn branch_of(repository: &Repository) -> String {
    repository
        .head()
        .expect("head")
        .shorthand()
        .expect("branch name")
        .to_string()
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
    //
    // The branch travels as a field of its own: the caller words the
    // explanation in the language of its reader and needs the name to put in
    // it, and taking it back out of an English sentence is not a way to get
    // it.
    match error {
        SyncError::Diverged { branch, .. } => assert_eq!("master", branch),
        other => panic!("got {other:?}"),
    }
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

    // How many files stand in the way is a number, not a word: `1 file` and
    // `2 files` are two forms in English and four in Russian, and only the
    // caller knows which language it is writing in.
    match &error {
        SyncError::Dirty { changed, .. } => assert_eq!(1, *changed),
        other => panic!("got {other:?}"),
    }
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

/// A repository someone created for their notes and has not written to yet is
/// the ordinary way to start, and it clones with no commits in it — HEAD names
/// a branch that does not exist. Every read of the checkout has to survive
/// that, or the application is unusable until the first commit arrives from
/// somewhere else.
#[test]
fn a_remote_without_commits_clones_into_a_checkout_the_status_can_read() {
    let remote = tempfile::tempdir().expect("tempdir");
    Repository::init_bare(remote.path()).expect("init bare");
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");

    let outcome = sync_repository(request(&checkout, remote.path())).expect("sync");

    assert!(outcome.cloned);
    assert!(
        outcome.head.head_id.is_empty(),
        "there is no commit to report, got {:?}",
        outcome.head.head_id
    );
    assert!(!outcome.head.branch.is_empty(), "the branch is still named");

    let status = repository_status(checkout.display().to_string())
        .expect("status")
        .expect("the directory holds a repository");
    assert!(status.head_id.is_empty());
    assert!(!status.dirty);
}

#[test]
fn an_edit_in_a_checkout_without_commits_is_committed_as_the_first_one() {
    let remote = tempfile::tempdir().expect("tempdir");
    Repository::init_bare(remote.path()).expect("init bare");
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    sync_repository(request(&checkout, remote.path())).expect("sync");

    fs::write(checkout.join("notes.md"), NOTES).expect("write");
    let id = commit_changes(
        checkout.display().to_string(),
        "Capture the first note".to_string(),
        author(),
    )
    .expect("commit")
    .expect("something to commit");

    let status = repository_status(checkout.display().to_string())
        .expect("status")
        .expect("cloned");
    assert_eq!(status.head_id, id);
    assert!(!status.dirty);
}

#[test]
fn syncing_a_checkout_without_commits_reports_no_change_rather_than_failing() {
    let remote = tempfile::tempdir().expect("tempdir");
    Repository::init_bare(remote.path()).expect("init bare");
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    sync_repository(request(&checkout, remote.path())).expect("clone");

    let outcome = sync_repository(request(&checkout, remote.path())).expect("second sync");

    assert!(!outcome.cloned);
    assert_eq!(outcome.commits_applied, 0);
}

/// Changing only the branch in the settings leaves the directory alone, so the
/// next sync meets a checkout of the previous branch. Cloning the new one is
/// not an option either — an uncommitted note would go with the directory.
#[test]
fn switching_the_branch_moves_the_checkout_onto_it() {
    let remote = origin(&[("notes.md", NOTES)]);
    let upstream = Repository::open(remote.path()).expect("open");
    let first = branch_of(&upstream);
    let head = upstream
        .head()
        .expect("head")
        .peel_to_commit()
        .expect("commit");
    upstream.branch("notes", &head, false).expect("branch");
    upstream.set_head("refs/heads/notes").expect("set head");
    commit(&upstream, &[("other.md", NOTES)], "only on notes");
    upstream
        .set_head(&format!("refs/heads/{first}"))
        .expect("set head back");

    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    sync_repository(request_on(&checkout, remote.path(), &first)).expect("clone");

    let outcome =
        sync_repository(request_on(&checkout, remote.path(), "notes")).expect("switch branch");

    assert_eq!(outcome.head.branch, "notes");
    assert_eq!(outcome.head.head_summary, "only on notes");
    assert!(
        checkout.join("other.md").exists(),
        "the file that only exists on the new branch has to reach the working copy"
    );
}

/// The one legitimate reason to commit without a parent is a repository that
/// has none yet. Everything else — a missing object, a HEAD pointing at an id
/// that is not there — used to reach the same branch and silently rewrote the
/// history as a root commit.
#[test]
fn a_head_whose_commit_is_gone_is_an_error_rather_than_a_new_root() {
    // The object is removed from the store rather than the ref repointed:
    // git2 refuses to point a reference at an id it cannot find, while a
    // missing object is what a truncated copy or a damaged card actually
    // leaves behind.
    let dir = origin(&[("notes.md", NOTES)]);
    let repository = Repository::open(dir.path()).expect("open");
    let id = repository
        .head()
        .expect("head")
        .peel_to_commit()
        .expect("commit")
        .id()
        .to_string();
    let (prefix, rest) = id.split_at(2);
    fs::remove_file(
        dir.path()
            .join(".git")
            .join("objects")
            .join(prefix)
            .join(rest),
    )
    .expect("remove the commit object");

    fs::write(dir.path().join("notes.md"), "# DONE Write the report\n").expect("write");

    let error = commit_changes(
        dir.path().display().to_string(),
        "edit on a broken checkout".to_string(),
        author(),
    )
    .expect_err("a commit on a broken HEAD must not be reported as done");

    let SyncError::Repository { detail } = &error else {
        panic!("expected a repository error, got {error:?}");
    };
    // libgit2 does stop the commit itself -- it refuses to write one whose
    // first parent is not the current tip -- but that message describes the
    // symptom. Swallowing the failure to read HEAD is what turned "the commit
    // this checkout is on cannot be read" into it.
    assert!(
        !detail.contains("current tip is not the first parent"),
        "the reported reason has to be the unreadable HEAD, got {detail:?}"
    );
}

/// `SyncError::Auth` is the variant that tells the user to replace the token.
/// The whole HTTP class used to map onto it, so a 404 from a mistyped
/// repository path asked for a new token instead.
#[test]
fn an_http_failure_that_is_not_about_credentials_is_not_reported_as_auth() {
    let error = SyncError::from(git2::Error::new(
        git2::ErrorCode::GenericError,
        git2::ErrorClass::Http,
        "unexpected http status code: 404",
    ));

    assert!(
        matches!(
            error,
            SyncError::Repository { .. } | SyncError::Network { .. }
        ),
        "got {error:?}"
    );
}

#[test]
fn a_rejected_credential_is_still_reported_as_auth() {
    let error = SyncError::from(git2::Error::new(
        git2::ErrorCode::Auth,
        git2::ErrorClass::Http,
        "too many redirects or authentication replays",
    ));

    assert!(matches!(error, SyncError::Auth { .. }), "got {error:?}");
}

/// A self-signed certificate, which is all the loader needs: it parses the
/// PEM and hands each certificate to libgit2, and neither step cares who
/// signed it.
fn self_signed_pem() -> String {
    use openssl::asn1::Asn1Time;
    use openssl::hash::MessageDigest;
    use openssl::pkey::PKey;
    use openssl::rsa::Rsa;
    use openssl::x509::{X509Name, X509};

    let key = PKey::from_rsa(Rsa::generate(2048).expect("rsa")).expect("key");
    let mut name = X509Name::builder().expect("name builder");
    name.append_entry_by_text("CN", "markdown-org test")
        .expect("common name");
    let name = name.build();

    let mut builder = X509::builder().expect("certificate builder");
    builder.set_version(2).expect("version");
    builder.set_subject_name(&name).expect("subject");
    builder.set_issuer_name(&name).expect("issuer");
    builder.set_pubkey(&key).expect("public key");
    builder
        .set_not_before(&Asn1Time::days_from_now(0).expect("now"))
        .expect("not before");
    builder
        .set_not_after(&Asn1Time::days_from_now(1).expect("tomorrow"))
        .expect("not after");
    builder.sign(&key, MessageDigest::sha256()).expect("sign");

    String::from_utf8(builder.build().to_pem().expect("pem")).expect("pem is ascii")
}

/// The store the certificates go into is global to libgit2, and the loader
/// used to guard it with a relaxed flag: two callers could fill it at once,
/// and one could see the flag before the certificates behind it.
#[test]
fn the_certificate_bundle_can_be_loaded_from_several_threads_at_once() {
    let pem = self_signed_pem();

    std::thread::scope(|scope| {
        for _ in 0..4 {
            scope.spawn(|| load_ca_bundle(pem.clone()).expect("load"));
        }
    });

    // Loading again is what every later sync would do; it must stay cheap and
    // must not report a failure.
    load_ca_bundle(pem).expect("load again");
}

#[test]
fn a_bundle_that_holds_no_certificate_is_rejected_rather_than_remembered() {
    let error = load_ca_bundle("not a certificate".to_string()).expect_err("must fail");

    assert!(matches!(error, SyncError::Repository { .. }), "{error:?}");
    // Not remembering the failure is the point: a sync that went ahead with an
    // empty store would fail on every connection.
    load_ca_bundle(self_signed_pem()).expect("a good bundle still loads");
}

/// Asked before every commit, so it cannot be the full status walk it used to
/// borrow: that one collects untracked files across the whole checkout.
#[test]
fn a_directory_is_told_from_a_checkout_without_reading_its_state() {
    let remote = origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    let plain = local.path().join("plain");
    fs::create_dir(&plain).expect("create");

    assert!(!holds_repository(plain.display().to_string()));
    assert!(!holds_repository(checkout.display().to_string()));

    sync_repository(request(&checkout, remote.path())).expect("clone");

    assert!(holds_repository(checkout.display().to_string()));
}

/// Without these the wait is whatever the operating system decides, which on
/// a phone that has wandered onto a captive portal is "until the user gives
/// up".
#[test]
fn a_sync_bounds_how_long_it_waits_on_the_network() {
    let remote = origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");

    sync_repository(request(&checkout, remote.path())).expect("clone");

    // Safe: the values are read after the sync applied them, and nothing else
    // in this test writes them.
    let (connect, server) = unsafe {
        (
            git2::opts::get_server_connect_timeout_in_milliseconds().expect("connect timeout"),
            git2::opts::get_server_timeout_in_milliseconds().expect("server timeout"),
        )
    };

    assert!(connect > 0, "the connect timeout is still the default");
    assert!(server > 0, "the request timeout is still the default");
}

/// The fast-forward checks out with `force()`, which overwrites whatever sits
/// in the way — including a file git is not tracking. The check that guards it
/// used to ignore untracked files entirely, so a note captured before the
/// first commit disappeared without a word.
#[test]
fn an_untracked_file_the_update_would_overwrite_stops_the_sync() {
    let remote = origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    sync_repository(request(&checkout, remote.path())).expect("clone");

    let upstream = Repository::open(remote.path()).expect("open");
    commit(
        &upstream,
        &[("inbox.md", "# TODO Theirs\n")],
        "add inbox.md",
    );
    fs::write(checkout.join("inbox.md"), "# TODO Captured here\n").expect("write");

    let error = sync_repository(request(&checkout, remote.path())).expect_err("must fail");

    assert!(matches!(error, SyncError::Dirty { .. }), "got {error:?}");
    assert_eq!(
        fs::read_to_string(checkout.join("inbox.md")).expect("read"),
        "# TODO Captured here\n",
        "the note captured locally must survive a refused sync",
    );
}

/// The token travels as the HTTP password, so the scheme decides whether it
/// travels in the clear. The platform's ban on cleartext traffic does not
/// reach this stack: the request leaves from libgit2 over a vendored OpenSSL,
/// neither of which reads Android's network security policy.
#[test]
fn an_address_that_would_carry_the_token_in_the_clear_is_refused() {
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");

    for url in [
        "http://127.0.0.1:1/notes.git",
        "git://127.0.0.1:1/notes.git",
        "ssh://git@127.0.0.1:1/notes.git",
        "git@127.0.0.1:notes.git",
    ] {
        let error = sync_repository(SyncRequest {
            dir: checkout.display().to_string(),
            url: url.to_string(),
            token: Some("secret".to_string()),
            branch: None,
        })
        .expect_err("must fail");

        assert!(
            matches!(error, SyncError::Address { .. }),
            "{url} got {error:?}",
        );
        assert!(
            !checkout.exists(),
            "{url} left a directory behind, so the address reached the transport",
        );
    }
}

/// The check belongs before the fetch as much as before the clone: settings
/// changed under an existing checkout go down this path.
#[test]
fn a_refused_address_stops_a_fetch_as_well_as_a_clone() {
    let remote = origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    sync_repository(request(&checkout, remote.path())).expect("clone");

    let error = sync_repository(SyncRequest {
        dir: checkout.display().to_string(),
        url: "http://127.0.0.1:1/notes.git".to_string(),
        token: Some("secret".to_string()),
        branch: None,
    })
    .expect_err("must fail");

    assert!(matches!(error, SyncError::Address { .. }), "{error:?}");
}

/// A checkout on disk is what every test here uses, and the production remote
/// is `https://` — neither may be caught by the guard.
#[test]
fn a_local_path_and_a_file_url_stay_usable() {
    let remote = origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");

    sync_repository(request(&local.path().join("by-path"), remote.path())).expect("path");

    let by_url = local.path().join("by-url");
    sync_repository(SyncRequest {
        dir: by_url.display().to_string(),
        url: format!("file://{}", remote.path().display()),
        token: None,
        branch: None,
    })
    .expect("file url");
}

/// The remote as a server holds it: bare, so that pushing to the branch it has
/// checked out is not a case that can arise here and hide one that can.
fn bare_origin(files: &[(&str, &str)]) -> tempfile::TempDir {
    let seed = origin(files);
    let dir = tempfile::tempdir().expect("tempdir");
    let mut builder = git2::build::RepoBuilder::new();
    builder.bare(true);
    builder
        .clone(&seed.path().display().to_string(), dir.path())
        .expect("clone bare");
    dir
}

/// Somebody else committing to the same remote, through a checkout of their
/// own — which is the only way to put a commit into a bare repository.
fn another_device_commits(remote: &Path, files: &[(&str, &str)], message: &str) {
    let elsewhere = tempfile::tempdir().expect("tempdir");
    let theirs = Repository::clone(&remote.display().to_string(), elsewhere.path()).expect("clone");
    commit(&theirs, files, message);

    let branch = branch_of(&theirs);
    theirs
        .find_remote("origin")
        .expect("origin")
        .push(&[format!("refs/heads/{branch}:refs/heads/{branch}")], None)
        .expect("push");
}

/// What the remote has on `branch`, as a commit summary.
fn summary_at(remote: &Path, branch: &str) -> String {
    let repository = Repository::open(remote).expect("open");
    let reference = repository
        .find_reference(&format!("refs/heads/{branch}"))
        .expect("branch");

    let summary = reference
        .peel_to_commit()
        .expect("commit")
        .summary()
        .expect("read the summary")
        .expect("a message")
        .to_string();

    summary
}

#[test]
fn an_edit_committed_here_reaches_the_remote() {
    let remote = bare_origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    sync_repository(request(&checkout, remote.path())).expect("clone");

    fs::write(checkout.join("notes.md"), "# DONE Write the report\n").expect("write");
    commit_changes(
        checkout.display().to_string(),
        "mark it done".to_string(),
        author(),
    )
    .expect("commit")
    .expect("a commit was made");

    let outcome = push_changes(request(&checkout, remote.path())).expect("push");

    assert_eq!(outcome.commits_pushed, 1);
    assert_eq!(outcome.head.unpushed, 0, "nothing may be left behind");
    assert_eq!(
        summary_at(remote.path(), &outcome.head.branch),
        "mark it done",
    );
}

#[test]
fn pushing_a_checkout_the_remote_is_level_with_hands_over_nothing() {
    let remote = bare_origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    sync_repository(request(&checkout, remote.path())).expect("clone");

    let outcome = push_changes(request(&checkout, remote.path())).expect("push");

    assert_eq!(outcome.commits_pushed, 0);
}

#[test]
fn a_remote_that_moved_on_refuses_the_push_and_keeps_the_commits_here() {
    let remote = bare_origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    sync_repository(request(&checkout, remote.path())).expect("clone");

    // Both sides move, and neither knows about the other: the same situation
    // `Diverged` describes, met from the other end.
    another_device_commits(remote.path(), &[("theirs.md", NOTES)], "theirs");
    let mine = Repository::open(&checkout).expect("open");
    commit(&mine, &[("mine.md", NOTES)], "mine");

    let error = push_changes(request(&checkout, remote.path())).expect_err("must fail");

    match &error {
        SyncError::Rejected { branch, .. } => assert_eq!("master", branch),
        other => panic!("got {other:?}"),
    }
    // A refused push is not a lost commit: it is still here, and still
    // counted as owed to the remote.
    let status = repository_status(checkout.display().to_string())
        .expect("status")
        .expect("a repository");
    assert_eq!(status.unpushed, 1);
    assert_eq!(summary_at(remote.path(), &status.branch), "theirs");
}

#[test]
fn the_status_counts_the_commits_the_remote_has_not_been_given() {
    let remote = bare_origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    sync_repository(request(&checkout, remote.path())).expect("clone");

    let fresh = repository_status(checkout.display().to_string())
        .expect("status")
        .expect("a repository");
    assert_eq!(fresh.unpushed, 0);

    let mine = Repository::open(&checkout).expect("open");
    commit(&mine, &[("mine.md", NOTES)], "mine");
    commit(&mine, &[("mine-too.md", NOTES)], "mine too");

    let owed = repository_status(checkout.display().to_string())
        .expect("status")
        .expect("a repository");
    assert_eq!(owed.unpushed, 2);

    push_changes(request(&checkout, remote.path())).expect("push");

    let settled = repository_status(checkout.display().to_string())
        .expect("status")
        .expect("a repository");
    assert_eq!(settled.unpushed, 0);
}

/// A branch the remote does not have is wholly unpushed rather than level:
/// there is nothing to compare against, and calling that "up to date" would
/// leave the first push of a new branch undone.
#[test]
fn a_branch_the_remote_does_not_have_counts_as_wholly_unpushed() {
    let remote = bare_origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    sync_repository(request(&checkout, remote.path())).expect("clone");

    let mine = Repository::open(&checkout).expect("open");
    let head = mine.head().expect("head").peel_to_commit().expect("commit");
    mine.branch("phone", &head, false).expect("branch");
    mine.set_head("refs/heads/phone").expect("set head");

    let owed = repository_status(checkout.display().to_string())
        .expect("status")
        .expect("a repository");
    assert_eq!(owed.unpushed, 1, "the one commit the branch holds");

    let outcome = push_changes(request_on(&checkout, remote.path(), "phone")).expect("push");

    assert_eq!(outcome.commits_pushed, 1);
    assert_eq!(summary_at(remote.path(), "phone"), "initial");
}

/// The guard on the address covers a push as well as a fetch: a push offers
/// both the token and the notes to whoever answers.
#[test]
fn a_refused_address_stops_a_push() {
    let remote = bare_origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    sync_repository(request(&checkout, remote.path())).expect("clone");

    let error = push_changes(SyncRequest {
        dir: checkout.display().to_string(),
        url: "http://127.0.0.1:1/notes.git".to_string(),
        token: Some("secret".to_string()),
        branch: None,
    })
    .expect_err("must fail");

    assert!(matches!(error, SyncError::Address { .. }), "{error:?}");
}

#[test]
fn pushing_from_a_directory_that_holds_no_repository_is_an_error() {
    let local = tempfile::tempdir().expect("tempdir");

    let error = push_changes(request(local.path(), local.path())).expect_err("must fail");

    assert!(matches!(error, SyncError::Repository { .. }), "{error:?}");
}

/// The way in for notes kept on the device before any remote was named: the
/// files stay where they are and become the first commit.
#[test]
fn a_directory_of_notes_becomes_a_checkout_without_losing_them() {
    let remote = bare_origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let notes = local.path().join("notes");
    fs::create_dir_all(&notes).expect("create");
    fs::write(notes.join("mine.md"), NOTES).expect("write");

    let outcome = adopt_directory(request(&notes, remote.path()), author()).expect("adopt");

    // The remote already holds notes of its own, and these share no history
    // with them: nothing is sent and nothing is overwritten.
    match &outcome {
        Adoption::Unrelated { branch } => assert_eq!("master", branch),
        other => panic!("got {other:?}"),
    }
    assert!(
        notes.join("mine.md").exists(),
        "the notes must still be there"
    );
    assert!(holds_repository(notes.display().to_string()));

    let status = repository_status(notes.display().to_string())
        .expect("status")
        .expect("a repository");
    assert_eq!(status.head_summary, "Notes already in this directory");
    assert_eq!(status.unpushed, 1);
}

#[test]
fn a_directory_of_notes_is_published_when_the_remote_has_none() {
    // A remote with no commits at all: the ordinary way to start from a
    // repository created and left alone.
    let remote = tempfile::tempdir().expect("tempdir");
    Repository::init_bare(remote.path()).expect("init bare");
    let local = tempfile::tempdir().expect("tempdir");
    let notes = local.path().join("notes");
    fs::create_dir_all(&notes).expect("create");
    fs::write(notes.join("mine.md"), NOTES).expect("write");

    let outcome =
        adopt_directory(request_on(&notes, remote.path(), "main"), author()).expect("adopt");

    match &outcome {
        Adoption::Published { commits_pushed } => assert_eq!(1, *commits_pushed),
        other => panic!("got {other:?}"),
    }
    assert_eq!(
        summary_at(remote.path(), "main"),
        "Notes already in this directory"
    );
}

#[test]
fn an_empty_directory_takes_what_the_remote_holds() {
    let remote = bare_origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let notes = local.path().join("notes");
    fs::create_dir_all(&notes).expect("create");

    let outcome = adopt_directory(request(&notes, remote.path()), author()).expect("adopt");

    assert!(matches!(outcome, Adoption::Took), "{outcome:?}");
    assert!(
        notes.join("notes.md").exists(),
        "the remote's notes have to land"
    );
}

#[test]
fn taking_the_remote_keeps_what_was_in_the_directory_on_a_branch_of_its_own() {
    let remote = bare_origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let notes = local.path().join("notes");
    fs::create_dir_all(&notes).expect("create");
    fs::write(notes.join("mine.md"), NOTES).expect("write");
    adopt_directory(request(&notes, remote.path()), author()).expect("adopt");

    take_remote_notes(request(&notes, remote.path())).expect("take");

    // What the remote holds is on disk, and what was here is not deleted:
    // it is a commit on a branch, readable by any git client.
    assert!(notes.join("notes.md").exists());
    assert!(!notes.join("mine.md").exists());
    let repository = Repository::open(&notes).expect("open");
    let kept = repository
        .find_branch(markdown_org_ffi::KEPT_BRANCH, git2::BranchType::Local)
        .expect("the kept branch");
    let summary = kept
        .get()
        .peel_to_commit()
        .expect("commit")
        .summary()
        .expect("read the summary")
        .expect("a message")
        .to_string();
    assert_eq!(summary, "Notes already in this directory");
}

#[test]
fn a_directory_that_is_already_a_checkout_is_refused_rather_than_given_a_second_origin() {
    let remote = bare_origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let checkout = local.path().join("notes");
    sync_repository(request(&checkout, remote.path())).expect("clone");

    let error =
        adopt_directory(request(&checkout, remote.path()), author()).expect_err("must fail");

    assert!(matches!(error, SyncError::Repository { .. }), "{error:?}");
}

#[test]
fn taking_a_remote_branch_that_was_never_fetched_is_an_error_rather_than_a_wipe() {
    let remote = bare_origin(&[("notes.md", NOTES)]);
    let local = tempfile::tempdir().expect("tempdir");
    let notes = local.path().join("notes");
    fs::create_dir_all(&notes).expect("create");
    fs::write(notes.join("mine.md"), NOTES).expect("write");
    adopt_directory(request(&notes, remote.path()), author()).expect("adopt");

    let error = take_remote_notes(request_on(&notes, remote.path(), "never-fetched"))
        .expect_err("must fail");

    assert!(matches!(error, SyncError::Repository { .. }), "{error:?}");
    assert!(
        notes.join("mine.md").exists(),
        "nothing may be written over"
    );
}
