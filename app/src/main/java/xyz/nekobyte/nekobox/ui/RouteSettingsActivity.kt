package xyz.nekobyte.nekobox.ui

import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.BundleCompat
import androidx.core.os.ConfigurationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProfileManager
import xyz.nekobyte.nekobox.database.RuleEntity
import xyz.nekobyte.nekobox.databinding.ItemRouteListChipBinding
import xyz.nekobyte.nekobox.databinding.LayoutRouteEditorBinding
import xyz.nekobyte.nekobox.fmt.RouteVerdict
import xyz.nekobyte.nekobox.fmt.validateDnsRouting
import xyz.nekobyte.nekobox.ktx.beginFold
import xyz.nekobyte.nekobox.ktx.readableMessage
import xyz.nekobyte.nekobox.ui.profile.ConfigEditActivity
import xyz.nekobyte.nekobox.utils.PackageCache
import xyz.nekobyte.nekobox.utils.listByLineOrComma
import java.util.Locale

/**
 * Makes or changes a route rule. The form edits a draft, which survives the screen being recreated;
 * nothing is stored until Save, and leaving with changes asks first.
 */
class RouteSettingsActivity : ThemedActivity() {

    private lateinit var binding: LayoutRouteEditorBinding
    private lateinit var original: RuleEntity
    private lateinit var draft: RuleEntity

    // Set while the form shows the draft, so that the fields' listeners don't write it back.
    private var filling = false
    private var describing: Job? = null
    private var trying: Job? = null

