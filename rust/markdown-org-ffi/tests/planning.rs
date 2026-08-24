//! Tests for moving a planning date and for completing a task.
//!
//! The repeater cases follow upstream Emacs Org-mode `org-auto-repeat-maybe`
//! (lisp/org.el): a `+` repeater takes exactly one step from the date in the
//! file, `++` keeps stepping until it passes today, and `.+` restarts from
//! today. Marking a repeating task done therefore moves it forward and leaves
//! it open rather than closing it.

use markdown_org_ffi::{
    canonical_repeater, complete_task, set_planning, shift_planning, EditError, PlanningKeyword,
};

mod common;

use common::{body, target, vault};

const TODAY: &str = "2026-07-28";

#[test]
fn shifting_a_scheduled_date_keeps_everything_around_it() {
    let vault = vault("# TODO Write the report\n`SCHEDULED: <2026-07-28 Tue 10:00 -2d>`\n");

    let outcome = shift_planning(
        target(vault.path(), 1, "Write the report"),
        PlanningKeyword::Scheduled,
        1,
    )
    .expect("shift");

    assert_eq!(outcome.line, "`SCHEDULED: <2026-07-29 Wed 10:00 -2d>`");
    assert_eq!(
        body(vault.path()),
        "# TODO Write the report\n`SCHEDULED: <2026-07-29 Wed 10:00 -2d>`\n"
    );
}

#[test]
fn shifting_backwards_works_the_same_way() {
    let vault = vault("# TODO Write the report\n`DEADLINE: <2026-07-28 Tue>`\n");

    let outcome = shift_planning(
        target(vault.path(), 1, "Write the report"),
        PlanningKeyword::Deadline,
        -7,
    )
    .expect("shift");

    assert_eq!(outcome.line, "`DEADLINE: <2026-07-21 Tue>`");
}

#[test]
fn a_localised_weekday_is_rewritten_in_the_same_language() {
    let vault = vault("# TODO Отчёт\n`SCHEDULED: <2026-07-28 Вт>`\n");

    let outcome = shift_planning(
        target(vault.path(), 1, "Отчёт"),
        PlanningKeyword::Scheduled,
        1,
    )
    .expect("shift");

    assert_eq!(outcome.line, "`SCHEDULED: <2026-07-29 Ср>`");
}

#[test]
fn a_full_weekday_name_stays_full() {
    let vault = vault("# TODO Write the report\n`SCHEDULED: <2026-07-28 Tuesday>`\n");

    let outcome = shift_planning(
        target(vault.path(), 1, "Write the report"),
        PlanningKeyword::Scheduled,
        1,
    )
    .expect("shift");

    assert_eq!(outcome.line, "`SCHEDULED: <2026-07-29 Wednesday>`");
}

#[test]
fn the_other_planning_line_is_left_alone() {
    let vault = vault(
        "# TODO Write the report\n`SCHEDULED: <2026-07-28 Tue>`\n`DEADLINE: <2026-07-30 Thu>`\n",
    );

    shift_planning(
        target(vault.path(), 1, "Write the report"),
        PlanningKeyword::Scheduled,
        1,
    )
    .expect("shift");

    assert_eq!(
        body(vault.path()),
        "# TODO Write the report\n`SCHEDULED: <2026-07-29 Wed>`\n`DEADLINE: <2026-07-30 Thu>`\n"
    );
}

#[test]
fn shifting_a_planning_line_the_task_does_not_have_is_refused() {
    let vault = vault("# TODO Write the report\n`SCHEDULED: <2026-07-28 Tue>`\n");

    let error = shift_planning(
        target(vault.path(), 1, "Write the report"),
        PlanningKeyword::Deadline,
        1,
    )
    .expect_err("must refuse");

    assert!(
        matches!(error, EditError::NoPlanningLine { .. }),
        "{error:?}"
    );
}

#[test]
fn completing_a_plain_task_marks_it_done() {
    let vault = vault("# TODO Write the report\n`SCHEDULED: <2026-07-28 Tue>`\n");

    let outcome = complete_task(
        target(vault.path(), 1, "Write the report"),
        TODAY.to_string(),
    )
    .expect("complete");

    assert!(!outcome.repeated);
    assert_eq!(outcome.heading, "# DONE Write the report");
    assert_eq!(
        body(vault.path()),
        "# DONE Write the report\n`SCHEDULED: <2026-07-28 Tue>`\n"
    );
}

