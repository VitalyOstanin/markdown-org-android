//! Tests for changing an entry by phrase.
//!
//! Every case says a sentence the way a person would, hands what the rules
//! made of it to [`apply_phrase`], and asserts on the whole file: a phrase
//! that moves a date must not touch the line under it, and an assertion
//! scoped to the edited line would not see that.
//!
//! The phrases are read against a fixed day, so what "на пятницу" resolves to
//! is a date these assertions can name.

use markdown_org_ffi::{apply_phrase, refine_phrase, EditError, PhraseDraft, PhraseField};

mod common;

use common::{body, target, vault};

/// Monday, 2026-08-31: the coming Friday is 2026-09-04.
const TODAY: &str = "2026-08-31";

const ENTRY: &str = "\
# TODO [#B] Позвонить врачу
`SCHEDULED: <2026-09-01 Вт 15:00 +1w>`

# TODO Полить цветы
";

/// The empty draft a phrase is read into.
fn empty() -> PhraseDraft {
    PhraseDraft {
        heading: String::new(),
        priority: None,
        keyword: None,
        date: None,
        time: None,
        repeater: None,
        status: None,
        cleared: Vec::new(),
    }
}

/// What the rules make of `phrase`, as of [`TODAY`].
fn said(phrase: &str) -> PhraseDraft {
    refine_phrase(
        empty(),
        phrase.to_string(),
        "ru,en".to_string(),
        TODAY.to_string(),
    )
    .expect("the phrase is read")
}

#[test]
fn a_keyword_said_in_words_replaces_the_one_the_heading_carries() {
    let vault = vault(ENTRY);

    let outcome = apply_phrase(
        target(vault.path(), 1, "Позвонить врачу"),
        said("отметь выполненной"),
    )
    .expect("edit");

    assert!(outcome.changed);
    assert_eq!(outcome.line, "# DONE [#B] Позвонить врачу");
    assert_eq!(
        body(vault.path()),
        "\
# DONE [#B] Позвонить врачу
`SCHEDULED: <2026-09-01 Вт 15:00 +1w>`

# TODO Полить цветы
"
    );
}

#[test]
fn a_day_said_in_words_moves_the_date_and_leaves_the_rest_of_the_line() {
    let vault = vault(ENTRY);

    apply_phrase(
        target(vault.path(), 1, "Позвонить врачу"),
        said("перенеси на пятницу"),
    )
    .expect("edit");

    // The hour, the repeater and the weekday spelling come along: the phrase
    // named a day and nothing else about the timestamp.
    assert_eq!(
        body(vault.path()),
        "\
# TODO [#B] Позвонить врачу
`SCHEDULED: <2026-09-04 Пт 15:00 +1w>`

# TODO Полить цветы
"
    );
}

#[test]
fn two_instructions_in_one_phrase_are_one_edit() {
    let vault = vault(ENTRY);

    let outcome = apply_phrase(
        target(vault.path(), 1, "Позвонить врачу"),
        said("перенеси на пятницу в 16:00 и сделай срочной"),
    )
    .expect("edit");

    assert!(outcome.rollback.is_some(), "one rollback for the phrase");
    assert_eq!(
        body(vault.path()),
        "\
# TODO [#A] Позвонить врачу
`SCHEDULED: <2026-09-04 Пт 16:00 +1w>`

# TODO Полить цветы
"
    );
}

#[test]
fn emptying_the_hour_leaves_the_day_and_the_repeater() {
    let vault = vault(ENTRY);

    apply_phrase(
        target(vault.path(), 1, "Позвонить врачу"),
        said("убрать время"),
    )
    .expect("edit");

    assert_eq!(
        body(vault.path()),
        "\
# TODO [#B] Позвонить врачу
`SCHEDULED: <2026-09-01 Вт +1w>`

# TODO Полить цветы
"
    );
}

#[test]
fn emptying_the_repeater_leaves_the_day_and_the_hour() {
    let vault = vault(ENTRY);

    apply_phrase(
        target(vault.path(), 1, "Позвонить врачу"),
        said("убрать повтор"),
    )
    .expect("edit");

    assert_eq!(
        body(vault.path()),
        "\
# TODO [#B] Позвонить врачу
`SCHEDULED: <2026-09-01 Вт 15:00>`

# TODO Полить цветы
"
    );
}

