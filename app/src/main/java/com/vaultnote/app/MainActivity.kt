package com.vaultnote.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.commit
import com.google.android.material.snackbar.Snackbar
import com.vaultnote.R
import com.vaultnote.databinding.ActivityMainBinding
import com.vaultnote.feature.editor.NoteEditorFragment
import com.vaultnote.feature.importing.ImportPreviewFragment
import com.vaultnote.feature.importing.IncomingImport
import com.vaultnote.feature.importing.IncomingImportCoordinator
import com.vaultnote.feature.importing.IncomingImportParseResult
import com.vaultnote.feature.importing.IncomingImportParser
import com.vaultnote.feature.viewer.AttachmentViewerFragment
import com.vaultnote.feature.vault.VaultFragment

class MainActivity : AppCompatActivity(), MainNavigator {
    private lateinit var binding: ActivityMainBinding
    private val incomingImports: IncomingImportCoordinator by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                replace(R.id.fragment_container, VaultFragment.newInstance())
            }
        }
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
        binding.root.post { consumeIncomingIntent(intent) }
    }

    override fun openNoteEditor(itemId: String) {
        if (supportFragmentManager.isStateSaved) return
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
        if (supportFragmentManager.isStateSaved) return false
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
        if (supportFragmentManager.isStateSaved) return
        supportFragmentManager.popBackStackImmediate()
        if (createdItem) openNoteEditor(itemId)
    }

    override fun openAttachment(attachmentId: String) {
        if (supportFragmentManager.isStateSaved) return
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragment_container, AttachmentViewerFragment.newInstance(attachmentId))
            addToBackStack(AttachmentViewerFragment.BACK_STACK_NAME)
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
                openImportPreview(parentItemId = null, incomingImport = parsed.incomingImport)
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
}
