package xyz.nekobyte.nekobox.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.GroupManager
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.databinding.LayoutGroupBinding
import xyz.nekobyte.nekobox.databinding.LayoutGroupItemBinding
import xyz.nekobyte.nekobox.fmt.toUniversalLink
import xyz.nekobyte.nekobox.group.GroupUpdater
import xyz.nekobyte.nekobox.ktx.FixedLinearLayoutManager
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.getColorAttr
import xyz.nekobyte.nekobox.ktx.itemMoveIndices
import xyz.nekobyte.nekobox.ktx.launchCustomTab
import xyz.nekobyte.nekobox.ktx.onMainDispatcher
import xyz.nekobyte.nekobox.ktx.readableMessage
import xyz.nekobyte.nekobox.ktx.runOnDefaultDispatcher
import xyz.nekobyte.nekobox.ktx.showAllowingStateLoss
import xyz.nekobyte.nekobox.widget.ListListener
import xyz.nekobyte.nekobox.widget.QRCodeDialog
import xyz.nekobyte.nekobox.widget.UndoSnackbarManager
import xyz.nekobyte.nekobox.widget.attachItemTouch

/**
 * The groups, one compact row each: the name, how many profiles, the quota left and an update
 * button. A row opens in place, one at a time, to show what the provider reports and every action.
 * Drag to reorder, which is also the order of Home's tabs; swipe to remove, with undo.
 */
