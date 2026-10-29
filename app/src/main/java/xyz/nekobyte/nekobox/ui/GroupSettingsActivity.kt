package xyz.nekobyte.nekobox.ui

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.parcelize.Parcelize
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.SubscriptionFilterMode
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.GroupManager
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProfileManager
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.database.SubscriptionBean
import xyz.nekobyte.nekobox.database.preference.OnPreferenceDataStoreChangeListener
import xyz.nekobyte.nekobox.databinding.ItemGroupChainBinding
import xyz.nekobyte.nekobox.databinding.ItemGroupOfferBinding
import xyz.nekobyte.nekobox.databinding.LayoutGroupEditorBinding
import xyz.nekobyte.nekobox.group.GroupUpdater
import xyz.nekobyte.nekobox.group.SubscriptionFormat
import xyz.nekobyte.nekobox.group.SubscriptionRecovery
import xyz.nekobyte.nekobox.ktx.USER_AGENT
import xyz.nekobyte.nekobox.ktx.applyDefaultValues
import xyz.nekobyte.nekobox.ktx.beginFold
import xyz.nekobyte.nekobox.ktx.onMainDispatcher
import xyz.nekobyte.nekobox.ktx.runOnDefaultDispatcher

/**
 * Makes or changes a group. The common choices come first: the name, what the group holds and,
 * for a subscription, its link and how it updates; the rest is under More options, open when the
 * group uses any of it. The form writes the group's settings as you go; nothing is stored until
 * Save, and leaving with changes asks first.
 */