#[test]
fn completing_a_cumulative_repeater_takes_one_step() {
    // `+1w` from a date in the past lands one week later, still in the past:
    // upstream takes exactly one step and does not catch up.
    let vault = vault("# TODO Water the plants\n`SCHEDULED: <2026-07-01 Wed +1w>`\n");

    let outcome = complete_task(
        target(vault.path(), 1, "Water the plants"),
        TODAY.to_string(),
    )
    .expect("complete");

    assert!(outcome.repeated);
    assert_eq!(
        body(vault.path()),
        "# TODO Water the plants\n`SCHEDULED: <2026-07-08 Wed +1w>`\n"
    );
}

#[test]
fn completing_a_catch_up_repeater_passes_today() {
    let vault = vault("# TODO Water the plants\n`SCHEDULED: <2026-07-01 Wed ++1w>`\n");

    complete_task(
        target(vault.path(), 1, "Water the plants"),
        TODAY.to_string(),
    )
    .expect("complete");

    // 1, 8, 15, 22, 29 July: the first Wednesday strictly after 28 July.
    assert_eq!(
        body(vault.path()),
        "# TODO Water the plants\n`SCHEDULED: <2026-07-29 Wed ++1w>`\n"
    );
}

#[test]
fn completing_a_restart_repeater_counts_from_today() {
    let vault = vault("# TODO Water the plants\n`SCHEDULED: <2026-07-01 Wed .+3d>`\n");

    complete_task(
        target(vault.path(), 1, "Water the plants"),
        TODAY.to_string(),
    )
    .expect("complete");

    assert_eq!(
        body(vault.path()),
        "# TODO Water the plants\n`SCHEDULED: <2026-07-31 Fri .+3d>`\n"
    );
}

#[test]
fn completing_a_repeating_task_moves_every_repeating_planning_line() {
    let vault = vault(
        "# TODO Report\n`SCHEDULED: <2026-07-01 Wed +1m>`\n`DEADLINE: <2026-07-05 Sun +1m>`\n",
    );

    complete_task(target(vault.path(), 1, "Report"), TODAY.to_string()).expect("complete");

    assert_eq!(
        body(vault.path()),
        "# TODO Report\n`SCHEDULED: <2026-08-01 Sat +1m>`\n`DEADLINE: <2026-08-05 Wed +1m>`\n"
    );
}

#[test]
fn a_planning_line_without_a_repeater_stays_put_when_another_one_repeats() {
    let vault =
        vault("# TODO Report\n`SCHEDULED: <2026-07-01 Wed +1m>`\n`DEADLINE: <2026-07-05 Sun>`\n");

    complete_task(target(vault.path(), 1, "Report"), TODAY.to_string()).expect("complete");

    // Upstream removes a repeater-less SCHEDULED here; this application keeps
    // it, because removing a line the user wrote is not something a tap on a
    // phone should do. Documented divergence.
    assert_eq!(
        body(vault.path()),
        "# TODO Report\n`SCHEDULED: <2026-08-01 Sat +1m>`\n`DEADLINE: <2026-07-05 Sun>`\n"
    );
}

#[test]
fn completing_a_repeating_task_leaves_it_open() {
    let vault = vault("# TODO Water the plants\n`SCHEDULED: <2026-07-28 Tue +1d>`\n");

    let outcome = complete_task(
        target(vault.path(), 1, "Water the plants"),
        TODAY.to_string(),
    )
    .expect("complete");

    assert_eq!(outcome.heading, "# TODO Water the plants");
    assert!(outcome.repeated);
}

#[test]
fn an_hourly_repeater_is_refused_rather_than_guessed_at() {
    let vault = vault("# TODO Take the pill\n`SCHEDULED: <2026-07-28 Tue 10:00 +6h>`\n");

    let error = complete_task(target(vault.path(), 1, "Take the pill"), TODAY.to_string())
        .expect_err("must refuse");

    assert!(matches!(error, EditError::Unsupported { .. }), "{error:?}");
    assert_eq!(
        body(vault.path()),
        "# TODO Take the pill\n`SCHEDULED: <2026-07-28 Tue 10:00 +6h>`\n"
    );
}

#[test]
fn a_workday_repeater_skips_the_weekend() {
    // 31 July 2026 is a Friday, so one working day later is Monday.
    let vault = vault("# TODO Standup\n`SCHEDULED: <2026-07-31 Fri +1wd>`\n");

    complete_task(target(vault.path(), 1, "Standup"), TODAY.to_string()).expect("complete");

    assert_eq!(
        body(vault.path()),
        "# TODO Standup\n`SCHEDULED: <2026-08-03 Mon +1wd>`\n"
    );
}