class GroupFragment :
    ToolbarFragment(R.layout.layout_group),
    Toolbar.OnMenuItemClickListener {

    lateinit var activity: MainActivity
    lateinit var groupListView: RecyclerView
    lateinit var layoutManager: LinearLayoutManager
    lateinit var groupAdapter: GroupAdapter
    lateinit var undoManager: UndoSnackbarManager<ProxyGroup>

    // The group whose row is open, or 0.
    private var openGroup = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity = requireActivity() as MainActivity

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_group)
        toolbar.inflateMenu(R.menu.add_group_menu)
        toolbar.setOnMenuItemClickListener(this)

        groupListView = LayoutGroupBinding.bind(view).groupList
        layoutManager = FixedLinearLayoutManager(groupListView)
        groupListView.layoutManager = layoutManager
        groupAdapter = GroupAdapter()
        GroupManager.addListener(groupAdapter)
        groupListView.adapter = groupAdapter
        FastScrollerBuilder(groupListView).useMd2Style().build()

        undoManager = UndoSnackbarManager(activity, groupAdapter)

        groupListView.attachItemTouch(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.START,
        ) {
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val proxyGroup = (viewHolder as GroupHolder).proxyGroup
                if (proxyGroup.ungrouped || proxyGroup.id in GroupUpdater.updating) {
                    return 0
                }
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val proxyGroup = (viewHolder as GroupHolder).proxyGroup
                if (proxyGroup.ungrouped || proxyGroup.id in GroupUpdater.updating) {
                    return 0
                }
                return super.getDragDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                groupAdapter.remove(index)
                undoManager.remove(index to (viewHolder as GroupHolder).proxyGroup)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                groupAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                groupAdapter.commitMove()
            }
        })
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new_group -> newGroup()

            R.id.action_update_all -> {
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                    .setMessage(R.string.update_all_subscription)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        runOnDefaultDispatcher {
                            ProfileDatabase.groupDao.allGroups()
                                .filter { it.type == GroupType.SUBSCRIPTION }
                                .forEach { GroupUpdater.startUpdate(it, true) }
                        }
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            }
        }
        return true
    }

    // One button, three ways to start a group.
    private fun newGroup() {
        val choices = arrayOf(getString(R.string.group_new_subscription), getString(R.string.group_new_scan), getString(R.string.group_new_empty))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.group_new_title)
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(context, GroupSettingsActivity::class.java).putExtra(GroupSettingsActivity.EXTRA_SUBSCRIPTION, true))
                    1 -> startActivity(Intent(context, ScannerActivity::class.java))
                    else -> startActivity(Intent(context, GroupSettingsActivity::class.java))
                }
            }
            .show()
    }

    private lateinit var selectedGroup: ProxyGroup

    private val exportProfiles =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    val profiles = ProfileDatabase.proxyDao.getByGroup(selectedGroup.id)
                    val links = profiles.joinToString("\n") { it.toStdLink(compact = true) }
                    try {
                        (requireActivity() as MainActivity).contentResolver.openOutputStream(
                            data,
                        )!!.bufferedWriter().use {
                            it.write(links)
                        }
                        onMainDispatcher {
                            snackbar(getString(R.string.action_export_msg)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }
                }
            }
        }

    inner class GroupAdapter :
        RecyclerView.Adapter<GroupHolder>(),
        GroupManager.Listener,
        UndoSnackbarManager.Interface<ProxyGroup> {

        val groupList = ArrayList<ProxyGroup>()

        // Profiles per group, read with the groups.
        val counts = HashMap<Long, Long>()

        suspend fun reload() {
            val groups = ProfileDatabase.groupDao.allGroups().toMutableList()
            val ungrouped = groups.find { it.ungrouped }
            if (groups.size > 1 && ungrouped != null &&
                ProfileDatabase.proxyDao.countByGroup(ungrouped.id) == 0L
            ) {
                groups.removeAll { it.ungrouped }
            }
            val sizes = groups.associate { it.id to ProfileDatabase.proxyDao.countByGroup(it.id) }
            groupListView.post {
                groupList.clear()
                groupList.addAll(groups)
                counts.clear()
                counts.putAll(sizes)
                notifyDataSetChanged()
            }
        }

        init {
            setHasStableIds(true)

            runOnDefaultDispatcher {
                reload()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupHolder = GroupHolder(LayoutGroupItemBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: GroupHolder, position: Int) {
            holder.bind(groupList[position], opening = false)
        }

        override fun onBindViewHolder(holder: GroupHolder, position: Int, payloads: MutableList<Any>) {
            holder.bind(groupList[position], opening = OPEN in payloads)
        }

        override fun getItemCount(): Int = groupList.size

        override fun getItemId(position: Int): Long = groupList[position].id

        private val updated = HashSet<ProxyGroup>()

        fun move(from: Int, to: Int) {
            val first = groupList[from]
            var previousOrder = first.userOrder
            val step = if (from < to) 1 else -1
            for (i in itemMoveIndices(from, to)) {
                val next = groupList[i + step]
                val order = next.userOrder
                next.userOrder = previousOrder
                previousOrder = order
                groupList[i] = next
                updated.add(next)
            }
            first.userOrder = previousOrder
            groupList[to] = first
            updated.add(first)
            notifyItemMoved(from, to)
        }

        fun commitMove() = runOnDefaultDispatcher {
            updated.forEach { ProfileDatabase.groupDao.updateGroup(it) }
            updated.clear()
        }

        fun remove(index: Int) {
            groupList.removeAt(index)
            notifyItemRemoved(index)
        }

        /** Opens the row of [groupId], closing the one open before; again on the same row, closes it. */
        fun toggle(groupId: Long) {
            val before = openGroup
            openGroup = if (before == groupId) 0L else groupId
            for (id in setOf(before, groupId)) {
                val index = groupList.indexOfFirst { it.id == id }
                if (index >= 0) notifyItemChanged(index, OPEN)
            }
        }

        override fun undo(actions: List<Pair<Int, ProxyGroup>>) {
            for ((index, item) in actions) {
                groupList.add(index, item)
                notifyItemInserted(index)
            }
        }

        override fun commit(actions: List<Pair<Int, ProxyGroup>>) {
            val groups = actions.map { it.second }
            runOnDefaultDispatcher {
                GroupManager.deleteGroup(groups)
                reload()
            }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            delay(300L)
            val size = ProfileDatabase.proxyDao.countByGroup(group.id)
            onMainDispatcher {
                undoManager.flush()
                groupList.add(group)
                counts[group.id] = size
                notifyItemInserted(groupList.size - 1)

                if (group.type == GroupType.SUBSCRIPTION) {
                    GroupUpdater.startUpdate(group, true)
                }
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            onMainDispatcher {
                val index = groupList.indexOfFirst { it.id == groupId }
                if (index == -1) return@onMainDispatcher
                undoManager.flush()
                if (ProfileDatabase.groupDao.allGroups().size <= 2) {
                    runOnDefaultDispatcher {
                        reload()
                    }
                } else {
                    groupList.removeAt(index)
                    notifyItemRemoved(index)
                }
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            val size = ProfileDatabase.proxyDao.countByGroup(group.id)
            onMainDispatcher {
                val index = groupList.indexOfFirst { it.id == group.id }
                if (index == -1) {
                    runOnDefaultDispatcher { reload() }
                    return@onMainDispatcher
                }
                undoManager.flush()
                groupList[index] = group
                counts[group.id] = size
                notifyItemChanged(index)
            }
        }

        override suspend fun groupProgressUpdated(groupId: Long) {
            onMainDispatcher {
                (groupListView.findViewHolderForItemId(groupId) as? GroupHolder)?.bindProgress()
            }
        }

        override suspend fun groupUpdated(groupId: Long) {
            val size = ProfileDatabase.proxyDao.countByGroup(groupId)
            onMainDispatcher {
                val index = groupList.indexOfFirst { it.id == groupId }
                if (index == -1) {
                    runOnDefaultDispatcher { reload() }
                    return@onMainDispatcher
                }
                counts[groupId] = size
                notifyItemChanged(index)
            }
        }
    }

    override fun onDestroy() {
        if (::groupAdapter.isInitialized) {
            GroupManager.removeListener(groupAdapter)
        }

        super.onDestroy()

        if (!::undoManager.isInitialized) return
        undoManager.flush()
    }

    inner class GroupHolder(private val binding: LayoutGroupItemBinding) :
        RecyclerView.ViewHolder(binding.root),
        PopupMenu.OnMenuItemClickListener {

        lateinit var proxyGroup: ProxyGroup
        private val card = binding.root

        init {
            card.setOnClickListener { groupAdapter.toggle(proxyGroup.id) }
            binding.update.setOnClickListener { GroupUpdater.startUpdate(proxyGroup, true) }
            binding.updateAction.setOnClickListener { GroupUpdater.startUpdate(proxyGroup, true) }
            binding.edit.setOnClickListener {
                startActivity(Intent(it.context, GroupSettingsActivity::class.java).putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, proxyGroup.id))
            }
            binding.share.setOnClickListener { menu(it, R.menu.group_share_menu) }
            binding.more.setOnClickListener { menu(it, R.menu.group_more_menu) }
        }

        private fun menu(anchor: View, menu: Int) {
            selectedGroup = proxyGroup
            val popup = PopupMenu(requireContext(), anchor)
            popup.menuInflater.inflate(menu, popup.menu)
            val subscription = proxyGroup.subscription.takeIf { proxyGroup.type == GroupType.SUBSCRIPTION }
            if (subscription == null) {
                popup.menu.removeItem(R.id.action_universal_clipboard)
                popup.menu.removeItem(R.id.action_universal_qr)
            }
            if (subscription?.supportUrl.isNullOrBlank()) popup.menu.removeItem(R.id.action_support_url)
            if (subscription?.webPageUrl.isNullOrBlank()) popup.menu.removeItem(R.id.action_web_page_url)
            if (proxyGroup.ungrouped || proxyGroup.id in GroupUpdater.updating) popup.menu.removeItem(R.id.action_delete_group)
            popup.setOnMenuItemClickListener(this)
            popup.show()
        }

        override fun onMenuItemClick(item: MenuItem): Boolean {
            fun export(link: String) {
                val success = NekoBox.trySetPrimaryClip(link)
                activity.snackbar(if (success) R.string.action_export_msg else R.string.action_export_err).show()
            }

            when (item.itemId) {
                R.id.action_universal_qr -> QRCodeDialog(proxyGroup.toUniversalLink(), proxyGroup.displayName()).showAllowingStateLoss(parentFragmentManager)

                R.id.action_universal_clipboard -> export(proxyGroup.toUniversalLink())

                R.id.action_export_clipboard -> runOnDefaultDispatcher {
                    val links = ProfileDatabase.proxyDao.getByGroup(selectedGroup.id).joinToString("\n") { it.toStdLink(compact = true) }
                    onMainDispatcher {
                        NekoBox.trySetPrimaryClip(links)
                        snackbar(getString(R.string.copy_toast_msg)).show()
                    }
                }

                R.id.action_export_file -> startFilesForResult(exportProfiles, "profiles_${proxyGroup.displayName()}.txt")

                R.id.action_support_url -> requireContext().launchCustomTab(proxyGroup.subscription!!.supportUrl!!)

                R.id.action_web_page_url -> requireContext().launchCustomTab(proxyGroup.subscription!!.webPageUrl!!)

                R.id.action_clear -> {
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                        .setMessage(R.string.clear_profiles_message)
                        .setPositiveButton(R.string.yes) { _, _ -> runOnDefaultDispatcher { GroupManager.clearGroup(proxyGroup.id) } }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }

                // Like a swipe: the row goes at once, and the snackbar can bring it back.
                R.id.action_delete_group -> {
                    val index = bindingAdapterPosition
                    if (index != RecyclerView.NO_POSITION) {
                        groupAdapter.remove(index)
                        undoManager.remove(index to proxyGroup)
                    }
                }
            }
            return true
        }

        /** The update under way: a bar along the top, real progress when the updater knows it. */
        fun bindProgress() {
            val updating = proxyGroup.id in GroupUpdater.updating
            val subscription = proxyGroup.type == GroupType.SUBSCRIPTION
            binding.updateProgress.isVisible = updating
            val progress = GroupUpdater.progress[proxyGroup.id]
            binding.updateProgress.isIndeterminate = progress == null
            if (progress != null) {
                binding.updateProgress.max = progress.max
                binding.updateProgress.progress = progress.progress
            }
            // A subscription's button makes room for the bar while it updates; a group of your own has none.
            binding.update.visibility = when {
                !subscription -> View.GONE
                updating -> View.INVISIBLE
                else -> View.VISIBLE
            }
            binding.updateSpace.isVisible = !subscription
            binding.updateAction.isVisible = subscription && !updating
            // The update writes the group back when it ends, so an edit saved meanwhile would be lost.
            binding.edit.isEnabled = !updating
        }

        /** Shows [group]; [opening] when its row has just been opened, so the details fade in and the bar fills. */
        fun bind(group: ProxyGroup, opening: Boolean) {
            proxyGroup = group
            val context = card.context
            val open = openGroup == group.id
            val count = groupAdapter.counts[group.id] ?: 0L
            val profiles = context.resources.getQuantityString(R.plurals.group_profiles, count.toInt(), count.toInt())
            binding.name.text = group.displayName()
            binding.count.text = count.toString()
            binding.chevron.setImageResource(if (open) R.drawable.ic_expand_less_24 else R.drawable.ic_expand_more_24)
            binding.details.isVisible = open
            binding.edit.isVisible = !group.ungrouped
            bindProgress()

            // The quota: a small meter in the row, the numbers when it opens.
            val subscription = group.subscription.takeIf { group.type == GroupType.SUBSCRIPTION }
            val quota = GroupWords.quota(subscription)
            val total = quota?.total
            val now = System.currentTimeMillis()
            val left = quota?.daysLeft(now)
            val meterColor = context.getColorAttr(if (quota?.low(now) == true) R.attr.colorError else R.attr.colorPrimary)
            // The small meter in the row becomes the full bar of the open row.
            val used = total?.let { ((quota.used * 100) / it).toInt().coerceIn(if (quota.used > 0) 1 else 0, 100) }
            binding.meter.isVisible = used != null && !open
            binding.trafficMeter.isVisible = used != null
            for (meter in listOf(binding.meter, binding.trafficMeter)) {
                meter.setIndicatorColor(meterColor)
                if (used != null) meter.progress = used
            }
            // Both animations jump to their end when animations are off.
            binding.details.animate().cancel()
            binding.details.alpha = 1f
            if (opening && open) {
                binding.details.alpha = 0f
                binding.details.animate().alpha(1f).setDuration(OPEN_FADE).start()
                if (used != null) {
                    binding.trafficMeter.progress = 0
                    binding.trafficMeter.setProgressCompat(used, true)
                }
            }
            binding.days.isVisible = left != null
            if (left != null) {
                binding.days.text = context.getString(R.string.group_days_short, left.coerceAtLeast(0))
                binding.days.setTextColor(context.getColorAttr(if (left <= 3) R.attr.colorError else android.R.attr.textColorSecondary))
            }
            val traffic = GroupWords.traffic(context, quota)
            binding.traffic.isVisible = traffic != null
            binding.traffic.text = traffic
            val ends = GroupWords.ends(context, quota, now)
            val updated = subscription?.let { GroupWords.updated(context, it, now) }
            binding.facts.text = listOfNotNull(profiles, ends, updated).joinToString(" · ")
            val announce = subscription?.announce?.takeIf { it.isNotBlank() }
            binding.announce.isVisible = announce != null
            binding.announce.text = announce

            // TalkBack hears the whole row, and what tapping it does.
            card.contentDescription = listOfNotNull(
                group.displayName(),
                profiles,
                traffic,
                left?.let { days -> context.resources.getQuantityString(R.plurals.group_days_left, days.coerceAtLeast(0), days.coerceAtLeast(0)) },
                updated,
            ).joinToString(". ")
            ViewCompat.setStateDescription(card, context.getString(if (open) R.string.group_expanded else R.string.group_collapsed))
            ViewCompat.replaceAccessibilityAction(card, AccessibilityActionCompat.ACTION_CLICK, context.getString(if (open) R.string.group_hide_details else R.string.group_show_details), null)
        }
    }

    private companion object {
        const val OPEN = "open"
        const val OPEN_FADE = 200L
    }
}
