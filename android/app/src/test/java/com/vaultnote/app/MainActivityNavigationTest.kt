package com.vaultnote.app

import com.vaultnote.R
import com.vaultnote.core.security.LockPolicy
import com.vaultnote.feature.conflicts.ConflictsFragment
import com.vaultnote.feature.sync.SyncStatusFragment
import com.vaultnote.feature.files.FilesFragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.vaultnote.feature.vault.VaultFragment
import com.vaultnote.feature.vault.VaultSection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityNavigationTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller.get()
        activity.appContainer().lockManager.applyPolicy(LockPolicy.DEFAULT)
        activity.supportFragmentManager.executePendingTransactions()
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
    }

    @Test
    fun `sync and conflicts destinations enter the back stack without crashing`() {
        activity.openSyncStatus()
        activity.supportFragmentManager.executePendingTransactions()
        assertTrue(currentFragment() is SyncStatusFragment)
        assertEquals(1, activity.supportFragmentManager.backStackEntryCount)

        activity.navigateBack()
        activity.supportFragmentManager.executePendingTransactions()
        activity.openConflicts()
        activity.supportFragmentManager.executePendingTransactions()
        assertTrue(currentFragment() is ConflictsFragment)
        assertEquals(1, activity.supportFragmentManager.backStackEntryCount)
    }

    @Test
    fun `archived item can open an editor without an illegal transaction`() {
        activity.showArchivedForTest()
        activity.openNoteEditor("00000000-0000-0000-0000-000000000001")
        activity.supportFragmentManager.executePendingTransactions()

        assertEquals(1, activity.supportFragmentManager.backStackEntryCount)
    }

    @Test
    fun `files is primary and back returns to notes`() {
        activity.findViewById<BottomNavigationView>(R.id.primary_navigation).selectedItemId =
            R.id.navigation_files
        activity.supportFragmentManager.executePendingTransactions()

        assertTrue(currentFragment() is FilesFragment)
        assertEquals(0, activity.supportFragmentManager.backStackEntryCount)

        activity.onBackPressedDispatcher.onBackPressed()
        activity.supportFragmentManager.executePendingTransactions()

        val vault = currentFragment() as VaultFragment
        assertEquals(VaultSection.ACTIVE, vault.currentSection())
    }

    @Test
    fun `short secure external handoff does not trigger immediate lock`() {
        val lockManager = activity.appContainer().lockManager
        lockManager.applyPolicy(LockPolicy(true, 0L, true))
        lockManager.unlock()

        assertTrue(activity.beginSecureExternalHandoff())
        controller.pause().stop()
        assertTrue(lockManager.isContentAccessAllowed())

        controller.start().resume()
        activity.endSecureExternalHandoff()
        assertTrue(lockManager.isContentAccessAllowed())
    }

    @Test
    fun `locked vault cannot begin an external handoff`() {
        val lockManager = activity.appContainer().lockManager
        lockManager.applyPolicy(LockPolicy(true, 0L, true))
        lockManager.lockNow()

        assertFalse(activity.beginSecureExternalHandoff())
    }

    private fun currentFragment() =
        activity.supportFragmentManager.findFragmentById(R.id.fragment_container)

    private fun MainActivity.showArchivedForTest() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, VaultFragment.newInstance(VaultSection.ARCHIVED))
            .commitNow()
    }
}