class GroupSettingsActivity :
    ThemedActivity(),
    OnPreferenceDataStoreChangeListener {

    private lateinit var binding: LayoutGroupEditorBinding

    // Links the provider proposed in its last update; shown for approval, never fetched before.
    private var pendingLinkOffer = ""
    private var pendingBackupOffer = ""
    private var isFromClipboard = false

    // The group as stored, for what the provider reported; null for a new one.
    private var stored: ProxyGroup? = null

    // Set while the form shows the settings, so that its listeners don't write them back.
    private var filling = false

    fun ProxyGroup.init() {
        DataStore.groupName = name ?: ""
        DataStore.groupType = type
        DataStore.groupOrder = order
        DataStore.groupIsSelector = isSelector
        DataStore.groupAutoSelect = autoSelect

        DataStore.frontProxy = frontProxy
        DataStore.landingProxy = landingProxy
        DataStore.frontProxyTmp =
            if (frontProxy >= 0) PROXY_PROFILE else 0
        DataStore.landingProxyTmp =
            if (landingProxy >= 0) PROXY_PROFILE else 0

        val subscription = subscription ?: SubscriptionBean().applyDefaultValues()
        DataStore.subscriptionLink = subscription.link!!
        DataStore.subscriptionFormat = subscription.outputFormat ?: 0
        DataStore.subscriptionImportWarning = subscription.importWarning.orEmpty()
        DataStore.subscriptionBackupLinks = subscription.backupLinks.orEmpty()
        pendingLinkOffer = subscription.offeredLink.orEmpty()
        pendingBackupOffer = subscription.offeredBackupLink.orEmpty()
        DataStore.subscriptionForceResolve = subscription.forceResolve!!
        DataStore.subscriptionDeduplication = subscription.deduplication!!
        DataStore.subscriptionUpdateWhenConnectedOnly = subscription.updateWhenConnectedOnly!!
        DataStore.subscriptionUserAgent = subscription.customUserAgent!!
        DataStore.subscriptionSendDeviceId = subscription.sendDeviceId!!
        DataStore.subscriptionAutoUpdate = subscription.autoUpdate!!
        DataStore.subscriptionAutoUpdateDelay = subscription.autoUpdateDelay!!
        DataStore.subscriptionFilterMode = subscription.filterMode!!
        DataStore.subscriptionFilterRegex = subscription.filterRegex!!
        DataStore.subscriptionCustomDns = subscription.customDnsResolver ?: ""
    }

    fun ProxyGroup.serialize() {
        name = DataStore.groupName.takeIf { it.isNotBlank() } ?: getString(R.string.group_default_name)
        type = DataStore.groupType
        order = DataStore.groupOrder
        isSelector = DataStore.groupIsSelector
        // The switch is only disabled, not cleared, when the selector is turned off.
        autoSelect = isSelector && DataStore.groupAutoSelect

        frontProxy =
            if (DataStore.frontProxyTmp == PROXY_PROFILE) {
                DataStore.frontProxy
            } else {
                -1
            }
        landingProxy =
            if (DataStore.landingProxyTmp == PROXY_PROFILE) {
                DataStore.landingProxy
            } else {
                -1
            }

        val isSubscription = type == GroupType.SUBSCRIPTION
        if (isSubscription) {
            subscription = (subscription ?: SubscriptionBean().applyDefaultValues()).apply {
                // Only an accepted update records which request the stored profiles came from. A new
                // User-Agent, or a switch between a link and a file, leaves that unknown, so the next
                // update asks before it replaces profiles it does not match.
                val userAgentChanged = customUserAgent.orEmpty().ifBlank { "" } != DataStore.subscriptionUserAgent.ifBlank { "" }
                val kindChanged = link.orEmpty().startsWith("content://") != DataStore.subscriptionLink.startsWith("content://")
                if ((lastUpdated ?: 0) > 0 && (userAgentChanged || kindChanged)) negotiatedFormat = SubscriptionFormat.CHANGED
                link = DataStore.subscriptionLink
                outputFormat = DataStore.subscriptionFormat
                backupLinks = DataStore.subscriptionBackupLinks.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                // An accepted offer is now the link or a backup; one still pending stays.
                val approved = backupLinks!!.lines() + link
                if (offeredLink in approved) offeredLink = ""
                if (offeredBackupLink in approved) offeredBackupLink = ""
                forceResolve = DataStore.subscriptionForceResolve
                deduplication = DataStore.subscriptionDeduplication
                updateWhenConnectedOnly = DataStore.subscriptionUpdateWhenConnectedOnly
                customUserAgent = DataStore.subscriptionUserAgent
                sendDeviceId = DataStore.subscriptionSendDeviceId
                autoUpdate = DataStore.subscriptionAutoUpdate
                autoUpdateDelay = DataStore.subscriptionAutoUpdateDelay
                filterMode = DataStore.subscriptionFilterMode
                filterRegex = DataStore.subscriptionFilterRegex
                customDnsResolver = DataStore.subscriptionCustomDns
            }
        }
    }

    fun needSave(): Boolean = DataStore.dirty

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutGroupEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        onBackPressedDispatcher.addCallback(this) {
            if (needSave()) {
                UnsavedChangesDialogFragment().apply { key() }.show(supportFragmentManager, null)
            } else {
                finish()
            }
        }
        setSupportActionBar(findViewById(R.id.toolbar))
        val editingId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)
        supportActionBar?.apply {
            setTitle(if (editingId == 0L) R.string.group_editor_new else R.string.group_editor_edit)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        val bottom = binding.scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.scroll) { view, insets ->
            view.updatePadding(bottom = bottom + insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()).bottom)
            insets
        }

        if (savedInstanceState != null) {
            // The settings store still holds the draft; the offers and the flag come back from the state.
            pendingLinkOffer = savedInstanceState.getString(LINK_OFFER).orEmpty()
            pendingBackupOffer = savedInstanceState.getString(BACKUP_OFFER).orEmpty()
            isFromClipboard = savedInstanceState.getBoolean(EXTRA_FROM_CLIPBOARD)
            start(open = savedInstanceState.getBoolean(MORE))
            DataStore.profileCacheStore.registerChangeListener(this)
            runOnDefaultDispatcher {
                val group = DataStore.editingId.takeIf { it > 0 }?.let { ProfileDatabase.groupDao.getById(it) }
                onMainDispatcher {
                    stored = group
                    showProvider()
                }
            }
            return
        }
        isFromClipboard = intent.getBooleanExtra(EXTRA_FROM_CLIPBOARD, false)
        val subscriptionLink = intent.getStringExtra(EXTRA_GROUP_SUBSCRIPTION_LINK)
        DataStore.editingId = editingId
        runOnDefaultDispatcher {
            var prefilled = false
            if (editingId == 0L) {
                ProxyGroup().init()
                if (intent.getBooleanExtra(EXTRA_SUBSCRIPTION, false)) DataStore.groupType = GroupType.SUBSCRIPTION
                // A link from elsewhere makes it a subscription with that link.
                if (!subscriptionLink.isNullOrEmpty()) {
                    DataStore.groupType = GroupType.SUBSCRIPTION
                    DataStore.subscriptionLink = subscriptionLink
                    prefilled = true
                }
            } else {
                val entity = ProfileDatabase.groupDao.getById(editingId)
                if (entity == null) {
                    onMainDispatcher { finish() }
                    return@runOnDefaultDispatcher
                }
                entity.init()
                stored = entity
            }
            onMainDispatcher {
                start(open = null)
                DataStore.dirty = prefilled
                DataStore.profileCacheStore.registerChangeListener(this@GroupSettingsActivity)
            }
        }
    }

    // Android sets the fields' text back after a rotation; that is not an edit.
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        filling = true
        try {
            super.onRestoreInstanceState(savedInstanceState)
        } finally {
            filling = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(LINK_OFFER, pendingLinkOffer)
        outState.putString(BACKUP_OFFER, pendingBackupOffer)
        outState.putBoolean(EXTRA_FROM_CLIPBOARD, isFromClipboard)
        if (::binding.isInitialized) outState.putBoolean(MORE, binding.more.isVisible)
    }

    /** Shows the settings in the form and listens to it; [open] folds More options, or by what the group uses when null. */
    private fun start(open: Boolean?) {
        val defaults = SubscriptionBean().applyDefaultValues()
        filling = true
        binding.name.setText(DataStore.groupName)
        binding.kind.check(if (DataStore.groupType == GroupType.SUBSCRIPTION) R.id.kind_subscription else R.id.kind_own)
        binding.link.setText(DataStore.subscriptionLink)
        binding.autoUpdate.isChecked = DataStore.subscriptionAutoUpdate
        binding.interval.setText(DataStore.subscriptionAutoUpdateDelay.toString())
        binding.order.check(
            when (DataStore.groupOrder) {
                1 -> R.id.order_name
                2 -> R.id.order_latency
                else -> R.id.order_listed
            },
        )
        binding.selector.isChecked = DataStore.groupIsSelector
        binding.autoSelect.isChecked = DataStore.groupAutoSelect
        binding.backups.setText(DataStore.subscriptionBackupLinks)
        binding.resolve.isChecked = DataStore.subscriptionForceResolve
        binding.dedupe.isChecked = DataStore.subscriptionDeduplication
        binding.filter.check(
            when (DataStore.subscriptionFilterMode) {
                SubscriptionFilterMode.INCLUDE -> R.id.filter_keep
                SubscriptionFilterMode.EXCLUDE -> R.id.filter_drop
                else -> R.id.filter_off
            },
        )
        binding.filterRegex.setText(DataStore.subscriptionFilterRegex)
        binding.dns.setText(DataStore.subscriptionCustomDns)
        binding.connectedOnly.isChecked = DataStore.subscriptionUpdateWhenConnectedOnly
        binding.agent.setText(DataStore.subscriptionUserAgent)
        binding.agentLayout.placeholderText = USER_AGENT
        binding.deviceId.isChecked = DataStore.subscriptionSendDeviceId
        offerFormats()
        offerIntervals()
        filling = false

        fun EditText.writes(write: (String) -> Unit) = doAfterTextChanged {
            if (filling) return@doAfterTextChanged
            write(it?.toString().orEmpty())
            describe()
        }
        binding.name.writes { DataStore.groupName = it }
        binding.link.writes {
            DataStore.subscriptionLink = it
            checkLink()
        }
        binding.interval.writes { text ->
            binding.intervalLayout.error = null
            // Only a valid interval is kept; Save says what is wrong with another.
            text.toIntOrNull()?.takeIf { it >= 15 }?.let { DataStore.subscriptionAutoUpdateDelay = it }
            showIntervals()
        }
        binding.backups.writes {
            binding.backupsLayout.error = null
            DataStore.subscriptionBackupLinks = it
        }
        binding.filterRegex.writes { DataStore.subscriptionFilterRegex = it }
        binding.dns.writes {
            binding.dnsLayout.error = null
            DataStore.subscriptionCustomDns = it
        }
        binding.agent.writes { DataStore.subscriptionUserAgent = it }
        binding.kind.setOnCheckedStateChangeListener { _, checked ->
            if (filling) return@setOnCheckedStateChangeListener
            DataStore.groupType = if (checked.firstOrNull() == R.id.kind_subscription) GroupType.SUBSCRIPTION else GroupType.BASIC
            showKind(animate = true)
        }
        binding.autoUpdate.setOnCheckedChangeListener { _, on ->
            if (filling) return@setOnCheckedChangeListener
            DataStore.subscriptionAutoUpdate = on
            showIntervals()
            describe()
        }
        binding.order.setOnCheckedStateChangeListener { _, checked ->
            if (filling) return@setOnCheckedStateChangeListener
            DataStore.groupOrder = when (checked.firstOrNull()) {
                R.id.order_name -> 1
                R.id.order_latency -> 2
                else -> 0
            }
        }
        // Turning the selector off clears automatic selection, so the disabled switch shows what will be saved.
        binding.selector.setOnCheckedChangeListener { _, on ->
            if (filling) return@setOnCheckedChangeListener
            DataStore.groupIsSelector = on
            if (!on) binding.autoSelect.isChecked = false
            binding.autoSelect.isEnabled = on
        }
        binding.autoSelect.isEnabled = DataStore.groupIsSelector
        binding.autoSelect.setOnCheckedChangeListener { _, on -> if (!filling) DataStore.groupAutoSelect = on }
        binding.resolve.setOnCheckedChangeListener { _, on -> if (!filling) DataStore.subscriptionForceResolve = on }
        binding.dedupe.setOnCheckedChangeListener { _, on -> if (!filling) DataStore.subscriptionDeduplication = on }
        binding.connectedOnly.setOnCheckedChangeListener { _, on -> if (!filling) DataStore.subscriptionUpdateWhenConnectedOnly = on }
        binding.deviceId.setOnCheckedChangeListener { _, on -> if (!filling) DataStore.subscriptionSendDeviceId = on }
        binding.filter.setOnCheckedStateChangeListener { _, checked ->
            if (filling) return@setOnCheckedStateChangeListener
            DataStore.subscriptionFilterMode = when (checked.firstOrNull()) {
                R.id.filter_keep -> SubscriptionFilterMode.INCLUDE
                R.id.filter_drop -> SubscriptionFilterMode.EXCLUDE
                else -> SubscriptionFilterMode.DISABLED
            }
            binding.filterRegexLayout.isVisible = DataStore.subscriptionFilterMode != SubscriptionFilterMode.DISABLED
        }
        binding.filterRegexLayout.isVisible = DataStore.subscriptionFilterMode != SubscriptionFilterMode.DISABLED
        chain(binding.first, R.string.group_editor_first, front = true)
        chain(binding.last, R.string.group_editor_last, front = false)
        binding.moreToggle.setOnClickListener { showMore(!binding.more.isVisible, animate = true) }

        val usesMore = DataStore.groupOrder != 0 || DataStore.groupIsSelector ||
            DataStore.frontProxyTmp == PROXY_PROFILE || DataStore.landingProxyTmp == PROXY_PROFILE ||
            DataStore.subscriptionBackupLinks.isNotBlank() || DataStore.subscriptionFormat != (defaults.outputFormat ?: 0) ||
            DataStore.subscriptionForceResolve != defaults.forceResolve || DataStore.subscriptionDeduplication != defaults.deduplication ||
            DataStore.subscriptionFilterMode != SubscriptionFilterMode.DISABLED || DataStore.subscriptionCustomDns.isNotBlank() ||
            DataStore.subscriptionUpdateWhenConnectedOnly != defaults.updateWhenConnectedOnly || DataStore.subscriptionUserAgent.isNotBlank() ||
            DataStore.subscriptionSendDeviceId != defaults.sendDeviceId
        showMore(open ?: usesMore, animate = false)
        showOffers()
        showKind(animate = false)
        showIntervals()
        checkLink()
        showProvider()
        describe()
    }

    /** The formats a subscription can be asked for, one radio button each. */
    private fun offerFormats() {
        if (binding.format.isNotEmpty()) return
        resources.getStringArray(R.array.subscription_formats).forEachIndexed { index, label ->
            val button = MaterialRadioButton(this).apply {
                id = ViewCompat.generateViewId()
                text = label
                minHeight = (48 * resources.displayMetrics.density).toInt()
                tag = index
            }
            binding.format.addView(button)
            if (index == DataStore.subscriptionFormat) button.isChecked = true
        }
        binding.format.setOnCheckedChangeListener { group, id ->
            if (!filling) DataStore.subscriptionFormat = group.findViewById<MaterialRadioButton>(id)?.tag as? Int ?: 0
        }
    }

    /** Quick picks for how often to update, which fill in the minutes. */
    private fun offerIntervals() {
        if (binding.intervals.isNotEmpty()) return
        for (minutes in INTERVALS) {
            val chip = layoutInflater.inflate(R.layout.item_route_list_chip, binding.intervals, false) as Chip
            chip.text = GroupWords.interval(this, minutes)
            chip.tag = minutes
            chip.setOnClickListener {
                binding.interval.setText(minutes.toString())
                binding.interval.setSelection(binding.interval.length())
            }
            binding.intervals.addView(chip)
        }
    }

    private fun showIntervals() {
        val on = DataStore.subscriptionAutoUpdate
        binding.intervals.isVisible = on
        binding.intervalLayout.isVisible = on
        val minutes = binding.interval.text?.toString()?.toIntOrNull()
        for (i in 0 until binding.intervals.childCount) {
            val chip = binding.intervals.getChildAt(i) as Chip
            chip.isChecked = chip.tag == minutes
        }
    }

    private fun showKind(animate: Boolean) {
        if (animate) (binding.scroll.getChildAt(0) as ViewGroup).beginFold(binding.kind)
        val subscription = DataStore.groupType == GroupType.SUBSCRIPTION
        binding.subscription.isVisible = subscription
        binding.subscriptionMore.isVisible = subscription
        describe()
    }

    private fun showMore(open: Boolean, animate: Boolean) {
        if (animate) (binding.scroll.getChildAt(0) as ViewGroup).beginFold(binding.moreToggle)
        binding.more.isVisible = open
        binding.moreToggle.setText(if (open) R.string.route_editor_fewer else R.string.route_editor_more)
        binding.moreToggle.setIconResource(if (open) R.drawable.ic_expand_less_24 else R.drawable.ic_expand_more_24)
    }

    // A line break never belongs in a link and blocks saving; a cleartext link is allowed, and the help line says what it risks.
    private fun checkLink() {
        val link = DataStore.subscriptionLink
        binding.linkLayout.error = getString(R.string.group_editor_link_line).takeIf { "\n" in link }
        binding.linkLayout.helperText = getString(if (link.startsWith("http://", ignoreCase = true)) R.string.cleartext_http_warning else R.string.group_editor_link_help)
    }

    /** The sentence on top: what the group holds and, for a subscription, where from and how it updates. */
    private fun describe() {
        binding.sentence.text = if (DataStore.groupType != GroupType.SUBSCRIPTION) {
            getString(R.string.group_editor_sentence_own)
        } else {
            val link = DataStore.subscriptionLink.trim()
            val updates = if (DataStore.subscriptionAutoUpdate) {
                getString(R.string.group_editor_updates_every, GroupWords.interval(this, DataStore.subscriptionAutoUpdateDelay.coerceAtLeast(1)))
            } else {
                getString(R.string.group_editor_updates_manual)
            }
            val host = link.toHttpUrlOrNull()?.host
            when {
                link.toUri().scheme == "content" -> getString(R.string.group_editor_sentence_file, updates)
                host != null -> getString(R.string.group_editor_sentence_subscription, host, updates)
                else -> getString(R.string.group_editor_sentence_new)
            }
        }
    }

    /** What the provider reported at the last update, for a stored subscription. */
    private fun showProvider() {
        val group = stored?.takeIf { it.type == GroupType.SUBSCRIPTION }
        val subscription = group?.subscription
        binding.provider.isVisible = subscription != null
        if (group == null || subscription == null) return
        runOnDefaultDispatcher {
            val count = ProfileDatabase.proxyDao.countByGroup(group.id).toInt()
            onMainDispatcher {
                val now = System.currentTimeMillis()
                val quota = GroupWords.quota(subscription)
                binding.provider.text = listOfNotNull(
                    resources.getQuantityString(R.plurals.group_profiles, count, count),
                    GroupWords.traffic(this@GroupSettingsActivity, quota),
                    GroupWords.ends(this@GroupSettingsActivity, quota, now),
                    GroupWords.updated(this@GroupSettingsActivity, subscription, now),
                ).joinToString(" · ")
            }
        }
    }

    private fun showOffers() {
        binding.warning.isVisible = DataStore.subscriptionImportWarning.isNotBlank()
        binding.warning.text = DataStore.subscriptionImportWarning
        // A new link and a backup can both wait; each is approved on its own.
        binding.offers.removeAllViews()
        for ((title, offered) in listOf(R.string.subscription_offered_link to pendingLinkOffer, R.string.subscription_offered_backup_link to pendingBackupOffer)) {
            if (offered.isEmpty()) continue
            val card = ItemGroupOfferBinding.inflate(layoutInflater, binding.offers, true)
            card.offerTitle.setText(title)
            card.offerLink.text = offered
            card.offerAccept.setOnClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle(title)
                    .setMessage(getString(R.string.subscription_offer_message, offered, offered.toHttpUrlOrNull()?.host ?: offered))
                    .setPositiveButton(R.string.subscription_offer_accept) { _, _ -> accept(offered, link = title == R.string.subscription_offered_link) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    // Accepting fills in the link or backups like typing them would; saving the group applies it.
    private fun accept(offered: String, link: Boolean) {
        fun backups(links: List<String>, without: String) = links.map { it.trim() }
            .filter { it != without && SubscriptionRecovery.isHttpsLink(it) }
            .distinct()
            .take(SubscriptionRecovery.MAX_BACKUP_LINKS)
            .joinToString("\n")
        if (link) {
            // The replaced link stays available as the first backup when it is HTTPS.
            val previous = binding.link.text?.toString().orEmpty()
            binding.link.setText(offered)
            binding.backups.setText(backups(listOf(previous) + binding.backups.text.toString().lines(), offered))
            pendingLinkOffer = ""
        } else {
            binding.backups.setText(backups(binding.backups.text.toString().lines() + offered, binding.link.text.toString()))
            pendingBackupOffer = ""
        }
        showMore(true, animate = true)
        showOffers()
    }

    /** A row for the profile traffic goes through first or leaves through last, picked from the profiles. */
    private fun chain(row: ItemGroupChainBinding, title: Int, front: Boolean) {
        row.chainTitle.setText(title)
        row.chainClear.contentDescription = getString(R.string.group_editor_clear, getString(title))
        row.root.setOnClickListener {
            val current = if (front) DataStore.frontProxy else DataStore.landingProxy
            runOnDefaultDispatcher {
                val selected = ProfileManager.getProfile(current)
                onMainDispatcher {
                    (if (front) selectFront else selectLanding).launch(
                        Intent(this@GroupSettingsActivity, ProfileSelectActivity::class.java).apply { selected?.let { putExtra(ProfileSelectActivity.EXTRA_SELECTED, it) } },
                    )
                }
            }
        }
        row.chainClear.setOnClickListener {
            if (front) DataStore.frontProxyTmp = 0 else DataStore.landingProxyTmp = 0
            showChain(row, front)
        }
        showChain(row, front)
    }

    private fun showChain(row: ItemGroupChainBinding, front: Boolean) {
        val set = (if (front) DataStore.frontProxyTmp else DataStore.landingProxyTmp) == PROXY_PROFILE
        val id = if (front) DataStore.frontProxy else DataStore.landingProxy
        row.chainClear.isVisible = set
        if (!set) return row.chainValue.setText(R.string.group_editor_none)
        runOnDefaultDispatcher {
            val name = ProfileManager.getProfile(id)?.displayName() ?: getString(R.string.route_profile_missing)
            onMainDispatcher { row.chainValue.text = name }
        }
    }

    /** What keeps the group from being saved, shown at its field; true when nothing does. */
    private fun check(): Boolean {
        // An update under way writes the group back when it ends, over anything saved meanwhile.
        if (DataStore.editingId in GroupUpdater.updating) {
            snackbar(R.string.group_editor_wait_update).show()
            return false
        }
        if (DataStore.groupType != GroupType.SUBSCRIPTION) return true
        var fine = true
        if (DataStore.subscriptionLink.isBlank()) {
            binding.linkLayout.error = getString(R.string.group_editor_link_missing)
            fine = false
        } else if ("\n" in DataStore.subscriptionLink) {
            fine = false
        }
        val minutes = binding.interval.text?.toString()?.toIntOrNull()
        if (DataStore.subscriptionAutoUpdate && (minutes == null || minutes < 15)) {
            binding.intervalLayout.error = getString(R.string.group_editor_interval_invalid)
            fine = false
        }
        val backups = DataStore.subscriptionBackupLinks.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (backups.size > SubscriptionRecovery.MAX_BACKUP_LINKS || !backups.all(SubscriptionRecovery::isHttpsLink)) {
            binding.backupsLayout.error = getString(R.string.subscription_backup_links_invalid, SubscriptionRecovery.MAX_BACKUP_LINKS)
            showMore(true, animate = false)
            fine = false
        }
        val dns = DataStore.subscriptionCustomDns.trim()
        if (!isValidCustomDnsResolver(dns)) {
            binding.dnsLayout.error = getString(R.string.subscription_custom_dns_invalid)
            showMore(true, animate = false)
            fine = false
        } else {
            DataStore.subscriptionCustomDns = dns
        }
        return fine
    }

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                val activity = requireActivity() as GroupSettingsActivity
                if (activity.check()) runOnDefaultDispatcher { activity.saveAndExit() }
            }
            setNegativeButton(R.string.no) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    @Parcelize
    data class GroupIdArg(val groupId: Long) : Parcelable
    class DeleteConfirmationDialogFragment : AlertDialogFragment<GroupIdArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.delete_group_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    GroupManager.deleteGroup(arg.groupId)
                }
                requireActivity().finish()
            }
            setNegativeButton(R.string.no, null)
        }
    }

    suspend fun saveAndExit() {
        val editingId = DataStore.editingId
        if (editingId == 0L) {
            val newGroup = GroupManager.createGroup(ProxyGroup().apply { serialize() })
            if (isFromClipboard && newGroup.type == GroupType.SUBSCRIPTION && !newGroup.subscription?.link.isNullOrEmpty()) {
                GroupUpdater.startUpdate(newGroup, true)
            }
        } else if (needSave()) {
            val entity = ProfileDatabase.groupDao.getById(DataStore.editingId)
            if (entity == null) {
                finish()
                return
            }
            val keepUserInfo = (
                entity.type == GroupType.SUBSCRIPTION &&
                    DataStore.groupType == GroupType.SUBSCRIPTION &&
                    entity.subscription?.link == DataStore.subscriptionLink
                )
            if (!keepUserInfo) {
                entity.subscription?.subscriptionUserinfo = ""
            }
            val previousMode = Triple(entity.isSelector, entity.autoSelect, entity.frontProxy to entity.landingProxy)
            GroupManager.updateGroup(entity.apply { serialize() })
            val currentMode = Triple(entity.isSelector, entity.autoSelect, entity.frontProxy to entity.landingProxy)
            // The running config bakes in the group's selection mode and chain, so apply changes
            // to it right away instead of leaving the old mode active until the next restart.
            if (previousMode != currentMode && DataStore.serviceState.started &&
                ProfileDatabase.proxyDao.getById(DataStore.currentProfile)?.groupId == entity.id
            ) {
                NekoBox.reloadService()
            }
        }

        finish()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar = Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG)

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        // A group not saved yet has nothing to delete; closing discards it.
        menu.findItem(R.id.action_delete)?.isVisible = intent.getLongExtra(EXTRA_GROUP_ID, 0L) != 0L
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_apply -> if (check()) runOnDefaultDispatcher { saveAndExit() }

            R.id.action_delete -> {
                if (DataStore.editingId == 0L) {
                    finish()
                } else {
                    DeleteConfirmationDialogFragment().apply {
                        arg(GroupIdArg(DataStore.editingId))
                        key()
                    }.show(supportFragmentManager, null)
                }
            }

            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        // Route the action-bar up button through the back dispatcher so it honors the
        // unsaved-changes guard instead of finishing directly (avoids data loss).
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            DataStore.dirty = true
        }
    }

    private val selectFront = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { picked(it.resultCode, it.data, front = true) }
    private val selectLanding = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { picked(it.resultCode, it.data, front = false) }

    private fun picked(resultCode: Int, data: Intent?, front: Boolean) {
        if (resultCode != Activity.RESULT_OK) return
        val id = data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0) ?: return
        if (id <= 0) return
        if (front) {
            DataStore.frontProxy = id
            DataStore.frontProxyTmp = PROXY_PROFILE
        } else {
            DataStore.landingProxy = id
            DataStore.landingProxyTmp = PROXY_PROFILE
        }
        showChain(if (front) binding.first else binding.last, front)
    }

    object PasswordSummaryProvider : Preference.SummaryProvider<EditTextPreference> {

        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val text = preference.text
            return if (text.isNullOrBlank()) {
                preference.context.getString(androidx.preference.R.string.not_set)
            } else {
                "\u2022".repeat(text.length)
            }
        }
    }

    companion object {
        const val EXTRA_GROUP_ID = "id"
        const val EXTRA_FROM_CLIPBOARD = "fromClipboard"
        const val EXTRA_GROUP_SUBSCRIPTION_LINK = "subscription_link"

        /** A new group that starts as a subscription, waiting for its link. */
        const val EXTRA_SUBSCRIPTION = "subscription"

        /** frontProxyTmp and landingProxyTmp hold this while a profile is picked as the front or landing proxy. */
        private const val PROXY_PROFILE = 3

        private const val LINK_OFFER = "linkOffer"
        private const val BACKUP_OFFER = "backupOffer"
        private const val MORE = "more"

        /** Quick picks for the update interval, in minutes: an hour, six and twelve hours, a day. */
        private val INTERVALS = listOf(60, 360, 720, 1440)
    }
}

/**
 * Validate a per-subscription custom resolver value.
 * Empty = unset (allowed). Otherwise must be one of:
 *  - a URL with scheme https/tls/quic and a non-empty host
 *  - a bare IPv4/IPv6 literal or host[:port]
 * No default is implied; sing-box performs final parsing at runtime.
 */
private fun isValidCustomDnsResolver(raw: String): Boolean {
    val value = raw.trim()
    if (value.isEmpty()) return true
    if (value.any { it.isISOControl() || it.isWhitespace() }) return false

    if (value.contains("://")) {
        val scheme = value.substringBefore("://").lowercase()
        if (scheme !in setOf("https", "tls", "quic")) return false
        val rest = value.substringAfter("://")
        val host = rest.substringBefore("/").substringBefore("?")
        // strip optional [ipv6] / host:port; require a non-empty host
        val bare = host.substringBeforeLast(":").trim('[', ']')
        return bare.isNotEmpty()
    }

    // bare host[:port] / ip[:port]
    val host = value.substringBeforeLast(":").trim('[', ']')
    return host.isNotEmpty()
}
