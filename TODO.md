# TODO

Work that is understood but deliberately not done yet.

## Table of contents

- [One set of notes on more than one remote](#one-set-of-notes-on-more-than-one-remote)
- [Weekday names beyond Russian and English](#weekday-names-beyond-russian-and-english)
- [Tooltips beyond the agenda screen](#tooltips-beyond-the-agenda-screen)
- [Unicode normalisation when a heading can be typed](#unicode-normalisation-when-a-heading-can-be-typed)
- [Publishing to the app stores](#publishing-to-the-app-stores)

## One set of notes on more than one remote

Not the same as the collections the application already has. Those are several
sets of notes, each in its own repository and its own directory; this one is a
single set of notes kept on several servers at once — the same commits on
GitHub and on GitLab, so that one host being unreachable, throttled or gone
does not take the notes with it.

Git already models this: one working copy can carry several remotes, and the
commits are the same objects wherever they are pushed. What the application
does not have is anything to drive it — the settings hold one URL, and the
core's sync fetches from and fast-forwards against a single remote.

| № | Part          | What changes                                                                                                                             |
|---|---------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | Settings      | A list of remotes for the one checkout, with one of them named as the one to pull from; a token per remote, since they are different hosts. |
| 2 | The core      | `sync_repository` takes the remotes rather than one URL: fetch and fast-forward from the primary, push to each of the others.               |
| 3 | Partial failure | A push that failed to one host while the others went through is not a failed sync — it is a state, and it has to be reported per remote and retried on the next run rather than silently dropped. |
| 4 | Divergence    | Two remotes can hold different histories once a push has failed to one of them for a while. Only fast-forward is on offer today, so the answer is to say which remote is behind rather than to merge. |
| 5 | The screens   | The banner names which remotes are current and which are behind, and the settings add and remove remotes.                                   |

Worth doing together with the section above, or after it: both replace the
single stored triple of URL, branch and token with a list, and writing that
migration twice is the part not worth repeating.

## Weekday names beyond Russian and English

Moving a date rewrites the weekday token to match, in the language the file
already uses. The two languages the ecosystem knows are Russian and English
(`SUPPORTED_LOCALES` in the extractor), and `weekday_like` in
`rust/markdown-org-ffi/src/planning.rs` refuses anything else with
`Unsupported` rather than replacing, say, Ukrainian `Нд` with Russian `Вс`.

For a file written in a third language that means its dates cannot be moved
from the phone at all. Adding one is a table in the extractor's `locale`
module plus the matching entry in `SUPPORTED_LOCALES`, so that reading and
writing agree; doing it here alone would let the application write names the
extractor cannot read back.

## Tooltips beyond the agenda screen

Every screen now carries them, and what is left is the wording rather than the
mechanism.

The agenda screen: the header icons, the layout switch, the collection chips,
the sync banner and the two lines under it, the scan notices, the band menu
and each of its items; the row tooltip names the collection the dot stands
for. The sheet of actions: what each action writes into the note — the
keyword, the cookie, the date and what stays as written. The settings screen:
every button, both checkboxes, the heading of the collections and the two
lines at the bottom; the fields answer with a line under them instead, which
is readable without a press. The licence screen: the card and the way back.

Wording follows the VS Code extension where the two show the same thing, so
the same task reads alike on the phone and in the editor.

Two rules the layout imposes, both learnt from a test that failed:

| № | Rule                                                                                                     |
|---|-----------------------------------------------------------------------------------------------------------|
| 1 | A tag belongs on the tooltip rather than on what it wraps — the box merges the semantics of its content, and a `testTag` on a bare `Text` inside stops existing in the merged tree. A control with a semantics node of its own — a button, a chip — keeps its tag either way. |
| 2 | A weight must not be handed to the tooltip: the row measures its own children, and the box is not the button. Two buttons sharing a row are wrapped as a row, not one by one — the second one went off the screen otherwise. |

## Unicode normalisation when a heading can be typed

`Document::heading` compares the heading in the file with the one the caller
believes is there byte for byte. Today both come from the same scan and pass
through Kotlin without loss, so a NFC/NFD difference cannot arise.

It can as soon as a heading is typed — a system keyboard hands over decomposed
diacritics — and a visually identical heading would then be refused as
`Stale`. The answer is to compare after NFC normalisation
(`unicode-normalization`), and it is worth adding together with whatever first
lets a heading be entered.

## Publishing to the app stores

The only way to install the application today is the GitHub release: a tag
builds a signed APK and publishes it, and a phone downloads that file. That
asks the user to allow installs from an unknown source and leaves updates to
whoever remembers to check the releases page. A store does both for them.

Where the build already is:

| № | Piece                     | State                                                                                       |
|---|---------------------------|---------------------------------------------------------------------------------------------|
| 1 | Application id            | `io.github.vitalyostanin.markdownorg`, which any store will take as the package name         |
| 2 | Signing                   | a release keystore held as a CI secret, read from `APP_KEYSTORE_*`; a local build is unsigned |
| 3 | Artefact                  | APK only. Google Play takes an Android App Bundle, so `bundleRelease` has to be added         |
| 4 | Version                   | `versionCode` / `versionName` come from the tag, which is what a store increments against     |
| 5 | Licence notices           | already generated for the APK, and every store asks for the same list                         |
| 6 | Store listing             | `fastlane/metadata/android/{en-US,ru}` holds the title, both descriptions, the icon and four screenshots per language; no feature graphic and no privacy policy yet |
| 7 | Releases                  | every tag so far is a prerelease `v0.1.0-build.<run>`, and a store that tracks releases has nothing marked final to pick up |

The listing was written against the IzzyOnDroid policy, read on 2026-08-10
(<https://izzyondroid.org/docs/general/AppInclusionPolicy/>): fastlane
metadata with a short description, a full description, an icon and
screenshots; an APK signed with the developer's release key, carrying neither
`debuggable` nor `testOnly`, published on the releases page; at most 30 MB per
application, against the 24.8 MB the last build weighs; an OSI licence, which
MIT is; no proprietary component, tracker or analytics, of which the
application has none.

The icon is rendered from the launcher vector by `tools/store-icon.sh` rather
than drawn a second time. The screenshots were taken on the emulator, in both
languages, of the sample notes the application writes on first run — which are
written in the language of the device, so the Russian listing shows Russian
headings throughout. The emulator was put into Russian per application
(`cmd locale set-app-locales`) rather than as a whole: changing the system
language restarts the shell, and the emulator came back with an unresponsive
system UI over everything.

What each store wants. Every requirement below is from general knowledge and
has to be re-checked against the store's own documentation before anything is
submitted — these rules change often, and the numbers most of all.

| № | Store                       | What it costs and what it asks for                                                                                     |
|---|-----------------------------|------------------------------------------------------------------------------------------------------------------------|
| 1 | Google Play                 | a one-off developer fee, an AAB rather than an APK, Play App Signing, a data-safety declaration, a target API level no older than the current floor, and — for a personal account opened recently — a closed test with a number of testers over a number of days before production is unlocked |
| 2 | F-Droid                     | no fee and no account: a metadata file in their repository, a build from source on their own machines, and no proprietary dependency. Their build would have to reproduce the NDK and Rust toolchain this project pins |
| 3 | IzzyOnDroid                 | the lightest of them: a repository that publishes signed APKs on its releases page is enough, and the store tracks the tags. Closest to how the project already publishes |
| 4 | RuStore                     | a Russian developer account; takes an APK, which is what the build already produces |
| 5 | Huawei AppGallery           | an account and their own review; no Google Play Services in the application, which this one does not use anyway |
| 6 | Amazon Appstore, Galaxy Store | an account each, an APK, and a listing per store |

Two things have to be decided before any of it, and they are the reason this
is a note rather than a task:

1. **Who holds the signing key.** Google Play App Signing keeps the upload key
   separate from the one the store signs with, and an application published
   both there and elsewhere is then signed by two different keys — the same
   package cannot be updated across the two. The usual answer is to pick one
   distribution as the primary and let the others carry a different package
   name, or to publish everywhere with the project's own key and skip Play.
2. **Paying the developer fee.** The Google and Apple fees are charged in a way
   a Russian card does not satisfy, so the account itself is the obstacle
   rather than the review. Until that is answered, F-Droid, IzzyOnDroid and
   RuStore are the reachable ones.

The cheapest first step is IzzyOnDroid: it wants what the build already emits.
F-Droid is the one worth the work — it is where a notes application of this
kind is looked for — and the effort there is making the build reproduce on
their infrastructure, not the paperwork.

What is left before the request to include it can be filed:

| № | Step                                                                                        |
|---|---------------------------------------------------------------------------------------------|
| 1 | Cut a release that is not a prerelease, so there is a version for a store to track          |
| 2 | Write `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` for that release      |
| 3 | Decide whether a feature graphic is worth drawing — the listing renders without one          |
| 4 | File the inclusion request, naming the repository and the tag pattern the APK is published under |
