package xyz.nekobyte.nekobox.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import xyz.nekobyte.nekobox.R
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

    override fun onDestroyView() {
        childFragmentManager.unregisterFragmentLifecycleCallbacks(glassChildren)
        super.onDestroyView()
    }

    open fun onKeyDown(ketCode: Int, event: KeyEvent) = false
    open fun onBackPressed(): Boolean = false
}
