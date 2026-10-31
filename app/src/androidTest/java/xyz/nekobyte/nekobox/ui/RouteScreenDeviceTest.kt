package xyz.nekobyte.nekobox.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.children
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.GroupManager
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProfileManager
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.database.RoutingProfiles
import xyz.nekobyte.nekobox.database.RuleEntity

/** The Route screen says where traffic goes with the core's own matching, and the editor makes the rule it shows. */
@RunWith(AndroidJUnit4::class)
class RouteScreenDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val names = listOf("Route test sites", "Route test port", "Route test made", "Route test off", "Route test protocol", "Route test theirs")

    @Before
    fun rules() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        runBlocking {
            ProfileManager.getRules()
            ProfileManager.createRule(RuleEntity(name = names[0], enabled = true, domains = "domain:example.org", outbound = -2))
            ProfileManager.createRule(RuleEntity(name = names[1], enabled = true, port = "8443", outbound = -1))
            ProfileManager.createRule(RuleEntity(name = names[3], enabled = false, domains = "domain:example.com", outbound = -1))
            ProfileManager.createRule(RuleEntity(name = names[4], enabled = false, domains = "example.edu", protocol = "tls", outbound = -2))
        }
    }

    @After
    fun clean() = runBlocking {
        ProfileDatabase.rulesDao.allRules().filter { it.name in names }.forEach { ProfileManager.deleteRule(it.id) }
    }

    @Test
    fun theTesterNamesTheRuleThatCatchesItAndTheRowSaysSo() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.displayFragmentWithId(R.id.nav_route) }
            settle()
            lateinit var root: View
            scenario.onActivity { root = it.window.decorView }
            val position = ProfileDatabase.rulesDao.allRules().indexOfFirst { it.name == names[0] } + 1

            ask(root, "www.example.org")
            assertEquals(context.getString(R.string.route_block), text(root, R.id.answer_destination))
            assertEquals(context.getString(R.string.route_answer_rule, position, names[0]), text(root, R.id.answer_by))
            // The answer brings its rule into view; the row says that it catches it.
            instrumentation.runOnMainSync { root.findViewById<View>(R.id.answer).performClick() }
            settle()
            instrumentation.runOnMainSync {
                val list = root.findViewById<RecyclerView>(R.id.route_list)
                val spoken = list.children.mapNotNull { it.contentDescription?.toString() }.first { names[0] in it }
                assertTrue(spoken, spoken.endsWith(context.getString(R.string.route_verdict_catches)))
            }

            ask(root, "203.0.113.7:8443")
            assertEquals(context.getString(R.string.route_bypass), text(root, R.id.answer_destination))
            // Without a port both earlier rules might catch an address first: one line counts them, the rows say how.
            ask(root, "203.0.113.7")
            assertEquals(context.resources.getQuantityString(R.plurals.route_answer_doubts, 2, 2), text(root, R.id.answer_notes))

            // An off rule reads at full strength; its label is gray and says Off.
            ask(root, "")
            val off = ProfileDatabase.rulesDao.allRules().indexOfFirst { it.name == names[3] }
            instrumentation.runOnMainSync { root.findViewById<RecyclerView>(R.id.route_list).scrollToPosition(off) }
            settle()
            instrumentation.runOnMainSync {
                val list = root.findViewById<RecyclerView>(R.id.route_list)
                val row = list.children.first { names[3] in it.contentDescription?.toString().orEmpty() }
                assertEquals(context.getString(R.string.route_rule_off), row.findViewById<TextView>(R.id.destination).text.toString())
                assertEquals(1f, row.findViewById<View>(R.id.title).alpha)
            }

            // Not a site or an address: an app, by the name the launcher shows.
            val label = context.packageManager.getApplicationLabel(context.applicationInfo).toString()
            ask(root, label)
            assertTrue(text(root, R.id.answer_notes).startsWith(context.getString(R.string.route_test_app, label)))
            // A package name reads like a site, but an installed app's means the app.
            ask(root, context.packageName)
            assertTrue(text(root, R.id.answer_notes).startsWith(context.getString(R.string.route_test_app, label)))
        }
    }

    @Test
    fun aSubscriptionsRulesHaveATabThatPutsThemInUseAndMyRulesTakesThemBack() {
        val before = RoutingProfiles.list().map { it.id }.toSet()
        val (group, provider) = runBlocking {
            val group = GroupManager.createGroup(ProxyGroup(name = "Route test provider"))
            val rules = JSONArray().put(BackupFormatV2.encodeRule(RuleEntity(name = names[5], enabled = true, domains = "domain:example.net", outbound = -2)))
            group to RoutingProfiles.store(RoutingProfiles.Profile(0L, "Provider", JSONObject().put("rules", rules), RoutingProfiles.subscriptionSource(group.id)))
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.displayFragmentWithId(R.id.nav_route) }
                settle()
                lateinit var root: View
                scenario.onActivity { root = it.window.decorView }

                // My rules are in use: they open first and change as before.
                instrumentation.runOnMainSync {
                    val tabs = root.findViewById<TabLayout>(R.id.route_tabs)
                    assertEquals(View.VISIBLE, tabs.visibility)
                    assertEquals(context.getString(R.string.route_rules_mine), tabs.getTabAt(tabs.selectedTabPosition)?.text.toString())
                    assertEquals(View.GONE, root.findViewById<View>(R.id.rules_notice).visibility)
                    tabs.getTabAt((0 until tabs.tabCount).single { tabs.getTabAt(it)?.text.toString() == group.displayName() })?.select()
                }
                settle()

                // The subscription's rules read only, with the switch to them in place of the tester.
                assertEquals(context.getString(R.string.route_rules_from_unused, group.displayName()), text(root, R.id.rules_notice_text))
                instrumentation.runOnMainSync {
                    assertEquals(View.VISIBLE, root.findViewById<View>(R.id.use_rules).visibility)
                    assertEquals(View.GONE, root.findViewById<View>(R.id.probe_layout).visibility)
                    assertEquals(View.GONE, root.findViewById<View>(R.id.add).visibility)
                    val list = root.findViewById<RecyclerView>(R.id.route_list)
                    // A rule that can't be edited still opens, to read.
                    assertTrue(list.children.single { names[5] in it.contentDescription?.toString().orEmpty() }.isClickable)
                    root.findViewById<View>(R.id.use_rules).performClick()
                }
                settle()

                assertEquals(provider.id, RoutingProfiles.activeId)
                assertEquals(context.getString(R.string.route_rules_from_used, group.displayName()), text(root, R.id.rules_notice_text))
                // The tester checks the rules in use.
                ask(root, "www.example.net")
                assertEquals(context.getString(R.string.route_block), text(root, R.id.answer_destination))
                ask(root, "")

                instrumentation.runOnMainSync {
                    val tabs = root.findViewById<TabLayout>(R.id.route_tabs)
                    tabs.getTabAt((0 until tabs.tabCount).single { tabs.getTabAt(it)?.text.toString() == context.getString(R.string.route_rules_mine) })?.select()
                }
                settle()
                assertEquals(context.getString(R.string.route_rules_mine_unused), text(root, R.id.rules_notice_text))
                instrumentation.runOnMainSync { root.findViewById<View>(R.id.use_rules).performClick() }
                settle()

                assertEquals(RoutingProfiles.ownProfile()?.id, RoutingProfiles.activeId)
                assertTrue(ProfileDatabase.rulesDao.allRules().any { it.name == names[0] })
                instrumentation.runOnMainSync { assertEquals(View.GONE, root.findViewById<View>(R.id.rules_notice).visibility) }
            }
        } finally {
            runBlocking {
                if (RoutingProfiles.activeId == provider.id) RoutingProfiles.switchToOwn()
                GroupManager.deleteGroup(group.id)
                RoutingProfiles.list().filter { it.id !in before }.forEach { RoutingProfiles.delete(it.id) }
            }
        }
    }

    @Test
    fun theEditorMakesTheRuleItDescribes() {
        val intent = Intent(context, RouteSettingsActivity::class.java).putExtra(RouteSettingsActivity.EXTRA_DOMAIN, "example.net")
        ActivityScenario.launch<RouteSettingsActivity>(intent).use { scenario ->
            settle()
            lateinit var root: View
            scenario.onActivity { root = it.window.decorView }
            // A rule without detected protocols, sources, rule DNS or custom JSON keeps them folded.
            instrumentation.runOnMainSync {
                assertEquals(View.GONE, root.findViewById<View>(R.id.more).visibility)
                root.findViewById<View>(R.id.more_toggle).performClick()
            }
            settle()
            instrumentation.runOnMainSync {
                assertEquals(View.VISIBLE, root.findViewById<View>(R.id.more).visibility)
                root.findViewById<EditText>(R.id.name).setText(names[2])
                root.findViewById<View>(R.id.to_block).performClick()
                root.findViewById<EditText>(R.id.try_probe).setText("www.example.net")
            }
            settle()
            assertEquals(context.getString(R.string.route_editor_sentence, "example.net", context.getString(R.string.route_block)), text(root, R.id.sentence))
            assertEquals(context.getString(R.string.route_editor_try_catches), text(root, R.id.try_result))
            instrumentation.runOnMainSync { root.findViewById<View>(R.id.action_apply).performClick() }
            settle()
        }
        val made = ProfileDatabase.rulesDao.allRules().single { it.name == names[2] }
        assertEquals("example.net", made.domains)
        assertEquals(-2L, made.outbound)
        assertTrue(made.enabled)
    }

    @Test
    fun aRuleShownToReadHasNoWayToChangeOrSaveIt() {
        val before = RoutingProfiles.list().map { it.id }.toSet()
        val rule = RuleEntity(name = names[5], enabled = true, domains = "domain:example.net\ndomain:example.org", packages = setOf(context.packageName), outbound = -2)
        val rules = JSONArray().put(BackupFormatV2.encodeRule(RuleEntity(name = "first", domains = "first.example"))).put(BackupFormatV2.encodeRule(rule))
        val provider = runBlocking { RoutingProfiles.store(RoutingProfiles.Profile(0L, "Provider", JSONObject().put("rules", rules), RoutingProfiles.subscriptionSource(Long.MAX_VALUE))) }
        // A set not in use gives the rule by its place in the set.
        val intent = Intent(context, RouteSettingsActivity::class.java).putExtra(RouteSettingsActivity.EXTRA_READ_ONLY, true)
            .putExtra(RouteSettingsActivity.EXTRA_RULE_SET, provider.id).putExtra(RouteSettingsActivity.EXTRA_RULE_INDEX, 1)
        try {
            ActivityScenario.launch<RouteSettingsActivity>(intent).use { scenario ->
                settle()
                lateinit var root: View
                scenario.onActivity { root = it.window.decorView }
                instrumentation.runOnMainSync {
                    val sites = root.findViewById<EditText>(R.id.sites)
                    assertEquals(rule.domains, sites.text.toString())
                    assertFalse(sites.onCheckIsTextEditor())
                    assertTrue(sites.isTextSelectable)
                    assertFalse(root.findViewById<View>(R.id.to_proxy).isClickable)
                    assertFalse(root.findViewById<View>(R.id.apps).isClickable)
                    // Every app by name, as there is no list to open.
                    assertEquals(context.packageManager.getApplicationLabel(context.applicationInfo).toString(), text(root, R.id.apps_summary))
                    assertEquals(null, root.findViewById<View>(R.id.action_apply))
                    root.findViewById<EditText>(R.id.try_probe).setText("www.example.net")
                }
                settle()
                assertEquals(context.getString(R.string.route_editor_try_catches), text(root, R.id.try_result))
            }
        } finally {
            RoutingProfiles.list().filter { it.id !in before }.forEach { RoutingProfiles.delete(it.id) }
        }
        assertFalse(ProfileDatabase.rulesDao.allRules().any { it.name == names[5] })
    }

    @Test
    fun aRuleThatUsesAFoldedFieldOpensWithItShown() {
        val rule = ProfileDatabase.rulesDao.allRules().single { it.name == names[4] }
        val intent = Intent(context, RouteSettingsActivity::class.java).putExtra(RouteSettingsActivity.EXTRA_ROUTE_ID, rule.id)
        ActivityScenario.launch<RouteSettingsActivity>(intent).use { scenario ->
            settle()
            scenario.onActivity {
                assertEquals(View.VISIBLE, it.findViewById<View>(R.id.more).visibility)
                assertEquals("tls", it.findViewById<EditText>(R.id.protocols).text.toString())
            }
        }
    }

    private fun ask(root: View, text: String) {
        instrumentation.runOnMainSync { root.findViewById<EditText>(R.id.probe).setText(text) }
        settle()
    }

    private fun text(root: View, id: Int): String {
        var text = ""
        instrumentation.runOnMainSync { text = root.findViewById<TextView>(id).text.toString() }
        return text
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        Thread.sleep(1500)
    }
}