    private val pickProfile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = result.data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L) ?: 0L
        if (result.resultCode == RESULT_OK && id > 0L) draft.outbound = id
        showOutbound()
        changed()
    }

    // The app list reads and writes its choice in the settings store.
    private val pickApps = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        draft.packages = DataStore.routePackages.split('\n').filter { it.isNotBlank() }.toSet()
        showApps()
        changed()
    }

    private val editJson = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        draft.config = DataStore.serverConfig
        showJson()
        changed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutRouteEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById(R.id.toolbar))
        val editingId = intent.getLongExtra(EXTRA_ROUTE_ID, 0L)
        supportActionBar?.apply {
            setTitle(if (editingId == 0L) R.string.route_editor_new else R.string.route_editor_edit)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_close)
        }
        val bottom = binding.scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.scroll) { view, insets ->
            view.updatePadding(bottom = bottom + insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()).bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this) {
            if (::draft.isInitialized && draft != original) askToSave() else finish()
        }

        val savedDraft = savedInstanceState?.let { BundleCompat.getParcelable(it, DRAFT, RuleEntity::class.java) }
        val savedOriginal = savedInstanceState?.let { BundleCompat.getParcelable(it, ORIGINAL, RuleEntity::class.java) }
        if (savedDraft != null && savedOriginal != null) {
            start(stored = savedOriginal, edited = savedDraft)
            // Open or folded, the section stays as the person left it.
            if (savedInstanceState?.containsKey(MORE) == true) showMore(savedInstanceState.getBoolean(MORE), animate = false)
            return
        }
        lifecycleScope.launch {
            val rule = withContext(Dispatchers.IO) { if (editingId == 0L) newRule() else ProfileDatabase.rulesDao.getById(editingId) }
            if (rule == null) finish() else start(stored = rule, edited = rule.copy())
        }
    }

    // A rule made from the connections list starts with the app, site or address it came from.
    private fun newRule() = RuleEntity(enabled = true).apply {
        intent.getStringExtra(EXTRA_PACKAGE_NAME)?.takeIf { it.isNotBlank() }?.let {
            PackageCache.awaitLoadSync()
            packages = setOf(it)
            name = getString(R.string.route_for, PackageCache.loadLabel(it))
        }
        intent.getStringExtra(EXTRA_DOMAIN)?.takeIf { it.isNotBlank() }?.let {
            domains = it
            name = getString(R.string.route_for, it)
        }
        intent.getStringExtra(EXTRA_IP)?.takeIf { it.isNotBlank() }?.let {
            ip = it
            name = getString(R.string.route_for, it)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::draft.isInitialized) return
        outState.putParcelable(DRAFT, draft)
        outState.putParcelable(ORIGINAL, original)
        outState.putBoolean(MORE, binding.more.isVisible)
    }

    private fun start(stored: RuleEntity, edited: RuleEntity) {
        original = stored
        draft = edited
        offerLists(binding.siteLists, binding.sites, SITE_LISTS)
        offerLists(binding.addressLists, binding.addresses, ADDRESS_LISTS)
        filling = true
        binding.name.setText(draft.name)
        binding.sites.setText(draft.domains)
        binding.addresses.setText(draft.ip)
        binding.downloaded.setText(draft.ruleset)
        binding.ports.setText(draft.port)
        binding.protocols.setText(draft.protocol)
        binding.source.setText(draft.source)
        binding.sourcePorts.setText(draft.sourcePort)
        binding.dns.isChecked = draft.dnsThroughOutbound
        binding.dnsServer.setText(draft.dnsServer)
        binding.network.check(
            when (draft.network) {
                "tcp" -> R.id.network_tcp
                "udp" -> R.id.network_udp
                else -> R.id.network_any
            },
        )
        filling = false
        showOutbound()
        showApps()
        showJson()

        fun EditText.writes(write: RuleEntity.(String) -> Unit) = doAfterTextChanged {
            if (filling) return@doAfterTextChanged
            draft.write(it?.toString().orEmpty())
            changed()
        }
        binding.name.writes { name = it.trim() }
        binding.sites.writes { domains = it }
        binding.addresses.writes { ip = it }
        binding.downloaded.writes { ruleset = it }
        binding.ports.writes { port = it }
        binding.protocols.writes { protocol = it }
        binding.source.writes { source = it }
        binding.sourcePorts.writes { sourcePort = it }
        binding.dnsServer.writes { dnsServer = it.trim() }
        binding.dns.setOnCheckedChangeListener { _, on ->
            if (filling) return@setOnCheckedChangeListener
            draft.dnsThroughOutbound = on
            binding.dnsServerLayout.isEnabled = on
            changed()
        }
        binding.dnsServerLayout.isEnabled = draft.dnsThroughOutbound
        binding.network.setOnCheckedStateChangeListener { _, checked ->
            if (filling) return@setOnCheckedStateChangeListener
            draft.network = when (checked.firstOrNull()) {
                R.id.network_tcp -> "tcp"
                R.id.network_udp -> "udp"
                else -> ""
            }
            changed()
        }
        binding.outbound.setOnCheckedStateChangeListener { _, checked ->
            if (filling) return@setOnCheckedStateChangeListener
            draft.outbound = when (checked.firstOrNull()) {
                R.id.to_bypass -> -1L
                R.id.to_block -> -2L
                R.id.to_profile -> return@setOnCheckedStateChangeListener
                else -> 0L
            }
            binding.dnsSection.isVisible = draft.outbound >= 0L || draft.dnsThroughOutbound
            changed()
        }
        // A tap picks a profile, also when one is already picked.
        binding.toProfile.setOnClickListener {
            lifecycleScope.launch {
                val selected = withContext(Dispatchers.IO) { ProfileManager.getProfile(draft.outbound) }
                pickProfile.launch(Intent(this@RouteSettingsActivity, ProfileSelectActivity::class.java).apply { selected?.let { putExtra(ProfileSelectActivity.EXTRA_SELECTED, it) } })
            }
        }
        binding.apps.setOnClickListener {
            DataStore.routePackages = draft.packages.joinToString("\n")
            pickApps.launch(Intent(this, AppListActivity::class.java))
        }
        binding.customJson.setOnClickListener {
            DataStore.serverConfig = draft.config
            editJson.launch(Intent(this, ConfigEditActivity::class.java))
        }
        binding.tryProbe.doAfterTextChanged { tryIt() }
        binding.moreToggle.setOnClickListener { showMore(!binding.more.isVisible, animate = true) }
        val usesMore = draft.protocol.isNotBlank() || draft.source.isNotBlank() || draft.sourcePort.isNotBlank() ||
            draft.dnsThroughOutbound || draft.dnsServer.isNotBlank() || draft.config.isNotBlank()
        showMore(usesMore, animate = false)
        changed()
    }

    /** Opens or folds what few rules need: detected protocols, where traffic starts, rule DNS and custom JSON. */
    private fun showMore(open: Boolean, animate: Boolean) {
        if (animate) (binding.scroll.getChildAt(0) as ViewGroup).beginFold(binding.moreToggle)
        binding.more.isVisible = open
        binding.moreToggle.setText(if (open) R.string.route_editor_fewer else R.string.route_editor_more)
        binding.moreToggle.setIconResource(if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
    }

    /** Chips for common lists under [field]: checked while the field holds the list, and tapping one adds or removes it. */
    private fun offerLists(group: ChipGroup, field: EditText, lists: List<Pair<String, Int?>>) {
        if (group.isNotEmpty()) return
        val locale = ConfigurationCompat.getLocales(resources.configuration)[0] ?: Locale.getDefault()
        for ((entry, label) in lists) {
            val chip = ItemRouteListChipBinding.inflate(layoutInflater, group, false).root
            chip.text = label?.let(::getString)
                ?: Locale("", entry.substringAfter(':').substringAfter("category-").uppercase()).getDisplayCountry(locale)
            chip.tag = entry
            chip.setOnCheckedChangeListener { _, on ->
                if (filling) return@setOnCheckedChangeListener
                val entries = field.text?.toString().orEmpty().listByLineOrComma().filter { it != entry }
                field.setText((if (on) entries + entry else entries).joinToString("\n"))
                field.setSelection(field.length())
            }
            group.addView(chip)
        }
    }

    private fun showLists() {
        filling = true
        for ((group, text) in listOf(binding.siteLists to draft.domains, binding.addressLists to draft.ip)) {
            val entries = text.listByLineOrComma()
            for (chip in group.children.filterIsInstance<Chip>()) chip.isChecked = chip.tag in entries
        }
        filling = false
    }

    private fun showOutbound() {
        filling = true
        binding.outbound.check(
            when (draft.outbound) {
                0L -> R.id.to_proxy
                -1L -> R.id.to_bypass
                -2L -> R.id.to_block
                else -> R.id.to_profile
            },
        )
        filling = false
        val outbound = draft.outbound
        binding.dnsSection.isVisible = outbound >= 0L || draft.dnsThroughOutbound
        if (outbound <= 0L) return binding.toProfile.setText(R.string.route_editor_profile)
        lifecycleScope.launch {
            binding.toProfile.text = withContext(Dispatchers.IO) { RuleWords.outbound(this@RouteSettingsActivity, outbound) }
        }
    }

    private fun showApps() {
        val packages = draft.packages
        if (packages.isEmpty()) return binding.appsSummary.setText(R.string.route_editor_any_app)
        lifecycleScope.launch {
            binding.appsSummary.text = withContext(Dispatchers.Default) { RuleWords.apps(this@RouteSettingsActivity, packages) }
        }
    }

    private fun showJson() {
        val lines = draft.config.lines().count { it.isNotBlank() }
        binding.customJsonSummary.text = if (lines == 0) getString(androidx.preference.R.string.not_set) else resources.getQuantityString(R.plurals.config_lines, lines, lines)
    }

    /** After every edit: the chips, the sentence on top and the try field follow the draft. */
    private fun changed() {
        showLists()
        describing?.cancel()
        val rule = draft.copy()
        describing = lifecycleScope.launch {
            delay(WORDS)
            val (summary, destination, color) = withContext(Dispatchers.Default) {
                Triple(RuleWords.summary(this@RouteSettingsActivity, rule), RuleWords.outbound(this@RouteSettingsActivity, rule.outbound), RuleWords.outboundColor(this@RouteSettingsActivity, rule.outbound))
            }
            val sentence = SpannableString(getString(R.string.route_editor_sentence, summary, destination))
            val at = sentence.lastIndexOf(destination)
            if (at >= 0) sentence.setSpan(ForegroundColorSpan(color), at, at + destination.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            binding.sentence.text = sentence
        }
        tryIt()
    }

    private fun tryIt() {
        trying?.cancel()
        val text = binding.tryProbe.text?.toString().orEmpty().trim()
        if (text.isEmpty()) {
            binding.tryResult.isVisible = false
            return
        }
        val rule = draft.copy(enabled = true)
        trying = lifecycleScope.launch {
            delay(TYPING)
            binding.tryResult.text = withContext(Dispatchers.Default) { tryWords(rule, text) }
            binding.tryResult.isVisible = true
        }
    }

    private fun tryWords(rule: RuleEntity, text: String): String {
        val (probe, _) = RouteChecks.probe(text) ?: return getString(R.string.route_test_nothing, text)
        return when (val verdict = RouteChecks.tester().judge(rule, probe)) {
            RouteVerdict.Catches -> getString(R.string.route_editor_try_catches)
            RouteVerdict.Passes -> getString(R.string.route_editor_try_passes)
            is RouteVerdict.CatchesIf -> getString(R.string.route_editor_try_maybe, RuleWords.conditions(this, verdict.conditions))
            is RouteVerdict.Unknown -> getString(R.string.route_editor_try_unknown, RuleWords.unknown(this, verdict.reason))
        }
    }

    // Set from the first Save until the screen closes or says what to fix, so that a second tap can't store the rule twice.
    private var saving = false

    private fun save() {
        if (saving) return
        saving = true
        val rule = draft.copy()
        lifecycleScope.launch {
            if (rule.domains.isBlank() && rule.ip.isBlank() && rule.port.isBlank() && rule.sourcePort.isBlank() && rule.network.isBlank() &&
                rule.source.isBlank() && rule.protocol.isBlank() && rule.ruleset.isBlank() && rule.packages.isEmpty() && rule.config.isBlank()
            ) {
                return@launch tell(R.string.empty_route, getString(R.string.empty_route_notice))
            }
            runCatching { rule.validateDnsRouting() }.exceptionOrNull()?.let { return@launch tell(R.string.route_dns_error, it.readableMessage) }
            withContext(Dispatchers.Default) { RouteChecks.problem(this@RouteSettingsActivity, rule) }?.let { return@launch tell(R.string.error_title, getString(R.string.route_editor_broken, it)) }
            withContext(Dispatchers.IO) { if (rule.id == 0L) ProfileManager.createRule(rule) else ProfileManager.updateRule(rule) }
            if (intent.hasExtra(EXTRA_PACKAGE_NAME)) setResult(RESULT_OK, Intent())
            finish()
        }
    }

    private fun tell(title: Int, message: String) {
        saving = false
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setPositiveButton(android.R.string.ok, null).show()
    }

    private fun askToSave() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unsaved_changes_prompt)
            .setPositiveButton(R.string.yes) { _, _ -> save() }
            .setNegativeButton(R.string.no) { _, _ -> finish() }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        // A rule not saved yet has nothing to delete; closing discards it, as in the group editor.
        menu.findItem(R.id.action_delete)?.isVisible = intent.getLongExtra(EXTRA_ROUTE_ID, 0L) != 0L
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (!::draft.isInitialized) return super.onOptionsItemSelected(item)
        when (item.itemId) {
            R.id.action_apply -> save()

            R.id.action_delete -> {
                val id = draft.id
                if (id == 0L) return true.also { finish() }
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.delete_route_prompt)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { ProfileManager.deleteRule(id) }
                            finish()
                        }
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            }

            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // The up button goes through Back, which asks before dropping changes.
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    companion object {
        const val EXTRA_ROUTE_ID = "id"
        const val EXTRA_PACKAGE_NAME = "pkg"
        const val EXTRA_DOMAIN = "domain"
        const val EXTRA_IP = "ip"

        private const val DRAFT = "draft"
        private const val ORIGINAL = "original"
        private const val MORE = "more"
        private const val WORDS = 120L
        private const val TYPING = 250L

        /** Lists to offer: an entry and its label, or null for the name of its country. */
        private val SITE_LISTS = listOf("geosite:category-ads-all" to R.string.route_editor_ads, "geosite:cn" to null, "geosite:category-ir" to null, "geosite:category-ru" to null)
        private val ADDRESS_LISTS = listOf("geoip:private" to R.string.route_words_local_network, "geoip:cn" to null, "geoip:ir" to null, "geoip:ru" to null)
    }
}
