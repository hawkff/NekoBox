package xyz.nekobyte.nekobox.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.databinding.LayoutToolsBinding
import xyz.nekobyte.nekobox.widget.glass.Glass
import xyz.nekobyte.nekobox.widget.glass.GlassLayout

class ToolsFragment : ToolbarFragment(R.layout.layout_tools) {
    // Its tabs keep what they last showed, as they do while open.
    override val keepsAlive get() = true

    private lateinit var tabs: TabLayout

    override fun GlassLayout.glassPanes(bars: ViewGroup) = paneOver(bars, tabs, 0f, 0f)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_tools)

        val tools = mutableListOf<NamedFragment>()
        tools.add(NetworkFragment())
        tools.add(BackupFragment())

        val binding = LayoutToolsBinding.bind(view)
        tabs = binding.toolsTab
        binding.toolsPager.adapter = ToolsAdapter(tools)
        if (Glass.enabled) {
            // The tabs ride with the toolbar as a glass capsule, like the group tabs.
            tabs.background = null
            Glass.styleTabs(tabs, tabs)
        }

        TabLayoutMediator(binding.toolsTab, binding.toolsPager) { tab, position ->
            tab.text = tools[position].name()
            tab.view.setOnLongClickListener {
                // clear toast
                true
            }
        }.attach()
    }

    inner class ToolsAdapter(val tools: List<Fragment>) : FragmentStateAdapter(this) {

        override fun getItemCount() = tools.size

        override fun createFragment(position: Int) = tools[position]
    }
}
