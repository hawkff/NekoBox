package xyz.nekobyte.nekobox.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.appcompat.widget.Toolbar
import androidx.core.content.getSystemService
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProfileManager
import xyz.nekobyte.nekobox.database.RuleEntity
import xyz.nekobyte.nekobox.databinding.LayoutRouteBinding
import xyz.nekobyte.nekobox.databinding.LayoutRouteEndBinding
import xyz.nekobyte.nekobox.databinding.LayoutRouteItemBinding
import xyz.nekobyte.nekobox.fmt.RouteAnswer
import xyz.nekobyte.nekobox.fmt.RouteDecision
import xyz.nekobyte.nekobox.fmt.RouteVerdict
import xyz.nekobyte.nekobox.ktx.FixedLinearLayoutManager
import xyz.nekobyte.nekobox.ktx.getColorAttr
import xyz.nekobyte.nekobox.ktx.itemMoveIndices
import xyz.nekobyte.nekobox.ktx.onMainDispatcher
import xyz.nekobyte.nekobox.ktx.runOnDefaultDispatcher
import xyz.nekobyte.nekobox.widget.CardHighlight
import xyz.nekobyte.nekobox.widget.ListListener
import xyz.nekobyte.nekobox.widget.UndoSnackbarManager
import xyz.nekobyte.nekobox.widget.attachItemTouch
import xyz.nekobyte.nekobox.widget.glass.Glass
import xyz.nekobyte.nekobox.widget.glass.GlassLayout

/**
 * The route rules in the order the core tries them, and a tester above them that says where a site,
 * an address or an app goes: the rules it passes fade, and the one that catches it stands out.
 */
