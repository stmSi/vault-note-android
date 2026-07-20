package com.vaultnote.app

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.commit
import com.vaultnote.R
import com.vaultnote.databinding.ActivityMainBinding
import com.vaultnote.feature.editor.NoteEditorFragment
import com.vaultnote.feature.vault.VaultFragment

class MainActivity : AppCompatActivity(), MainNavigator {
    private lateinit var binding: ActivityMainBinding

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
    }

    override fun openNoteEditor(itemId: String) {
        if (supportFragmentManager.isStateSaved) return
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragment_container, NoteEditorFragment.newInstance(itemId))
            addToBackStack(NoteEditorFragment.BACK_STACK_NAME)
        }
    }

    override fun navigateBack() {
        if (!supportFragmentManager.isStateSaved) {
            supportFragmentManager.popBackStack()
        }
    }
}
