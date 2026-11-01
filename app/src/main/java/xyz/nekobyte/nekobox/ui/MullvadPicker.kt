package xyz.nekobyte.nekobox.ui

import android.content.Context
import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.group.MullvadRelays

/**
 * Mullvad's countries under a row for every server, each followed by its cities when it has several, with how many
 * servers each holds. Checking a row checks the cities it covers. The cities in [held] open checked; [onPick] gets
 * the countries and cities checked as codes ("nl", "us-nyc"), or null when every server is.
 */
internal fun showMullvadPicker(
    context: Context,
    relays: List<MullvadRelays.Relay>,
    held: Set<String>,
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
    val picked = held.filterTo(HashSet()) { it in all }
    val checked = BooleanArray(rows.size) { picked.containsAll(rows[it].places) }
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
            refresh()
        }
        .setPositiveButton(if (adding) R.string.add else android.R.string.ok) { _, _ ->
            if (picked.containsAll(all)) return@setPositiveButton onPick(null)
            onPick(
                countries.flatMap { (country, servers) ->
                    val places = servers.mapTo(HashSet()) { it.place }
                    if (picked.containsAll(places)) listOf(country) else places.filter { it in picked }
                }.toSet(),
            )
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    dialog.show()
    refresh()
    return dialog
}
