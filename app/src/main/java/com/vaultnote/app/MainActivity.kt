package com.vaultnote.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.vaultnote.R
import com.vaultnote.databinding.ActivityMainBinding
import com.vaultnote.feature.editor.NoteEditorFragment
import com.vaultnote.feature.lock.LockFragment
import com.vaultnote.feature.settings.SecuritySettingsFragment
import com.vaultnote.feature.search.SearchFragment
import com.vaultnote.feature.importing.ImportPreviewFragment
import com.vaultnote.feature.importing.IncomingImport
import com.vaultnote.feature.importing.IncomingImportCoordinator
import com.vaultnote.feature.importing.IncomingImportParseResult
import com.vaultnote.feature.importing.IncomingImportParser
import com.vaultnote.feature.viewer.AttachmentViewerFragment
import com.vaultnote.feature.vault.VaultFragment
import com.vaultnote.feature.vault.VaultSection
import com.vaultnote.feature.sync.SyncStatusFragment
import com.vaultnote.feature.conflicts.ConflictsFragment
import com.vaultnote.feature.backup.BackupExportFragment
import com.vaultnote.feature.backup.BackupRestoreFragment
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.security.LockPolicy
import com.vaultnote.core.security.VaultLockState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

class MainActivity : AppCompatActivity(), MainNavigator {
    private lateinit var binding: ActivityMainBinding
    private val incomingImports: IncomingImportCoordinator by viewModels()
    private var securityMigrationJob: Job? = null
    private var ocrProcessingJob: Job? = null
    private var securityMaintenanceStarted = false
    private var policyErrorShown = false
    private var isRenderingPrimaryNavigation = false
    private var backgroundSyncScheduled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configurePrimaryNavigation()
        configureBackNavigation()
        supportFragmentManager.addOnBackStackChangedListener(::updatePrimaryNavigation)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }

        if (supportFragmentManager.findFragmentById(R.id.lock_container) == null) {
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                replace(R.id.lock_container, LockFragment.newInstance())
            }
        }
        observeSecurityState()
        val restoredImport = savedInstanceState != null &&
            supportFragmentManager.findFragmentById(R.id.fragment_container) is ImportPreviewFragment
        if (restoredImport && intent.isIncomingShare()) {
            clearIncomingIntent(intent)
        } else {
            binding.root.post { consumeIncomingIntent(intent) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        binding.root.post { consumeIncomingIntent(intent) }
    }

    override fun onPostResume() {
        super.onPostResume()
        if (appContainer().lockManager.isContentAccessAllowed()) ensureVaultRootAndDeferredImport()
        binding.root.post { consumeIncomingIntent(intent) }
    }

    override fun onStart() {
        super.onStart()
        appContainer().lockManager.onForeground()
    }

    override fun onStop() {
        if (!isChangingConfigurations) appContainer().lockManager.onBackground()
        super.onStop()
    }

    override fun openNoteEditor(itemId: String) {
        if (!canNavigate()) return
        binding.primaryNavigation.isVisible = false
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragment_container, NoteEditorFragment.newInstance(itemId))
            addToBackStack(NoteEditorFragment.BACK_STACK_NAME)
        }
    }

    override fun openImportPreview(
        parentItemId: String?,
        incomingImport: IncomingImport,
        cameraCaptureId: String?,
    ): Boolean {
        if (!appContainer().lockManager.isContentAccessAllowed()) {
            incomingImports.deferUntilUnlock(
                incomingImport = incomingImport,
                parentItemId = parentItemId,
                cameraCaptureId = cameraCaptureId,
            )
            return true
        }
        if (supportFragmentManager.isStateSaved) return false
        binding.primaryNavigation.isVisible = false
        val token = incomingImports.offer(incomingImport)
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(
                R.id.fragment_container,
                ImportPreviewFragment.newInstance(token, parentItemId, cameraCaptureId),
            )
            addToBackStack(ImportPreviewFragment.BACK_STACK_NAME)
        }
        return true
    }

    override fun takePendingImport(token: Long): IncomingImport? = incomingImports.take(token)

    override fun completeImport(itemId: String, createdItem: Boolean) {
        if (!canNavigate()) return
        supportFragmentManager.popBackStackImmediate()
        if (createdItem) openNoteEditor(itemId)
        startOcrProcessing()
    }

    override fun openAttachment(attachmentId: String) {
        if (!canNavigate()) return
        binding.primaryNavigation.isVisible = false
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragment_container, AttachmentViewerFragment.newInstance(attachmentId))
            addToBackStack(AttachmentViewerFragment.BACK_STACK_NAME)
        }
    }

    override fun openSecuritySettings() {
        showPrimaryDestination(
            fragment = SecuritySettingsFragment.newInstance(),
            navigationItemId = R.id.navigation_settings,
        )
    }

    override fun openSearch() {
        showPrimaryDestination(
            fragment = SearchFragment.newInstance(),
            navigationItemId = R.id.navigation_search,
        )
    }

    override fun openSyncStatus() {
        openContextualScreen(
            fragment = SyncStatusFragment.newInstance(),
            backStackName = SyncStatusFragment.BACK_STACK_NAME,
        )
    }

    override fun openConflicts() {
        openContextualScreen(
            fragment = ConflictsFragment.newInstance(),
            backStackName = ConflictsFragment.BACK_STACK_NAME,
        )
    }

    override fun openBackupExport() {
        openContextualScreen(
            fragment = BackupExportFragment.newInstance(),
            backStackName = BackupExportFragment.BACK_STACK_NAME,
        )
    }

    override fun openBackupRestore() {
        openContextualScreen(
            fragment = BackupRestoreFragment.newInstance(),
            backStackName = BackupRestoreFragment.BACK_STACK_NAME,
        )
    }

    override fun navigateBack() {
        if (!supportFragmentManager.isStateSaved) {
            supportFragmentManager.popBackStack()
        }
    }

    private fun consumeIncomingIntent(sourceIntent: Intent) {
        if (supportFragmentManager.isStateSaved) return
        when (val parsed = IncomingImportParser.parse(sourceIntent)) {
            IncomingImportParseResult.NotAnImport -> return
            is IncomingImportParseResult.Accepted -> {
                clearIncomingIntent(sourceIntent)
                if (appContainer().lockManager.isContentAccessAllowed()) {
                    openImportPreview(parentItemId = null, incomingImport = parsed.incomingImport)
                } else {
                    incomingImports.deferUntilUnlock(parsed.incomingImport)
                }
            }
            IncomingImportParseResult.Empty -> {
                clearIncomingIntent(sourceIntent)
                showImportError(R.string.shared_content_empty)
            }
            IncomingImportParseResult.TooManyFiles -> {
                clearIncomingIntent(sourceIntent)
                showImportError(R.string.too_many_files)
            }
            IncomingImportParseResult.UnsupportedUri -> {
                clearIncomingIntent(sourceIntent)
                showImportError(R.string.unsupported_uri)
            }
            IncomingImportParseResult.TextTooLarge -> {
                clearIncomingIntent(sourceIntent)
                showImportError(R.string.shared_text_too_large)
            }
        }
    }

    private fun clearIncomingIntent(consumed: Intent) {
        consumed.replaceExtras(null)
        consumed.clipData = null
        consumed.data = null
        consumed.action = Intent.ACTION_MAIN
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
    }

    private fun showImportError(message: Int) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun Intent.isIncomingShare(): Boolean =
        action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE

    private fun observeSecurityState() {
        val container = appContainer()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    container.lockPolicyRepository.observe().collect { result ->
                        when (result) {
                            is RepositoryResult.Success -> {
                                policyErrorShown = false
                                container.lockManager.applyPolicy(result.value)
                            }
                            is RepositoryResult.Failure -> {
                                container.lockManager.applyPolicy(LockPolicy.FAIL_CLOSED)
                                if (!policyErrorShown) {
                                    policyErrorShown = true
                                    showImportError(R.string.operation_failed)
                                }
                            }
                        }
                    }
                }
                launch { container.lockManager.state.collect(::renderSecurityState) }
            }
        }
    }

    private fun renderSecurityState(state: VaultLockState) {
        val locked = !state.isPolicyLoaded || state.isLocked
        binding.lockContainer.isVisible = locked
        if (locked) binding.primaryNavigation.isVisible = false
        binding.fragmentContainer.importantForAccessibility = if (locked) {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
        applyWindowSecurity(state, locked)
        if (locked) {
            securityMigrationJob?.cancel()
            securityMigrationJob = null
            ocrProcessingJob?.cancel()
            ocrProcessingJob = null
            securityMaintenanceStarted = false
            if (::binding.isInitialized) appContainer().imageLoader.memoryCache?.clear()
        } else {
            ensureVaultRootAndDeferredImport()
            updatePrimaryNavigation()
            binding.root.doOnPreDraw { root ->
                root.post {
                    if (appContainer().lockManager.isContentAccessAllowed()) {
                        startLegacyEncryptionMigration()
                        scheduleBackgroundSync()
                    }
                }
            }
        }
    }

    private fun applyWindowSecurity(state: VaultLockState, locked: Boolean) {
        val blockScreenshots = locked || state.policy.blockScreenshots ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        if (blockScreenshots) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun ensureVaultRootAndDeferredImport() {
        if (supportFragmentManager.isStateSaved) return
        if (supportFragmentManager.findFragmentById(R.id.fragment_container) == null) {
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                replace(R.id.fragment_container, VaultFragment.newInstance())
                runOnCommit(::updatePrimaryNavigation)
            }
        }
        incomingImports.takeDeferred()?.let { deferred ->
            openImportPreview(
                parentItemId = deferred.parentItemId,
                incomingImport = deferred.incomingImport,
                cameraCaptureId = deferred.cameraCaptureId,
            )
        }
    }

    private fun startLegacyEncryptionMigration() {
        if (securityMaintenanceStarted || securityMigrationJob?.isActive == true) return
        securityMaintenanceStarted = true
        securityMigrationJob = lifecycleScope.launch {
            if (appContainer().attachmentRepository.reconcileFileCleanup() is RepositoryResult.Failure) {
                Snackbar.make(
                    binding.root,
                    R.string.attachment_security_upgrade_failed,
                    Snackbar.LENGTH_LONG,
                ).show()
            }
            if (appContainer().lockManager.isContentAccessAllowed()) startOcrProcessing()
            while (appContainer().lockManager.isContentAccessAllowed()) {
                when (
                    val result = appContainer().attachmentRepository
                        .migrateLegacyAttachments(SECURITY_MIGRATION_BATCH)
                ) {
                    is RepositoryResult.Failure -> {
                        Snackbar.make(
                            binding.root,
                            R.string.attachment_security_upgrade_failed,
                            Snackbar.LENGTH_LONG,
                        ).show()
                        break
                    }
                    is RepositoryResult.Success -> {
                        if (result.value < SECURITY_MIGRATION_BATCH) break
                        yield()
                    }
                }
            }
        }
    }

    private fun startOcrProcessing() {
        if (ocrProcessingJob?.isActive == true || !appContainer().lockManager.isContentAccessAllowed()) {
            return
        }
        ocrProcessingJob = lifecycleScope.launch {
            while (appContainer().lockManager.isContentAccessAllowed()) {
                when (val result = appContainer().ocrRepository.processPending(OCR_BATCH)) {
                    is RepositoryResult.Failure -> break
                    is RepositoryResult.Success -> {
                        if (!result.value.mayHaveMore) break
                        yield()
                    }
                }
            }
        }
    }

    private fun scheduleBackgroundSync() {
        if (backgroundSyncScheduled) return
        backgroundSyncScheduled = true
        appContainer().syncScheduler.ensurePeriodicSync()
        appContainer().syncScheduler.requestSync()
    }

    private fun canNavigate(): Boolean =
        appContainer().lockManager.isContentAccessAllowed() &&
            !supportFragmentManager.isStateSaved

    private fun configurePrimaryNavigation() {
        binding.primaryNavigation.setOnItemSelectedListener { item ->
            if (isRenderingPrimaryNavigation) return@setOnItemSelectedListener true
            when (item.itemId) {
                R.id.navigation_notes -> showVaultSection(VaultSection.ACTIVE)
                R.id.navigation_archived -> showVaultSection(VaultSection.ARCHIVED)
                R.id.navigation_trash -> showVaultSection(VaultSection.TRASH)
                R.id.navigation_search -> openSearch()
                R.id.navigation_settings -> openSecuritySettings()
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        val startPadding = binding.primaryNavigation.paddingStart
        val endPadding = binding.primaryNavigation.paddingEnd
        val bottomPadding = binding.primaryNavigation.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.primaryNavigation) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val isRtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL
            view.updatePaddingRelative(
                start = startPadding + if (isRtl) safe.right else safe.left,
                end = endPadding + if (isRtl) safe.left else safe.right,
                bottom = bottomPadding + safe.bottom,
            )
            insets
        }
    }

    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (supportFragmentManager.isStateSaved) return
                    if (!appContainer().lockManager.isContentAccessAllowed()) {
                        dispatchToSystem()
                        return
                    }
                    if (supportFragmentManager.backStackEntryCount > 0) {
                        supportFragmentManager.popBackStack()
                        return
                    }

                    val current = supportFragmentManager
                        .findFragmentById(R.id.fragment_container)
                    val isNonDefaultPrimaryDestination = when (current) {
                        is VaultFragment ->
                            VaultFragment.sectionOf(current) != VaultSection.ACTIVE
                        is SearchFragment, is SecuritySettingsFragment -> true
                        else -> false
                    }
                    if (isNonDefaultPrimaryDestination) {
                        showVaultSection(VaultSection.ACTIVE)
                    } else {
                        dispatchToSystem()
                    }
                }

                private fun dispatchToSystem() {
                    isEnabled = false
                    try {
                        onBackPressedDispatcher.onBackPressed()
                    } finally {
                        isEnabled = true
                    }
                }
            },
        )
    }

    private fun showVaultSection(section: VaultSection) {
        val itemId = when (section) {
            VaultSection.ACTIVE -> R.id.navigation_notes
            VaultSection.ARCHIVED -> R.id.navigation_archived
            VaultSection.TRASH -> R.id.navigation_trash
        }
        showPrimaryDestination(VaultFragment.newInstance(section), itemId)
    }

    private fun openContextualScreen(
        fragment: androidx.fragment.app.Fragment,
        backStackName: String,
    ) {
        if (!canNavigate()) return
        binding.primaryNavigation.isVisible = false
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragment_container, fragment)
            addToBackStack(backStackName)
        }
    }

    private fun showPrimaryDestination(
        fragment: androidx.fragment.app.Fragment,
        navigationItemId: Int,
    ) {
        if (!canNavigate()) return
        val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
        val alreadySelected = when {
            current is VaultFragment && fragment is VaultFragment ->
                VaultFragment.sectionOf(current) == VaultFragment.sectionOf(fragment)
            else -> current?.javaClass == fragment.javaClass
        }
        setPrimaryNavigationSelection(navigationItemId)
        binding.primaryNavigation.isVisible = true
        if (alreadySelected) return
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragment_container, fragment)
            runOnCommit(::updatePrimaryNavigation)
        }
    }

    private fun updatePrimaryNavigation() {
        if (!::binding.isInitialized || !appContainer().lockManager.isContentAccessAllowed()) {
            if (::binding.isInitialized) binding.primaryNavigation.isVisible = false
            return
        }
        val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
        val selectedId = when (current) {
            is VaultFragment -> when (VaultFragment.sectionOf(current)) {
                VaultSection.ACTIVE -> R.id.navigation_notes
                VaultSection.ARCHIVED -> R.id.navigation_archived
                VaultSection.TRASH -> R.id.navigation_trash
            }
            is SearchFragment -> R.id.navigation_search
            is SecuritySettingsFragment -> R.id.navigation_settings
            else -> null
        }
        binding.primaryNavigation.isVisible = selectedId != null
        selectedId?.let(::setPrimaryNavigationSelection)
    }

    private fun setPrimaryNavigationSelection(itemId: Int) {
        isRenderingPrimaryNavigation = true
        try {
            binding.primaryNavigation.menu.findItem(itemId)?.isChecked = true
        } finally {
            isRenderingPrimaryNavigation = false
        }
    }

    private companion object {
        const val SECURITY_MIGRATION_BATCH = 8
        const val OCR_BATCH = 2
    }
}
