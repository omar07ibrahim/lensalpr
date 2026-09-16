package com.lensalpr.app.lock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lensalpr.app.R
import com.lensalpr.app.ScanActivity
import com.lensalpr.app.SetupActivity
import com.lensalpr.app.databinding.ActivityLockBinding

/**
 * The door. Every fresh process passes through here before either real screen is shown.
 *
 * Two jobs: on a phone with no password yet, ask for one (twice, twelve digits); otherwise ask
 * for it and count the misses. The third miss erases everything the app has ever collected and
 * puts the phone back to "no password yet" — see [PanicWipe]. Only the scanner's own restart
 * alarm is allowed past without a human, through [LockStore.consumeAutoUnlock].
 */
class LockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockBinding
    private var setupMode = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (LockStore.unlocked) {
            proceed()
            return
        }
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setupMode = !LockStore.hasPassword(this)
        binding.btnSubmit.setOnClickListener { submit() }
        // The keyboard's Done/Next button arrives as an action id; a hardware or injected Enter
        // arrives as IME_NULL with the key event, so both are handled here.
        binding.editPassword.setOnEditorActionListener { _, actionId, event ->
            when {
                actionId == EditorInfo.IME_ACTION_NEXT || (isEnter(actionId, event) && setupMode) -> {
                    binding.editConfirm.requestFocus()
                    true
                }
                actionId == EditorInfo.IME_ACTION_DONE || isEnter(actionId, event) -> {
                    submit()
                    true
                }
                else -> false
            }
        }
        binding.editConfirm.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE || isEnter(actionId, event)) {
                submit()
                true
            } else {
                false
            }
        }
        render()
        binding.editPassword.requestFocus()
    }

    private fun isEnter(actionId: Int, event: KeyEvent?): Boolean =
        actionId == EditorInfo.IME_NULL && event?.keyCode == KeyEvent.KEYCODE_ENTER &&
            event.action == KeyEvent.ACTION_DOWN

    private fun render() {
        binding.lockError.text = ""
        if (setupMode) {
            binding.lockTitle.text = getString(R.string.lock_setup_title)
            binding.lockHint.text = getString(R.string.lock_setup_hint)
            binding.editPassword.imeOptions = EditorInfo.IME_ACTION_NEXT
            binding.editConfirm.visibility = View.VISIBLE
            binding.lockAttempts.visibility = View.GONE
            binding.btnSubmit.text = getString(R.string.lock_save)
            return
        }
        binding.lockTitle.text = getString(R.string.lock_title)
        binding.lockHint.text = getString(R.string.lock_hint)
        binding.editPassword.imeOptions = EditorInfo.IME_ACTION_DONE
        binding.editConfirm.visibility = View.GONE
        binding.btnSubmit.text = getString(R.string.lock_unlock)
        val left = LockStore.attemptsLeft(this)
        binding.lockAttempts.visibility = View.VISIBLE
        binding.lockAttempts.text = if (left <= 1) {
            getString(R.string.lock_attempts_last)
        } else {
            getString(R.string.lock_attempts, left)
        }
        binding.lockAttempts.setTextColor(
            ContextCompat.getColor(this, if (left <= 1) R.color.danger else R.color.warn),
        )
    }

    private fun submit() {
        if (busy) return
        val password = binding.editPassword.text?.toString().orEmpty()
        if (!LockCrypto.isValid(password)) {
            binding.lockError.text = getString(R.string.lock_format)
            return
        }
        if (setupMode) {
            val confirm = binding.editConfirm.text?.toString().orEmpty()
            if (confirm != password) {
                binding.lockError.text = getString(R.string.lock_mismatch)
                binding.editConfirm.text?.clear()
                return
            }
            LockStore.setPassword(this, password)
            LockStore.unlocked = true
            proceed()
            return
        }
        // Deriving the key costs tens of milliseconds by design; off the main thread so the
        // screen does not freeze on the tap.
        busy = true
        binding.btnSubmit.isEnabled = false
        val app = applicationContext
        Thread {
            val ok = LockStore.verify(app, password)
            val left = if (ok) LockPolicy.MAX_ATTEMPTS else LockStore.noteFailure(app)
            runOnUiThread { onVerified(ok, left) }
        }.start()
    }

    private fun onVerified(ok: Boolean, attemptsLeft: Int) {
        if (isFinishing || isDestroyed) return
        busy = false
        binding.btnSubmit.isEnabled = true
        if (ok) {
            LockStore.unlocked = true
            proceed()
            return
        }
        binding.editPassword.text?.clear()
        if (attemptsLeft > 0) {
            render()
            binding.lockError.text = getString(R.string.lock_wrong, attemptsLeft)
            return
        }
        wipeAndReset()
    }

    /** The third miss: everything goes, and the screen turns into the first-run one. */
    private fun wipeAndReset() {
        busy = true
        binding.btnSubmit.isEnabled = false
        binding.lockAttempts.text = getString(R.string.lock_wiping)
        val app = applicationContext
        Thread {
            PanicWipe.run(app)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy = false
                binding.btnSubmit.isEnabled = true
                setupMode = true
                LockStore.unlocked = false
                binding.editPassword.text?.clear()
                binding.editConfirm.text?.clear()
                render()
                binding.lockError.text = getString(R.string.lock_wiped)
                Toast.makeText(this, R.string.lock_wiped, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun proceed() {
        val target = when (intent.getStringExtra(EXTRA_TARGET)) {
            TARGET_SCAN -> ScanActivity::class.java
            else -> SetupActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }

    companion object {
        const val EXTRA_TARGET = "target"
        const val TARGET_SETUP = "setup"
        const val TARGET_SCAN = "scan"

        fun intent(context: Context, target: String): Intent =
            Intent(context, LockActivity::class.java).putExtra(EXTRA_TARGET, target)
    }
}