#[test]
fn shifting_past_a_four_digit_year_is_refused_rather_than_rewriting_the_line() {
    // chrono prints a year outside 0..10000 with a sign and without
    // truncating, so the date would stop being ten bytes wide and the file
    // would end up holding a timestamp nothing can read back.
    let vault = vault("# TODO Отчёт\n`SCHEDULED: <2026-07-28 Вт>`\n");

    let error = shift_planning(
        target(vault.path(), 1, "Отчёт"),
        PlanningKeyword::Scheduled,
        2_920_000,
    )
    .expect_err("must refuse");

    assert!(matches!(error, EditError::InvalidDate { .. }), "{error:?}");
    assert_eq!(
        body(vault.path()),
        "# TODO Отчёт\n`SCHEDULED: <2026-07-28 Вт>`\n"
    );
}

#[test]
fn shifting_before_the_year_one_thousand_is_refused_rather_than_panicking() {
    let vault = vault("# TODO Отчёт\n`SCHEDULED: <2026-07-28 Вт>`\n");

    let error = shift_planning(
        target(vault.path(), 1, "Отчёт"),
        PlanningKeyword::Scheduled,
        -800_000,
    )
    .expect_err("must refuse");

    assert!(matches!(error, EditError::InvalidDate { .. }), "{error:?}");
    assert_eq!(
        body(vault.path()),
        "# TODO Отчёт\n`SCHEDULED: <2026-07-28 Вт>`\n"
    );
}

#[test]
fn shifting_by_no_days_leaves_the_file_untouched() {
    let vault = vault("# TODO Write the report\n`SCHEDULED: <2026-07-28 Tue>`\n");
    let path = vault.path().join("notes.md");
    let before = std::fs::metadata(&path).expect("metadata");

    let outcome = shift_planning(
        target(vault.path(), 1, "Write the report"),
        PlanningKeyword::Scheduled,
        0,
    )
    .expect("shift");

    assert!(!outcome.changed, "nothing moved, so nothing was written");
    let after = std::fs::metadata(&path).expect("metadata");
    assert_eq!(
        before.modified().expect("mtime"),
        after.modified().expect("mtime"),
        "the file was rewritten with its own content"
    );
}

#[test]
fn a_weekday_in_a_language_the_application_does_not_know_is_refused() {
    // Ukrainian `Нд` is Sunday. Rewriting it as the Russian `Вс` would be a
    // change of language the user did not ask for, so the edit is refused
    // instead.
    let vault = vault("# TODO Звіт\n`SCHEDULED: <2026-07-26 Нд>`\n");

    let error = shift_planning(
        target(vault.path(), 1, "Звіт"),
        PlanningKeyword::Scheduled,
        7,
    )
    .expect_err("must refuse");

    assert!(matches!(error, EditError::Unsupported { .. }), "{error:?}");
    assert_eq!(
        body(vault.path()),
        "# TODO Звіт\n`SCHEDULED: <2026-07-26 Нд>`\n"
    );
}

#[test]
fn a_lowercase_weekday_stays_lowercase() {
    let vault = vault("# TODO Отчёт\n`SCHEDULED: <2026-07-28 вт>`\n");

    let outcome = shift_planning(
        target(vault.path(), 1, "Отчёт"),
        PlanningKeyword::Scheduled,
        1,
    )
    .expect("shift");

    assert_eq!(outcome.line, "`SCHEDULED: <2026-07-29 ср>`");
}

#[test]
fn completing_a_task_with_a_malformed_today_is_refused() {
    let vault = vault("# TODO Write the report\n`SCHEDULED: <2026-07-28 Tue +1d>`\n");

    let error = complete_task(
        target(vault.path(), 1, "Write the report"),
        "yesterday".to_string(),
    )
    .expect_err("must refuse");

    assert!(matches!(error, EditError::InvalidDate { .. }), "{error:?}");
}

#[test]
fn a_blank_line_between_the_heading_and_the_planning_line_does_not_hide_it() {
    // Ordinary markdown spacing. The extractor reads the timestamp out of any
    // paragraph in the section, so the agenda shows the date and the edit has
    // to find the same line.
    let vault = vault("# TODO Water the plants\n\n`SCHEDULED: <2026-07-01 Wed +1w>`\n");

    let outcome = complete_task(
        target(vault.path(), 1, "Water the plants"),
        "2026-07-29".to_string(),
    )
    .expect("complete");

    assert!(outcome.repeated, "{outcome:?}");
    assert_eq!(
        body(vault.path()),
        "# TODO Water the plants\n\n`SCHEDULED: <2026-07-08 Wed +1w>`\n"
    );
}