#[test]
fn emptying_the_date_takes_the_planning_line_out() {
    let vault = vault(ENTRY);

    apply_phrase(
        target(vault.path(), 1, "Позвонить врачу"),
        said("убрать дату"),
    )
    .expect("edit");

    assert_eq!(
        body(vault.path()),
        "\
# TODO [#B] Позвонить врачу

# TODO Полить цветы
"
    );
}

#[test]
fn emptying_the_priority_leaves_the_keyword() {
    let vault = vault(ENTRY);

    apply_phrase(
        target(vault.path(), 1, "Позвонить врачу"),
        said("без приоритета"),
    )
    .expect("edit");

    assert_eq!(
        body(vault.path()),
        "\
# TODO Позвонить врачу
`SCHEDULED: <2026-09-01 Вт 15:00 +1w>`

# TODO Полить цветы
"
    );
}

#[test]
fn an_entry_without_a_planning_line_is_given_one() {
    let vault = vault("# TODO Купить хлеб\nтекст\n");

    apply_phrase(
        target(vault.path(), 1, "Купить хлеб"),
        said("перенеси на пятницу в 16:00"),
    )
    .expect("edit");

    // The weekday follows the file, and this one has no dated line to follow:
    // the fallback is the English spelling, as it is for a date put on such an
    // entry by the buttons.
    assert_eq!(
        body(vault.path()),
        "\
# TODO Купить хлеб
`SCHEDULED: <2026-09-04 Fri 16:00>`
текст
"
    );
}

#[test]
fn an_hour_with_no_day_to_hang_it_on_is_refused() {
    let vault = vault("# TODO Купить хлеб\nтекст\n");

    let error = apply_phrase(target(vault.path(), 1, "Купить хлеб"), said("в 16:00"))
        .expect_err("no day to put it on");

    assert!(matches!(error, EditError::Unsupported { .. }), "{error:?}");
    // The file is what it was: an edit that cannot be made in full makes none.
    assert_eq!(body(vault.path()), "# TODO Купить хлеб\nтекст\n");
}

#[test]
fn a_phrase_with_a_leftover_is_refused_before_the_file_is_touched() {
    let vault = vault(ENTRY);

    let error = apply_phrase(
        target(vault.path(), 1, "Позвонить врачу"),
        said("перенеси на пятницу совсем"),
    )
    .expect_err("the leftover is refused");

    assert!(matches!(error, EditError::Unsupported { .. }), "{error:?}");
    assert_eq!(body(vault.path()), ENTRY);
}

#[test]
fn a_phrase_that_named_no_field_is_refused() {
    let vault = vault(ENTRY);

    let error = apply_phrase(target(vault.path(), 1, "Позвонить врачу"), empty())
        .expect_err("nothing to change");

    assert!(matches!(error, EditError::Unsupported { .. }), "{error:?}");
    assert_eq!(body(vault.path()), ENTRY);
}

#[test]
fn a_phrase_that_says_what_the_entry_says_writes_nothing() {
    let vault = vault(ENTRY);

    let outcome =
        apply_phrase(target(vault.path(), 1, "Позвонить врачу"), said("в работу")).expect("edit");

    assert!(!outcome.changed);
    assert!(outcome.rollback.is_none(), "nothing to take back");
    assert_eq!(body(vault.path()), ENTRY);
}

#[test]
fn a_deadline_said_outright_moves_the_date_onto_its_own_line() {
    let vault = vault(ENTRY);

    apply_phrase(
        target(vault.path(), 1, "Позвонить врачу"),
        said("перенеси к пятнице"),
    )
    .expect("edit");

    // The entry has no DEADLINE line, so one is written under the planning
    // block it already carries; the SCHEDULED line is left where it is.
    assert_eq!(
        body(vault.path()),
        "\
# TODO [#B] Позвонить врачу
`SCHEDULED: <2026-09-01 Вт 15:00 +1w>`
`DEADLINE: <2026-09-04 Пт>`

# TODO Полить цветы
"
    );
}

#[test]
fn the_fields_a_phrase_emptied_travel_back_in_the_draft() {
    // The draft crosses the boundary in both directions, so what a phrase
    // emptied has to survive the trip: a screen that hands the draft back for
    // a second phrase would otherwise lose it.
    let draft = said("убрать дату и без приоритета");

    assert_eq!(
        draft.cleared,
        vec![PhraseField::Date, PhraseField::Priority]
    );
    assert!(draft.date.is_none());
    assert!(draft.priority.is_none());
}
