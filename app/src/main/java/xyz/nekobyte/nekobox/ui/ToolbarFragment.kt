package xyz.nekobyte.nekobox.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.widget.glass.Glass
import xyz.nekobyte.nekobox.widget.glass.GlassBackdropFrameLayout
import xyz.nekobyte.nekobox.widget.glass.GlassLayout

/**
 * A drawer screen with its toolbar. A layout that puts the toolbar, and what rides with it, in
 * `glass_bars` and the rest in a `glass_content` backdrop below them gets liquid glass as Home has
 * it: the content fills the screen and scrolls beneath the floating bars.
 */
open class ToolbarFragment : Fragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    lateinit var toolbar: Toolbar

    /** Under liquid glass, the room the content keeps clear at its top for the floating bars. */
    var glassInset = 0
        private set

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // ViewBinding intentionally not used here: this is a base class whose concrete view is
        // supplied by each subclass's own layout (layout_route/layout_group/layout_about/...).
        // The toolbar id is shared across those layouts, so findViewById on the subclass view is
        // the correct generic lookup.
        toolbar = view.findViewById(R.id.toolbar)
        val bars = view.findViewById<ViewGroup>(R.id.glass_bars)
        val content = view.findViewById<GlassBackdropFrameLayout>(R.id.glass_content)
        if (Glass.enabled && bars != null && content != null) {
            Glass.floatBars(bars, content, toolbar, { glassPanes(bars) }) { inset ->
                glassInset = inset
                onGlassInset(content, inset)
            }
            // Screens inside this one, such as settings pages and tool tabs, scroll beneath the bars too.
            childFragmentManager.registerFragmentLifecycleCallbacks(glassChildren, false)
        } else {
            view.findViewById<View>(R.id.appbar)?.let(Glass::styleAppBar)
        }
        // With the connect button on every screen, every list ends above it, as Home's does. Posted: a
        // screen gives its lists their layout after this runs.
        if (DataStore.showBottomBar) {
            view.post { clearBottomBar(view) }
            childFragmentManager.registerFragmentLifecycleCallbacks(bottomBarChildren, false)
        }
        showDrawerButton()
    }

    /** The navigation button opens the drawer. */
    protected fun showDrawerButton() {
        toolbar.setNavigationIcon(R.drawable.ic_navigation_menu)
        toolbar.setNavigationContentDescription(R.string.open_menu)
        toolbar.setNavigationOnClickListener {
            (activity as MainActivity).binding.drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    /** Panes for what floats with the toolbar besides its buttons, such as tabs or a search field. */
    open fun GlassLayout.glassPanes(bars: ViewGroup) = Unit

    /** The floating bars changed height: the content, and the screens inside it, keep [inset] clear at their top. */
    open fun onGlassInset(content: View, inset: Int) {
        val roots = listOf(content) + childFragmentManager.fragments.filter { it !is DialogFragment }.mapNotNull { it.view }
        for (root in roots) Glass.verticalScrollers(root).forEach { Glass.insetScroller(it, inset) }
    }

    private val glassChildren = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
            if (f !is DialogFragment) Glass.verticalScrollers(v).forEach { Glass.insetScroller(it, glassInset) }
        }
    }

    // Screens inside this one, such as settings pages and tool tabs, end above the button too.
    private val bottomBarChildren = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
            if (f !is DialogFragment) clearBottomBar(v)
        }
    }

    /** Lets the last row of each list in [root] scroll above the connect button; lists with that room already keep it. */
    private fun clearBottomBar(root: View) {
        val room = resources.getDimensionPixelSize(R.dimen.bottom_bar_room)
        for (scroller in Glass.verticalScrollers(root)) {
            (scroller as? ViewGroup)?.clipToPadding = false
            scroller.updatePadding(bottom = maxOf(scroller.paddingBottom, room))
        }
    }

    override fun onDestroyView() {
        childFragmentManager.unregisterFragmentLifecycleCallbacks(glassChildren)
        childFragmentManager.unregisterFragmentLifecycleCallbacks(bottomBarChildren)
        super.onDestroyView()
    }

    /** Whether this screen stays, hidden and paused, while another shows, so coming back to it is instant. */
    open val keepsAlive get() = false

    /** Whether the settings this screen reads once, when built, still have the values it was built with. */
    open fun current() = true

    open fun onKeyDown(ketCode: Int, event: KeyEvent) = false
    open fun onBackPressed(): Boolean = false
}
