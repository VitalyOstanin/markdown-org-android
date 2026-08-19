package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.autofill.FillableData
import androidx.compose.ui.autofill.createFromText
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.fillableData
import androidx.compose.ui.semantics.semantics

/**
 * Keep a field's content out of the autofill service's transaction.
 *
 * A text field hands what it holds to the system on every change, over a
 * Binder transaction bounded at about a megabyte. A note the user has put a
 * long text under therefore does not open slowly — it takes the application
 * down with `TransactionTooLargeException`, measured on a device at 676 KB.
 *
 * Both properties are needed, and this was established by a run rather than
 * by reading: the manager sends the text on a change of `InputText` only when
 * the data type says Text, but it sends `FillableData` unguarded, and that
 * carries the same string. The first is turned off, the second is pinned to a
 * value that never changes — an unchanged value produces no notification.
 *
 * Nothing here is asked of the field itself: the properties are set on the
 * modifier the caller passes, which lands on the same node the field declares
 * its own semantics on and is applied after them.
 *
 * This removes the field from autofill, which is the point: notes are not
 * addresses or passwords, and no autofill service has anything to offer them.
 */
fun Modifier.withoutAutofill(): Modifier = semantics {
    contentDataType = ContentDataType.None
    FillableData.createFromText("")?.let { fillableData = it }
}