class RouteFragment :
    ToolbarFragment(R.layout.layout_route),
    Toolbar.OnMenuItemClickListener {

    /** A rule and its words, made off the main thread. */
    class RuleRow(val rule: RuleEntity, val title: String, val summary: String, val destination: String, @param:ColorInt val color: Int)

    /** The tester's answer and its words, made off the main thread. [verdicts] has the line under each rule. */
    private class Tested(val answer: RouteAnswer?, val destination: String?, @param:ColorInt val color: Int, val by: String, val notes: String?, val verdicts: List<String?>)

    private lateinit var binding: LayoutRouteBinding
    private lateinit var ruleAdapter: RuleAdapter
    private lateinit var undoManager: UndoSnackbarManager<RuleRow>
    private var tested: Tested? = null
    private var testing: Job? = null
    private var showMatchWhenAnswered = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = LayoutRouteBinding.bind(view)
        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_route)
        toolbar.inflateMenu(R.menu.add_route_menu)
        toolbar.setOnMenuItemClickListener(this)

        val list = binding.routeList
        list.layoutManager = FixedLinearLayoutManager(list)
        ruleAdapter = RuleAdapter()
        ProfileManager.addListener(ruleAdapter)
        list.adapter = ruleAdapter
        FastScrollerBuilder(list).useMd2Style().build()
        undoManager = UndoSnackbarManager(requireActivity() as MainActivity, ruleAdapter)
        list.attachItemTouch(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START) {
            override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) = if (viewHolder is RuleHolder) super.getDragDirs(recyclerView, viewHolder) else 0

            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) = if (viewHolder is RuleHolder) super.getSwipeDirs(recyclerView, viewHolder) else 0

            override fun canDropOver(recyclerView: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = target is RuleHolder

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                undoManager.remove(index to ruleAdapter.remove(index))
            }

            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                ruleAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                ruleAdapter.commitMove()
            }
        })

        val header = binding.header
        header.globalOff.setOnClickListener {
            DataStore.globalMode = false
            showGlobalNotice()
            test()
            needReload()
        }
        header.probe.doAfterTextChanged { test() }
        // Search on the keyboard means done typing: the rule that catches it comes into view.
        header.probe.setOnEditorActionListener { field, action, _ ->
            if (action != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            field.context.getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(field.windowToken, 0)
            if (testing == null) showMatch() else showMatchWhenAnswered = true
            true
        }
        header.answer.setOnClickListener { showMatch() }
        if (Glass.enabled) {
            // The field floats as a glass capsule over the rules: no box or underline of its own.
            header.probeLayout.boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_NONE
            header.probe.background = null
        }
    }

    override fun GlassLayout.glassPanes(bars: ViewGroup) = paneOver(bars, binding.header.probeLayout, 0f, 0f)

    override fun onResume() {
        super.onResume()
        // Global mode and the VPN settings may have changed elsewhere.
        showGlobalNotice()
        test()
    }

    // The adapter and its listener belong to the view, made with it in onViewCreated.
    override fun onDestroyView() {
        if (::ruleAdapter.isInitialized) ProfileManager.removeListener(ruleAdapter)
        super.onDestroyView()
    }

    private fun showGlobalNotice() {
        binding.header.globalNotice.isVisible = DataStore.globalMode
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_reset_route -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.route_reset)
                    .setMessage(R.string.route_reset_message)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        runOnDefaultDispatcher {
                            ProfileDatabase.rulesDao.reset()
                            DataStore.rulesFirstCreate = false
                            ruleAdapter.reload()
                        }
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            }

            R.id.action_manage_assets -> startActivity(Intent(requireContext(), AssetsActivity::class.java))

            R.id.action_routing_profiles -> startActivity(Intent(requireContext(), RoutingProfilesActivity::class.java))
        }
        return true
    }

    /** Asks the tester about what the field holds, a moment after typing stops. */
    private fun test() {
        if (!::ruleAdapter.isInitialized || view == null) return
        testing?.cancel()
        val text = binding.header.probe.text?.toString().orEmpty().trim()
        if (text.isEmpty()) return show(null)
        val context = requireContext()
        val rows = ruleAdapter.rows.toList()
        testing = viewLifecycleOwner.lifecycleScope.launch {
            delay(TYPING)
            show(withContext(Dispatchers.Default) { ask(context, text, rows) })
        }
    }

    private fun ask(context: Context, text: String, rows: List<RuleRow>): Tested {
        val (probe, app) = RouteChecks.probe(text) ?: return Tested(null, null, 0, context.getString(R.string.route_test_nothing, text), null, emptyList())
        // Most traffic is TCP; UDP gets a note when it goes elsewhere, as QUIC does past a rule that blocks it.
        val tester = RouteChecks.tester()
        val rules = rows.map { it.rule }
        val answer = tester.test(rules, probe.copy(network = "tcp"))
        val overUdp = tester.test(rules, probe.copy(network = "udp")).decision
        val (destination, color) = destination(context, answer.decision, rows)
        val by = when (val decision = answer.decision) {
            is RouteDecision.Rule -> context.getString(R.string.route_answer_rule, decision.index + 1, rows[decision.index].title)
            RouteDecision.Dns -> context.getString(R.string.route_answer_dns)
            RouteDecision.AppOutsideVpn -> context.getString(R.string.route_answer_app_outside)
            RouteDecision.LocalOutsideVpn -> context.getString(R.string.route_answer_local_outside)
            RouteDecision.GlobalMode -> context.getString(R.string.route_answer_global)
            RouteDecision.LocalNetwork -> context.getString(R.string.route_answer_local)
            RouteDecision.Proxy -> context.getString(R.string.route_answer_proxy)
        }
        val udp = overUdp.takeIf { it != answer.decision }?.let { decision ->
            val (where, _) = destination(context, decision, rows)
            if (decision is RouteDecision.Rule) {
                context.getString(R.string.route_answer_udp_rule, where, decision.index + 1)
            } else {
                context.getString(R.string.route_answer_udp, where)
            }
        }
        // Earlier rules that might still catch it first are marked on their rows; the answer counts them.
        val doubts = answer.verdicts.count { it is RouteVerdict.CatchesIf || it is RouteVerdict.Unknown }
        val notes = listOfNotNull(
            app?.let { context.getString(R.string.route_test_app, it) },
            udp,
            doubts.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.route_answer_doubts, it, it) },
        )
        val verdicts = answer.verdicts.map { verdict ->
            when (verdict) {
                RouteVerdict.Catches -> context.getString(R.string.route_verdict_catches)
                is RouteVerdict.CatchesIf -> context.getString(R.string.route_verdict_maybe, RuleWords.conditions(context, verdict.conditions))
                is RouteVerdict.Unknown -> context.getString(R.string.route_verdict_unknown, RuleWords.unknown(context, verdict.reason))
                RouteVerdict.Passes, null -> null
            }
        }
        return Tested(answer, destination, color, by, notes.joinToString("\n").ifEmpty { null }, verdicts)
    }

    /** Where [decision] sends traffic, in words and in the destination's color. */
    private fun destination(context: Context, decision: RouteDecision, rows: List<RuleRow>): Pair<String, Int> {
        val direct = RuleWords.outboundColor(context, -1L)
        return when (decision) {
            is RouteDecision.Rule -> rows[decision.index].destination to rows[decision.index].color
            RouteDecision.Dns -> context.getString(R.string.route_destination_dns) to context.getColorAttr(android.R.attr.textColorSecondary)
            RouteDecision.AppOutsideVpn, RouteDecision.LocalOutsideVpn -> context.getString(R.string.route_destination_outside_vpn) to direct
            RouteDecision.LocalNetwork -> RuleWords.outbound(context, -1L) to direct
            RouteDecision.GlobalMode, RouteDecision.Proxy -> RuleWords.outbound(context, 0L) to RuleWords.outboundColor(context, 0L)
        }
    }

    private fun showMatch() {
        val decision = tested?.answer?.decision ?: return
        binding.routeList.smoothScrollToPosition(if (decision is RouteDecision.Rule) decision.index else ruleAdapter.rows.size)
    }

    private fun show(result: Tested?) {
        testing = null
        tested = result
        val header = binding.header
        header.answer.isVisible = result != null
        // An answer brings its deciding rule into view, and TalkBack says so; "nothing called that" leads nowhere.
        val leads = result?.answer != null
        header.answer.isClickable = leads
        if (leads) {
            ViewCompat.replaceAccessibilityAction(header.answer, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, getString(R.string.route_answer_show), null)
        } else {
            ViewCompat.removeAccessibilityAction(header.answer, AccessibilityNodeInfoCompat.ACTION_CLICK)
        }
        if (result != null) {
            header.answerDestination.isVisible = result.destination != null
            result.destination?.let { header.answerDestination.paintDestination(it, result.color) }
            header.answerBy.text = result.by
            header.answerNotes.text = result.notes
            header.answerNotes.isVisible = result.notes != null
        }
        val first = (binding.routeList.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
        ruleAdapter.firstVisible = first.coerceAtLeast(0)
        ruleAdapter.notifyItemRangeChanged(0, ruleAdapter.itemCount, VERDICT)
        if (showMatchWhenAnswered) {
            showMatchWhenAnswered = false
            showMatch()
        }
    }

    private fun TextView.paintDestination(text: String, @ColorInt color: Int) {
        this.text = text
        setTextColor(color)
        background = MaterialShapeDrawable(ShapeAppearanceModel.builder().setAllCornerSizes(8 * resources.displayMetrics.density).build()).apply {
            fillColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 0x1F))
        }
    }

    inner class RuleAdapter :
        RecyclerView.Adapter<RecyclerView.ViewHolder>(),
        ProfileManager.RuleListener,
        UndoSnackbarManager.Interface<RuleRow> {

        val rows = ArrayList<RuleRow>()

        // Until the rules are first read, an empty list doesn't mean there are none.
        var loaded = false

        /** The first row on screen when an answer came, which the fade starts from. */
        var firstVisible = 0

        init {
            runOnDefaultDispatcher { reload() }
        }

        suspend fun reload() {
            val context = context ?: return
            val fresh = ProfileManager.getRules().map { it.row(context) }
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                rows.clear()
                rows.addAll(fresh)
                loaded = true
                notifyDataSetChanged()
                test()
            }
        }

        private fun RuleEntity.row(context: Context) = RuleRow(this, RuleWords.title(context, this), RuleWords.summary(context, this), RuleWords.outbound(context, outbound), RuleWords.outboundColor(context, outbound))

        override fun getItemCount() = rows.size + 1

        override fun getItemViewType(position: Int) = if (position < rows.size) RULE else END

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = if (viewType == RULE) {
            RuleHolder(LayoutRouteItemBinding.inflate(layoutInflater, parent, false))
        } else {
            EndHolder(LayoutRouteEndBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = onBindViewHolder(holder, position, emptyList())

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
            val animate = VERDICT in payloads
            when (holder) {
                is RuleHolder -> holder.bind(rows[position], animate)
                is EndHolder -> holder.bind(animate)
            }
        }

        private val moved = HashSet<RuleEntity>()

        fun move(from: Int, to: Int) {
            val first = rows[from]
            var previousOrder = first.rule.userOrder
            val step = if (from < to) 1 else -1
            for (i in itemMoveIndices(from, to)) {
                val next = rows[i + step]
                val order = next.rule.userOrder
                next.rule.userOrder = previousOrder
                previousOrder = order
                rows[i] = next
                moved.add(next.rule)
            }
            first.rule.userOrder = previousOrder
            rows[to] = first
            moved.add(first.rule)
            notifyItemMoved(from, to)
        }

        fun commitMove() {
            if (moved.isEmpty()) return
            val rules = moved.toList()
            moved.clear()
            test()
            runOnDefaultDispatcher {
                ProfileDatabase.rulesDao.updateRules(rules)
                onMainDispatcher { needReload() }
            }
        }

        fun remove(index: Int): RuleRow {
            val row = rows.removeAt(index)
            notifyItemRemoved(index)
            test()
            return row
        }

        override fun undo(actions: List<Pair<Int, RuleRow>>) {
            for ((index, row) in actions) {
                rows.add(index, row)
                notifyItemInserted(index)
            }
            test()
        }

        override fun commit(actions: List<Pair<Int, RuleRow>>) {
            val rules = actions.map { it.second.rule }
            runOnDefaultDispatcher { ProfileManager.deleteRules(rules) }
        }

        override suspend fun onAdd(rule: RuleEntity) {
            val row = rule.row(context ?: return)
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                rows.add(row)
                notifyItemInserted(rows.size - 1)
                test()
                needReload()
            }
        }

        override suspend fun onUpdated(rule: RuleEntity) {
            val row = rule.row(context ?: return)
            onMainDispatcher {
                val index = rows.indexOfFirst { it.rule.id == rule.id }
                if (!isAdded || index < 0) return@onMainDispatcher
                rows[index] = row
                notifyItemChanged(index)
                test()
                needReload()
            }
        }

        override suspend fun onRemoved(ruleId: Long) = onMainDispatcher {
            if (!isAdded) return@onMainDispatcher
            val index = rows.indexOfFirst { it.rule.id == ruleId }
            if (index >= 0) {
                rows.removeAt(index)
                notifyItemRemoved(index)
                test()
            }
            needReload()
        }

        // The whole table was replaced, by a routing profile or a reset: read it again.
        override suspend fun onCleared() {
            reload()
            onMainDispatcher { if (isAdded) needReload() }
        }
    }

    inner class RuleHolder(private val binding: LayoutRouteItemBinding) : RecyclerView.ViewHolder(binding.root) {
        private lateinit var row: RuleRow
        private val card = binding.root
        private val highlight = CardHighlight(card)

        init {
            card.setOnClickListener {
                startActivity(Intent(it.context, RouteSettingsActivity::class.java).putExtra(RouteSettingsActivity.EXTRA_ROUTE_ID, row.rule.id))
            }
            binding.enable.setOnCheckedChangeListener { _, on ->
                if (on == row.rule.enabled) return@setOnCheckedChangeListener
                val rule = row.rule
                rule.enabled = on
                show(animate = true)
                test()
                runOnDefaultDispatcher {
                    ProfileDatabase.rulesDao.updateRule(rule)
                    onMainDispatcher { needReload() }
                }
            }
        }

        fun bind(row: RuleRow, animate: Boolean) {
            this.row = row
            binding.title.text = row.title
            binding.summary.text = row.summary
            binding.enable.isChecked = row.rule.enabled
            binding.enable.contentDescription = row.title
            show(animate)
        }

        /** The rule's number, how it looks against the tester's answer, and what a screen reader says. */
        private fun show(animate: Boolean) {
            val position = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return
            val context = card.context
            val result = tested?.takeIf { it.answer != null }
            val decision = result?.answer?.decision
            val matched = decision is RouteDecision.Rule && decision.index == position
            val verdict = result?.verdicts?.getOrNull(position)
            binding.order.text = (position + 1).toString()
            // An off rule stays readable; its label turns gray and says so, so the colored labels are the rules at work.
            if (row.rule.enabled) {
                binding.destination.paintDestination(row.destination, row.color)
            } else {
                binding.destination.paintDestination(context.getString(R.string.route_rule_off), context.getColorAttr(android.R.attr.textColorSecondary))
            }
            binding.verdict.text = verdict
            binding.verdict.isVisible = verdict != null
            binding.verdict.setTextColor(context.getColorAttr(if (matched) R.attr.colorPrimary else android.R.attr.textColorSecondary))
            val alpha = when {
                result == null -> 1f
                matched || verdict != null -> 1f
                else -> PASSED_ALPHA
            }
            val delay = ((position - ruleAdapter.firstVisible).coerceIn(0, MAX_STAGGER) * STAGGER).toLong()
            for (view in listOf(binding.order, binding.title, binding.summary, binding.destination, binding.verdict)) {
                view.animate().cancel()
                // A pop cut short never runs its end action.
                view.scaleX = 1f
                view.scaleY = 1f
                if (animate) view.animate().alpha(alpha).setStartDelay(delay).setDuration(FADE).start() else view.alpha = alpha
            }
            highlight.show(matched, animate, delay)
            if (animate && matched) {
                binding.destination.animate().scaleX(POP).scaleY(POP).setStartDelay(delay + FADE).setDuration(FADE).withEndAction {
                    binding.destination.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(FADE).start()
                }.start()
            }
            val spoken = when {
                result == null -> null
                matched -> context.getString(R.string.route_verdict_catches)
                verdict != null -> verdict
                decision is RouteDecision.Rule && position > decision.index -> context.getString(R.string.route_verdict_not_reached)
                else -> context.getString(R.string.route_verdict_passes)
            }
            card.contentDescription = listOfNotNull(
                context.getString(R.string.route_rule_description, position + 1, row.title, row.summary, row.destination),
                context.getString(R.string.route_rule_off).takeIf { !row.rule.enabled },
                spoken,
            ).joinToString(". ")
        }
    }

    inner class EndHolder(private val binding: LayoutRouteEndBinding) : RecyclerView.ViewHolder(binding.root) {
        private val highlight = CardHighlight(binding.fallback)

        init {
            binding.add.setOnClickListener { startActivity(Intent(it.context, RouteSettingsActivity::class.java)) }
        }

        fun bind(animate: Boolean) {
            val context = binding.root.context
            binding.empty.isVisible = ruleAdapter.loaded && ruleAdapter.rows.isEmpty()
            binding.fallbackDestination.paintDestination(RuleWords.outbound(context, 0L), RuleWords.outboundColor(context, 0L))
            val decision = tested?.answer?.decision
            val matched = decision == RouteDecision.Proxy || decision == RouteDecision.GlobalMode
            highlight.show(matched, animate, (ruleAdapter.rows.size - ruleAdapter.firstVisible).coerceIn(0, MAX_STAGGER) * STAGGER.toLong())
            ViewCompat.setStateDescription(binding.fallback, context.getString(R.string.route_verdict_catches).takeIf { matched })
        }
    }

    private companion object {
        const val RULE = 0
        const val END = 1
        const val VERDICT = "verdict"
        const val TYPING = 250L

        // The fade runs down the list the way the core reads it, one row after another.
        const val FADE = 160L
        const val STAGGER = 24
        const val MAX_STAGGER = 10
        const val POP = 1.12f
        const val PASSED_ALPHA = 0.38f
    }
}
