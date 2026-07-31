# ADR-0013: The notes directory is a path, and may be outside the application's storage

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-07-31). Amended by
[ADR-0017](0017-open-a-repository-the-platform-owns.md), which lifts the check
that kept git from working in a directory outside the application's storage.

## Context

Until now the notes lived in one place: a directory inside the application's
own storage, filled by a clone. That covers a phone whose notes arrive over
git and nothing else. It does not cover notes that are already on the device —
kept in a directory a file manager can reach, edited by another application,
copied over a cable — and it leaves no way to point the agenda at them.

Reaching a directory of the user's choosing on Android is not one decision but
two, because there are two mechanisms and they are not interchangeable:

- the Storage Access Framework, where the user picks a directory and the
  application receives a `content://` tree URI it can open through
  `DocumentFile`;
- all files access (`MANAGE_EXTERNAL_STORAGE`), a special permission granted
  in a screen of the platform, which gives ordinary path access to the shared
  storage.

The core decides between them. It clones and fast-forwards with libgit2 and
walks the directory with `std::fs`, and both take a path — see
[ADR-0001](0001-extractor-in-process-over-uniffi.md) and
[ADR-0005](0005-vendored-tls-and-libgit2.md). A tree URI is meaningless to
either: honouring it would mean copying the whole checkout into private
storage before every scan and back after every edit, or reimplementing the
walk and the repository access in Kotlin over `DocumentFile` — a second
implementation of what the core already is.

The permission is not free. Google Play treats all files access as a
restricted permission: an application that declares it has to justify the use
in a declaration form, and the accepted categories are narrow — file managers,
backup and restore, anti-virus, document management. A notes client is not an
obvious member of that list, so declaring it puts publication on Google Play
in question. Distribution outside Play — an APK from the project's releases,
or F-Droid — is unaffected.

## Decision

The notes directory is a path, chosen in the settings form, and empty means
the directory inside the application's own storage, which stays the default.

- The manifest declares `MANAGE_EXTERNAL_STORAGE`, and the storage
  permissions capped at API 29 for the devices from before it.
- The permission is asked for only when the chosen directory lies outside the
  application's own storage. The default path needs nothing, so an install
  that never changes the directory never sees the request.
- The form refuses a path that cannot hold the notes before anything is
  stored, and tells the missing permission apart from the two failures no
  permission would fix — a relative path, and a path that is a plain file.
- Moving happens through `NotesArea.useDirectory`, under the same lock as
  every other operation on the working copy
  ([ADR-0010](0010-one-writer-for-the-working-copy.md)). Neither directory is
  emptied: what was in the previous one may be a checkout with commits that
  exist nowhere else.
- The system picker is offered, but only as a way to fill the field in: the
  tree it returns is turned into a path (`primary:Documents/notes` becomes
  `/storage/emulated/0/Documents/notes`) and the URI is not kept, nor is a
  persistable permission taken for it. A phone keyboard is a poor way to type
  a path — on the device this was written for, `/sdcard/…` came out as
  `/SD card/…`.
- A tree whose identifier does not name a directory on the shared storage — a
  cloud provider, say — fills nothing in. Reading through `DocumentFile` is
  not a fallback, for the reason above.
- An empty repository address is no longer a refusal: a form that only sets
  the directory is what notes already on the device need, and the remote
  stored earlier is left alone.

## Consequences

- Notes already on the device can be opened without going through a remote at
  all, and a directory shared with another application on the phone is
  readable by both.
- Publication on Google Play needs a declaration for the permission, and may
  be refused. That is accepted knowingly: the distribution the project has is
  the APK in its releases.
- The application can read and write the whole of the shared storage while the
  permission is granted, which is more than it needs and more than a tree URI
  would have given. What it actually touches is the one directory; that is a
  property of the code, not something the platform enforces here.
- A directory on a removable card mounted elsewhere, or one that disappears,
  fails at the next scan rather than at the choice. The message names the
  directory.
- Devices before Android 11 fall back to the ordinary storage permission,
  which is a runtime dialog rather than a settings screen. The application
  asks the platform which of the two applies rather than deciding by version
  at the call site.

## References

- `app/src/main/kotlin/…/core/NotesLocation.kt` — the stored choice and the
  rule about which directories are allowed.
- `app/src/main/kotlin/…/core/StorageAccess.kt` — what the platform is asked,
  and how the grant is requested on either side of Android 11.
- `app/src/main/kotlin/…/core/NotesStore.kt` — `useDirectory`, the move under
  the lock.
- `app/src/main/AndroidManifest.xml` — the declared permissions.
- [Manage all files on a storage
  device](https://developer.android.com/training/data-storage/manage-all-files)
  — the permission and the terms it comes with.
