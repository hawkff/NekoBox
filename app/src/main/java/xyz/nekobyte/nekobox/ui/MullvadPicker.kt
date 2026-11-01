package xyz.nekobyte.nekobox.ui

import android.content.Context
import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.group.MullvadRelays

/**
 * Mullvad's countries under a row for every server, each followed by its cities when it has several, with how many
 * servers each holds. Checking a row checks the cities it covers. The countries and cities [saved] as codes ("nl",
 * "us-nyc"; null for all) open checked; [onPick] gets the codes checked, or null for every server: a list kept whole,
 * or All countries checked. A saved code the server list has no server for now stays.
 */
internal fun showMullvadPicker(
    context: Context,
    relays: List<MullvadRelays.Relay>,
    saved: Set<String>?,
    adding: Boolean,
    onPick: (Set<String>?) -> Unit,
): AlertDialog {
    // A row covers cities: all of them, a country's, or one.
    class Row(val label: String, val places: Set<String>)

    // The servers come ordered by country name and city, so the groups are too.
    val countries = relays.groupBy { it.country }
    val all = relays.mapTo(HashSet()) { it.place }
    val rows = ArrayList<Row>()
    rows += Row("${context.getString(R.string.proxies_mullvad_all)} · ${relays.size}", all)
    for ((country, servers) in countries) {
        val cities = servers.groupBy { it.city }
        rows += Row("${ProfileWords.flag(country)} ${MullvadRelays.countryName(country)} · ${servers.size}", cities.keys.mapTo(HashSet()) { "$country-$it" })
        if (cities.size > 1) cities.values.forEach { rows += Row("\u2003${it[0].cityName} · ${it.size}", setOf(it[0].place)) }
    }
    val picked = relays.filter { saved == null || it.country in saved || it.place in saved }.mapTo(HashSet()) { it.place }
    val unseen = saved.orEmpty().filterNot { it in countries || it in all }
    val checked = BooleanArray(rows.size) { picked.containsAll(rows[it].places) }
    // Every server shown is not every server when the server list came back short; only All countries says so.
    var everything = saved == null
    lateinit var dialog: AlertDialog

    // The dialog's list reads [checked] for rows it draws later; rows on screen take the check directly.
    fun refresh() {
        rows.forEachIndexed { index, row ->
            checked[index] = picked.containsAll(row.places)
            dialog.listView.setItemChecked(index, checked[index])
        }
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = picked.isNotEmpty()
    }
    dialog = MaterialAlertDialogBuilder(context)
        .setTitle(R.string.proxies_mullvad_title)
        .setMultiChoiceItems(rows.map { it.label }.toTypedArray(), checked) { _, which, isChecked ->
            if (isChecked) picked += rows[which].places else picked -= rows[which].places
            if (which == 0) everything = isChecked
            refresh()
        }
        .setPositiveButton(if (adding) R.string.add else android.R.string.ok) { _, _ ->
            if (everything && picked.containsAll(all)) return@setPositiveButton onPick(null)
            onPick(
                countries.flatMap { (country, servers) ->
                    val places = servers.mapTo(HashSet()) { it.place }
                    if (picked.containsAll(places)) listOf(country) else places.filter { it in picked }
                }.toSet() + unseen,
            )
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    dialog.show()
    refresh()
    return dialog
}
