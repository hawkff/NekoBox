package xyz.nekobyte.nekobox.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.SubscriptionFilterMode
import xyz.nekobyte.nekobox.database.*
import xyz.nekobyte.nekobox.database.preference.OnPreferenceDataStoreChangeListener
import xyz.nekobyte.nekobox.group.GroupUpdater
import xyz.nekobyte.nekobox.group.SubscriptionFormat
import xyz.nekobyte.nekobox.group.SubscriptionRecovery
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.applyDefaultValues
import xyz.nekobyte.nekobox.ktx.onMainDispatcher
import xyz.nekobyte.nekobox.ktx.runOnDefaultDispatcher
import xyz.nekobyte.nekobox.widget.ListListener
import xyz.nekobyte.nekobox.widget.OutboundPreference

@Suppress("UNCHECKED_CAST")
class GroupSettingsActivity(
    @LayoutRes resId: Int = R.layout.layout_config_settings,
) : ThemedActivity(resId),
    OnPreferenceDataStoreChangeListener {

    private lateinit var frontProxyPreference: OutboundPreference
    private lateinit var landingProxyPreference: OutboundPreference

    // Links the provider proposed in its last update; shown for approval, never fetched before.
    private var pendingLinkOffer = ""
    private var pendingBackupOffer = ""

    fun ProxyGroup.init() {
        DataStore.groupName = name ?: ""
        DataStore.groupType = type
        DataStore.groupOrder = order
        DataStore.groupIsSelector = isSelector
        DataStore.groupAutoSelect = autoSelect

        DataStore.frontProxy = frontProxy
        DataStore.landingProxy = landingProxy
        DataStore.frontProxyTmp =
            if (frontProxy >= 0) OutboundPreference.VALUE_SELECT_PROFILE.toInt() else 0
        DataStore.landingProxyTmp =
            if (landingProxy >= 0) OutboundPreference.VALUE_SELECT_PROFILE.toInt() else 0

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
        name = DataStore.groupName.takeIf { it.isNotBlank() } ?: "My group"
        type = DataStore.groupType
        order = DataStore.groupOrder
        isSelector = DataStore.groupIsSelector
        // The switch is only disabled, not cleared, when the selector is turned off.
        autoSelect = isSelector && DataStore.groupAutoSelect

        frontProxy =
            if (DataStore.frontProxyTmp == OutboundPreference.VALUE_SELECT_PROFILE.toInt()) {
                DataStore.frontProxy
            } else {
                -1
            }
        landingProxy =
            if (DataStore.landingProxyTmp == OutboundPreference.VALUE_SELECT_PROFILE.toInt()) {
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

    private var isFromClipboard = false

    fun needSave(): Boolean = DataStore.dirty

    fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.group_preferences)
        findPreference<Preference>(Key.SUBSCRIPTION_IMPORT_WARNING)!!.apply {
            summary = DataStore.subscriptionImportWarning
            isVisible = DataStore.subscriptionImportWarning.isNotBlank()
        }

        val linkPreference = findPreference<EditTextPreference>(Key.SUBSCRIPTION_LINK)!!
        val backupLinksPreference = findPreference<EditTextPreference>(Key.SUBSCRIPTION_BACKUP_LINKS)!!.apply {
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
                preference.text.orEmpty().trim().ifEmpty {
                    getString(R.string.subscription_backup_links_summary, SubscriptionRecovery.MAX_BACKUP_LINKS)
                }
            }
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            setOnPreferenceChangeListener { _, newValue ->
                val links = (newValue as String).lines().map { it.trim() }.filter { it.isNotEmpty() }
                val valid = links.size <= SubscriptionRecovery.MAX_BACKUP_LINKS && links.all(SubscriptionRecovery::isHttpsLink)
                if (!valid) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.subscription_backup_links_invalid, SubscriptionRecovery.MAX_BACKUP_LINKS),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                valid
            }
        }
        fun backupLinks(links: List<String>, without: String) = links.map { it.trim() }
            .filter { it != without && SubscriptionRecovery.isHttpsLink(it) }
            .distinct()
            .take(SubscriptionRecovery.MAX_BACKUP_LINKS)
            .joinToString("\n")

        // Accepting fills in the link or backups like typing them would; saving the group applies it.
        fun offer(key: String, offered: String, accept: () -> Unit) = findPreference<Preference>(key)!!.apply {
            isVisible = offered.isNotEmpty()
            summary = offered
            setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(title)
                    .setMessage(getString(R.string.subscription_offer_message, offered, offered.toHttpUrlOrNull()?.host ?: offered))
                    .setPositiveButton(R.string.subscription_offer_accept) { _, _ ->
                        accept()
                        isVisible = false
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        }
        offer(Key.SUBSCRIPTION_OFFERED_LINK, pendingLinkOffer) {
            // The replaced link stays available as the first backup when it is HTTPS.
            val previous = linkPreference.text.orEmpty()
            linkPreference.text = pendingLinkOffer
            backupLinksPreference.text = backupLinks(listOf(previous) + backupLinksPreference.text.orEmpty().lines(), pendingLinkOffer)
        }
        offer(Key.SUBSCRIPTION_OFFERED_BACKUP_LINK, pendingBackupOffer) {
            backupLinksPreference.text = backupLinks(backupLinksPreference.text.orEmpty().lines() + pendingBackupOffer, linkPreference.text.orEmpty())
        }

        // Turning the selector off clears automatic selection so the disabled switch shows the
        // value that will be saved.
        val autoSelectPreference = findPreference<SwitchPreference>(Key.GROUP_AUTO_SELECT)!!
        findPreference<SwitchPreference>(Key.GROUP_IS_SELECTOR)!!.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == false) autoSelectPreference.isChecked = false
            true
        }

        frontProxyPreference = findPreference(Key.GROUP_FRONT_PROXY)!!
        frontProxyPreference.apply {
            setEntries(R.array.front_proxy_entry)
            setEntryValues(R.array.front_proxy_value)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == OutboundPreference.VALUE_SELECT_PROFILE) {
                    selectProfileForAddFront.launch(
                        Intent(
                            this@GroupSettingsActivity,
                            ProfileSelectActivity::class.java,
                        ).apply {
                            ProfileManager.getProfile(DataStore.frontProxy)?.let {
                                putExtra(ProfileSelectActivity.EXTRA_SELECTED, it)
                            }
                        },
                    )
                    false
                } else {
                    true
                }
            }
        }
        landingProxyPreference = findPreference(Key.GROUP_LANDING_PROXY)!!
        landingProxyPreference.apply {
            setEntries(R.array.front_proxy_entry)
            setEntryValues(R.array.front_proxy_value)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == OutboundPreference.VALUE_SELECT_PROFILE) {
                    selectProfileForAddLanding.launch(
                        Intent(
                            this@GroupSettingsActivity,
                            ProfileSelectActivity::class.java,
                        ).apply {
                            ProfileManager.getProfile(DataStore.landingProxy)?.let {
                                putExtra(ProfileSelectActivity.EXTRA_SELECTED, it)
                            }
                        },
                    )
                    false
                } else {
                    true
                }
            }
        }

        val groupType = findPreference<SimpleMenuPreference>(Key.GROUP_TYPE)!!
        val groupSubscription = findPreference<PreferenceCategory>(Key.GROUP_SUBSCRIPTION)!!
        val subscriptionUpdate = findPreference<PreferenceCategory>(Key.SUBSCRIPTION_UPDATE)!!

        fun updateGroupType(groupType: Int = DataStore.groupType) {
            val isSubscription = groupType == GroupType.SUBSCRIPTION
            groupSubscription.isVisible = isSubscription
            subscriptionUpdate.isVisible = isSubscription
        }
        updateGroupType()
        groupType.setOnPreferenceChangeListener { _, newValue ->
            updateGroupType((newValue as String).toInt())
            true
        }

        val subscriptionAutoUpdate =
            findPreference<SwitchPreference>(Key.SUBSCRIPTION_AUTO_UPDATE)!!
        val subscriptionAutoUpdateDelay =
            findPreference<EditTextPreference>(Key.SUBSCRIPTION_AUTO_UPDATE_DELAY)!!

        subscriptionAutoUpdateDelay.isEnabled = subscriptionAutoUpdate.isChecked
        subscriptionAutoUpdateDelay.setOnPreferenceChangeListener { _, newValue ->
            val delay = (newValue as String).toIntOrNull()
            if (delay == null) {
                false
            } else {
                delay >= 15
            }
        }
        subscriptionAutoUpdate.setOnPreferenceChangeListener { _, newValue ->
            subscriptionAutoUpdateDelay.isEnabled = (newValue as Boolean)
            true
        }

        val subscriptionFilterMode =
            findPreference<SimpleMenuPreference>(Key.SUBSCRIPTION_FILTER_MODE)!!
        val subscriptionFilterRegex =
            findPreference<EditTextPreference>(Key.SUBSCRIPTION_FILTER_REGEX)!!

        fun updateFilterMode(filterMode: Int = DataStore.subscriptionFilterMode) {
            subscriptionFilterRegex.isVisible = filterMode != SubscriptionFilterMode.DISABLED
        }
        updateFilterMode()
        subscriptionFilterMode.setOnPreferenceChangeListener { _, newValue ->
            updateFilterMode((newValue as String).toInt())
            true
        }

        val subscriptionCustomDns =
            findPreference<EditTextPreference>(Key.SUBSCRIPTION_CUSTOM_DNS)!!
        subscriptionCustomDns.setOnPreferenceChangeListener { pref, newValue ->
            val value = (newValue as String).trim()
            if (isValidCustomDnsResolver(value)) {
                // Persist the normalized (trimmed) value rather than the raw input.
                if (value != newValue) {
                    (pref as EditTextPreference).text = value
                    false
                } else {
                    true
                }
            } else {
                Toast.makeText(
                    requireContext(),
                    R.string.subscription_custom_dns_invalid,
                    Toast.LENGTH_LONG,
                ).show()
                false
            }
        }
    }

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    (requireActivity() as GroupSettingsActivity).saveAndExit()
                }
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

    companion object {
        const val EXTRA_GROUP_ID = "id"
        const val EXTRA_FROM_CLIPBOARD = "fromClipboard"
        const val EXTRA_GROUP_SUBSCRIPTION_LINK = "subscription_link"
    }

    @SuppressLint("CommitTransaction")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) {
            if (needSave()) {
                UnsavedChangesDialogFragment().apply { key() }.show(supportFragmentManager, null)
            } else {
                finish()
            }
        }
        // ViewBinding intentionally not used here: this activity sets its content via the
        // ThemedActivity(@LayoutRes) constructor (contentLayoutId), not by inflating a binding,
        // so the toolbar is resolved with findViewById.
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.group_settings)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        if (savedInstanceState == null) {
            val editingId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)
            isFromClipboard = intent.getBooleanExtra(EXTRA_FROM_CLIPBOARD, false)
            val subscriptionLink = intent.getStringExtra(EXTRA_GROUP_SUBSCRIPTION_LINK)
            DataStore.editingId = editingId
            runOnDefaultDispatcher {
                if (editingId == 0L) {
                    val group = ProxyGroup()
                    group.init()

                    // if there's a subscription link, set it to subscription type and fill in the link
                    if (!subscriptionLink.isNullOrEmpty()) {
                        DataStore.groupType = GroupType.SUBSCRIPTION
                        DataStore.subscriptionLink = subscriptionLink
                        DataStore.dirty = true
                    }
                } else {
                    val entity = ProfileDatabase.groupDao.getById(editingId)
                    if (entity == null) {
                        onMainDispatcher {
                            finish()
                        }
                        return@runOnDefaultDispatcher
                    }
                    entity.init()
                }

                onMainDispatcher {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.settings, MyPreferenceFragmentCompat())
                        .commit()

                    DataStore.dirty = false
                    DataStore.profileCacheStore.registerChangeListener(this@GroupSettingsActivity)
                }
            }
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

    val child by lazy { supportFragmentManager.findFragmentById(R.id.settings) as MyPreferenceFragmentCompat }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = child.onOptionsItemSelected(item)

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

    class MyPreferenceFragmentCompat : PreferenceFragmentCompat() {

        var activity: GroupSettingsActivity? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            try {
                activity = (requireActivity() as GroupSettingsActivity).apply {
                    createPreferences(savedInstanceState, rootKey)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    NekoBox.application,
                    "Error on createPreferences, please try again.",
                    Toast.LENGTH_SHORT,
                ).show()
                Logs.e(e)
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            ViewCompat.setOnApplyWindowInsetsListener(listView, ListListener)
        }

        override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
            R.id.action_delete -> {
                if (DataStore.editingId == 0L) {
                    requireActivity().finish()
                } else {
                    DeleteConfirmationDialogFragment().apply {
                        arg(GroupIdArg(DataStore.editingId))
                        key()
                    }.show(parentFragmentManager, null)
                }
                true
            }

            R.id.action_apply -> {
                runOnDefaultDispatcher {
                    activity?.saveAndExit()
                }
                true
            }

            else -> false
        }
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

    val selectProfileForAddFront = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (it.resultCode == Activity.RESULT_OK) {
            runOnDefaultDispatcher {
                val profile = ProfileManager.getProfile(
                    it.data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0),
                ) ?: return@runOnDefaultDispatcher
                DataStore.frontProxy = profile.id
                onMainDispatcher {
                    frontProxyPreference.value = OutboundPreference.VALUE_SELECT_PROFILE
                }
            }
        }
    }

    val selectProfileForAddLanding = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (it.resultCode == Activity.RESULT_OK) {
            runOnDefaultDispatcher {
                val profile = ProfileManager.getProfile(
                    it.data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0),
                ) ?: return@runOnDefaultDispatcher
                DataStore.landingProxy = profile.id
                onMainDispatcher {
                    landingProxyPreference.value = OutboundPreference.VALUE_SELECT_PROFILE
                }
            }
        }
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