#[test]
fn a_created_line_before_the_planning_line_does_not_hide_it() {
    let vault = vault(
        "# TODO Water the plants\n`CREATED: [2026-06-01 Mon]`\n`SCHEDULED: <2026-07-01 Wed +1w>`\n",
    );

    let outcome = complete_task(
        target(vault.path(), 1, "Water the plants"),
        "2026-07-29".to_string(),
    )
    .expect("complete");

    assert!(outcome.repeated, "{outcome:?}");
    assert_eq!(
        body(vault.path()),
        "# TODO Water the plants\n`CREATED: [2026-06-01 Mon]`\n`SCHEDULED: <2026-07-08 Wed +1w>`\n"
    );
}

#[test]
fn the_search_stops_at_the_next_heading_rather_than_reaching_its_planning_line() {
    let vault = vault(
        "# TODO Write the report\nNothing planned here.\n# TODO Water the plants\n`SCHEDULED: <2026-07-01 Wed>`\n",
    );

    let error = shift_planning(
        target(vault.path(), 1, "Write the report"),
        PlanningKeyword::Scheduled,
        1,
    )
    .expect_err("must refuse");

    assert!(
        matches!(error, EditError::NoPlanningLine { .. }),
        "{error:?}"
    );
}

#[test]
fn a_planning_line_is_classified_by_its_prefix_not_by_what_it_mentions() {
    // The extractor anchors the keyword at the start of the line for this very
    // reason; matching anywhere reads a DEADLINE line as a SCHEDULED one and
    // moves the wrong date.
    let vault = vault(
        "# TODO Write the report\n`DEADLINE: <2026-07-30 Thu>` — agreed, see SCHEDULED: in the ticket\n",
    );

    let outcome = shift_planning(
        target(vault.path(), 1, "Write the report"),
        PlanningKeyword::Deadline,
        1,
    )
    .expect("shift");

    assert_eq!(
        outcome.line,
        "`DEADLINE: <2026-07-31 Fri>` — agreed, see SCHEDULED: in the ticket"
    );

    let error = shift_planning(
        target(vault.path(), 1, "Write the report"),
        PlanningKeyword::Scheduled,
        1,
    )
    .expect_err("must refuse");

    assert!(
        matches!(error, EditError::NoPlanningLine { .. }),
        "{error:?}"
    );
}

#[test]
fn completing_a_repeating_task_without_a_keyword_does_not_add_one() {
    let vault = vault("# Water the plants\n`SCHEDULED: <2026-07-01 Wed +1w>`\n");

    let outcome = complete_task(
        target(vault.path(), 1, "Water the plants"),
        "2026-07-29".to_string(),
    )
    .expect("complete");

    assert_eq!(outcome.heading, "# Water the plants");
    assert_eq!(
        body(vault.path()),
        "# Water the plants\n`SCHEDULED: <2026-07-08 Wed +1w>`\n"
    );
}

#[test]
fn a_date_is_written_where_the_entry_had_none() {
    let vault = vault("# TODO Write the report\n\nThe figures are in.\n");

    let outcome = set_planning(
        target(vault.path(), 1, "Write the report"),
        PlanningKeyword::Scheduled,
        Some("2026-08-19".to_string()),
    )
    .expect("set");

    assert_eq!(outcome.line, "`SCHEDULED: <2026-08-19 Wed>`");
    assert!(outcome.changed);
    assert_eq!(
        body(vault.path()),
        "# TODO Write the report\n`SCHEDULED: <2026-08-19 Wed>`\n\nThe figures are in.\n"
    );
}

#[test]
fn a_new_line_is_spelled_the_way_the_file_spells_the_others() {
    let vault = vault(concat!(
        "# TODO Полить цветы\n",
        "`SCHEDULED: <2026-08-17 Пн>`\n",
        "\n",
        "# TODO Написать отчёт\n",
    ));

    let outcome = set_planning(
        target(vault.path(), 4, "Написать отчёт"),
        PlanningKeyword::Deadline,
        Some("2026-08-19".to_string()),
    )
    .expect("set");

    assert_eq!(outcome.line, "`DEADLINE: <2026-08-19 Ср>`");
}

