package xyz.nekobyte.nekobox.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.databinding.LayoutToolsBinding
import xyz.nekobyte.nekobox.widget.glass.Glass
import xyz.nekobyte.nekobox.widget.glass.GlassDrawable

class ToolsFragment : ToolbarFragment(R.layout.layout_tools) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_tools)

        val tools = mutableListOf<NamedFragment>()
        tools.add(NetworkFragment())
        tools.add(BackupFragment())

        val binding = LayoutToolsBinding.bind(view)
        binding.toolsPager.adapter = ToolsAdapter(tools)
        if (Glass.enabled) {
            // The toolbar floats clear of a bar, so the tabs float too: a glass capsule like the group tabs'.
            val tabs = binding.toolsTab
            Glass.styleTabs(tabs, tabs)
            tabs.background = GlassDrawable(tabs, null, Glass.tint(requireContext())) { bounds ->
                pane().run {
                    rect.set(bounds)
                    radius = rect.height() / 2
                }
            }
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
