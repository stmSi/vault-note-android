package com.vaultnote.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
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

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
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
        if (!canNavigate()) return false
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
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragment_container, AttachmentViewerFragment.newInstance(attachmentId))
            addToBackStack(AttachmentViewerFragment.BACK_STACK_NAME)
        }
    }

    override fun openSecuritySettings() {
        if (!canNavigate()) return
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragment_container, SecuritySettingsFragment.newInstance())
            addToBackStack(SecuritySettingsFragment.BACK_STACK_NAME)
        }
    }

    override fun openSearch() {
        if (!canNavigate()) return
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragment_container, SearchFragment.newInstance())
            addToBackStack(SearchFragment.BACK_STACK_NAME)
        }
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
            binding.root.doOnPreDraw { root ->
                root.post {
                    if (appContainer().lockManager.isContentAccessAllowed()) {
                        startLegacyEncryptionMigration()
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
            }
        }
        incomingImports.takeDeferred()?.let { deferred ->
            openImportPreview(parentItemId = null, incomingImport = deferred)
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

    private fun canNavigate(): Boolean =
        appContainer().lockManager.isContentAccessAllowed() &&
            !supportFragmentManager.isStateSaved

    private companion object {
        const val SECURITY_MIGRATION_BATCH = 8
        const val OCR_BATCH = 2
    }
}