#[test]
fn a_file_that_writes_no_weekday_gets_a_date_without_one() {
    let vault = vault("# TODO Water\n`SCHEDULED: <2026-08-17>`\n\n# TODO Write\n");

    let outcome = set_planning(
        target(vault.path(), 4, "Write"),
        PlanningKeyword::Scheduled,
        Some("2026-08-19".to_string()),
    )
    .expect("set");

    assert_eq!(outcome.line, "`SCHEDULED: <2026-08-19>`");
}

#[test]
fn a_file_that_writes_its_lines_bare_keeps_them_bare() {
    let vault = vault("# TODO Water\nSCHEDULED: <2026-08-17 Mon>\n\n# TODO Write\n");

    let outcome = set_planning(
        target(vault.path(), 4, "Write"),
        PlanningKeyword::Scheduled,
        Some("2026-08-19".to_string()),
    )
    .expect("set");

    assert_eq!(outcome.line, "SCHEDULED: <2026-08-19 Wed>");
}

#[test]
fn a_weekday_this_application_cannot_rewrite_does_not_stop_a_new_date() {
    let vault = vault("# TODO Water\n`SCHEDULED: <2026-08-17 Δευ>`\n\n# TODO Write\n");

    let outcome = set_planning(
        target(vault.path(), 4, "Write"),
        PlanningKeyword::Scheduled,
        Some("2026-08-19".to_string()),
    )
    .expect("set");

    assert_eq!(outcome.line, "`SCHEDULED: <2026-08-19 Wed>`");
}

#[test]
fn a_new_line_joins_the_block_under_the_heading() {
    let vault = vault(concat!(
        "# TODO Write the report\n",
        "`CREATED: [2026-08-01 Sat]`\n",
        "`DEADLINE: <2026-08-25 Tue>`\n",
        "\n",
        "The figures are in.\n",
    ));

    set_planning(
        target(vault.path(), 1, "Write the report"),
        PlanningKeyword::Scheduled,
        Some("2026-08-19".to_string()),
    )
    .expect("set");

    assert_eq!(
        body(vault.path()),
        concat!(
            "# TODO Write the report\n",
            "`CREATED: [2026-08-01 Sat]`\n",
            "`DEADLINE: <2026-08-25 Tue>`\n",
            "`SCHEDULED: <2026-08-19 Wed>`\n",
            "\n",
            "The figures are in.\n",
        )
    );
}

#[test]
fn setting_a_date_on_a_line_that_has_one_moves_the_date_and_nothing_else() {
    let vault = vault("# TODO Write\n`SCHEDULED: <2026-07-28 Tue 10:00 ++1w -2d>`\n");

    let outcome = set_planning(
        target(vault.path(), 1, "Write"),
        PlanningKeyword::Scheduled,
        Some("2026-08-19".to_string()),
    )
    .expect("set");

    assert_eq!(outcome.line, "`SCHEDULED: <2026-08-19 Wed 10:00 ++1w -2d>`");
    assert_eq!(
        body(vault.path()),
        "# TODO Write\n`SCHEDULED: <2026-08-19 Wed 10:00 ++1w -2d>`\n"
    );
}

#[test]
fn setting_the_date_the_file_already_holds_writes_nothing() {
    let vault = vault("# TODO Write\n`SCHEDULED: <2026-08-19 Wed>`\n");

    let outcome = set_planning(
        target(vault.path(), 1, "Write"),
        PlanningKeyword::Scheduled,
        Some("2026-08-19".to_string()),
    )
    .expect("set");

    assert!(!outcome.changed);
    assert_eq!(
        body(vault.path()),
        "# TODO Write\n`SCHEDULED: <2026-08-19 Wed>`\n"
    );
}

#[test]
fn clearing_a_date_takes_its_line_out_and_leaves_the_rest() {
    let vault = vault(concat!(
        "# TODO Write\n",
        "`SCHEDULED: <2026-08-19 Wed>`\n",
        "`DEADLINE: <2026-08-25 Tue>`\n",
        "\n",
        "The figures are in.\n",
    ));

    let outcome = set_planning(
        target(vault.path(), 1, "Write"),
        PlanningKeyword::Scheduled,
        None,
    )
    .expect("clear");

    assert!(outcome.changed);
    assert_eq!(outcome.line, "");
    assert_eq!(
        body(vault.path()),
        "# TODO Write\n`DEADLINE: <2026-08-25 Tue>`\n\nThe figures are in.\n"
    );
}

#[test]
fn clearing_a_date_the_entry_does_not_carry_is_no_edit_at_all() {
    let vault = vault("# TODO Write\n`DEADLINE: <2026-08-25 Tue>`\n");

    let outcome = set_planning(
        target(vault.path(), 1, "Write"),
        PlanningKeyword::Scheduled,
        None,
    )
    .expect("clear");

    assert!(!outcome.changed);
    assert_eq!(
        body(vault.path()),
        "# TODO Write\n`DEADLINE: <2026-08-25 Tue>`\n"
    );
}

#[test]
fn a_line_carrying_both_keywords_is_refused_rather_than_half_cut() {
    let vault = vault("# TODO Write\n`SCHEDULED: <2026-08-19 Wed> DEADLINE: <2026-08-25 Tue>`\n");

    let refused = set_planning(
        target(vault.path(), 1, "Write"),
        PlanningKeyword::Scheduled,
        None,
    )
    .expect_err("refused");

    assert!(
        matches!(refused, EditError::Unsupported { .. }),
        "{refused:?}"
    );
    assert_eq!(
        body(vault.path()),
        "# TODO Write\n`SCHEDULED: <2026-08-19 Wed> DEADLINE: <2026-08-25 Tue>`\n"
    );
}

#[test]
fn a_date_that_is_not_a_date_is_refused_before_the_file_is_opened() {
    let vault = vault("# TODO Write\n");

    let refused = set_planning(
        target(vault.path(), 1, "Write"),
        PlanningKeyword::Scheduled,
        Some("19.08.2026".to_string()),
    )
    .expect_err("refused");

    assert!(
        matches!(refused, EditError::InvalidDate { .. }),
        "{refused:?}"
    );
    assert_eq!(body(vault.path()), "# TODO Write\n");
}

#[test]
fn a_date_outside_the_four_digit_years_is_refused() {
    let vault = vault("# TODO Write\n");

    let refused = set_planning(
        target(vault.path(), 1, "Write"),
        PlanningKeyword::Scheduled,
        Some("0999-08-19".to_string()),
    )
    .expect_err("refused");

    assert!(
        matches!(refused, EditError::InvalidDate { .. }),
        "{refused:?}"
    );
    assert_eq!(body(vault.path()), "# TODO Write\n");
}

#[test]
fn a_line_added_to_a_crlf_file_is_written_with_crlf() {
    let vault = vault("# TODO Write\r\n\r\nThe figures are in.\r\n");

    set_planning(
        target(vault.path(), 1, "Write"),
        PlanningKeyword::Deadline,
        Some("2026-08-19".to_string()),
    )
    .expect("set");

    assert_eq!(
        body(vault.path()),
        "# TODO Write\r\n`DEADLINE: <2026-08-19 Wed>`\r\n\r\nThe figures are in.\r\n"
    );
}

#[test]
fn a_file_that_ends_without_a_newline_does_not_grow_one() {
    let vault = vault("# TODO Write");

    set_planning(
        target(vault.path(), 1, "Write"),
        PlanningKeyword::Scheduled,
        Some("2026-08-19".to_string()),
    )
    .expect("set");

    assert_eq!(
        body(vault.path()),
        "# TODO Write\n`SCHEDULED: <2026-08-19 Wed>`"
    );
}

#[test]
fn an_indented_block_keeps_its_indentation() {
    let vault = vault("# TODO Write\n  `DEADLINE: <2026-08-25 Tue>`\n");

    let outcome = set_planning(
        target(vault.path(), 1, "Write"),
        PlanningKeyword::Scheduled,
        Some("2026-08-19".to_string()),
    )
    .expect("set");

    assert_eq!(outcome.line, "  `SCHEDULED: <2026-08-19 Wed>`");
}

#[test]
fn a_repeater_comes_back_written_the_way_it_goes_into_the_file() {
    assert_eq!(
        canonical_repeater("++1w".to_string()).as_deref(),
        Some("++1w")
    );
    assert_eq!(
        canonical_repeater("  .+3d ".to_string()).as_deref(),
        Some(".+3d")
    );
    assert_eq!(
        canonical_repeater("+007d".to_string()).as_deref(),
        Some("+7d")
    );
    assert_eq!(
        canonical_repeater("+1wd".to_string()).as_deref(),
        Some("+1wd")
    );
}

#[test]
fn what_is_not_a_repeater_is_answered_as_none() {
    // No prefix, no unit, a step of nothing, a word, and the upper case the
    // format does not read -- each is a field somebody can type.
    for value in ["1w", "+1", "+0d", "weekly", "++2W", ""] {
        assert_eq!(
            canonical_repeater(value.to_string()),
            None,
            "{value:?} was taken for a repeater"
        );
    }
}
