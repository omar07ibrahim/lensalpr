package com.lensalpr.app.lock

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.lensalpr.app.BotService
import com.lensalpr.app.R
import com.lensalpr.app.ScanSessionService
import com.lensalpr.app.SetupActivity
import com.lensalpr.app.databinding.ActivityLockBinding

/**
 * The door. Nothing in this app is reachable without coming through here first.
 *
 * Three states: set a code on first launch, ask for it on every later one, and — once the three
 * attempts are gone — a blank white sheet that no amount of typing will move. That last state is
 * the whole point of the screen: a phone taken off a windscreen should look like a dead phone, and
 * the only thing that opens it is a message to the bot from somewhere else entirely.
 */
class LockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockBinding
    private val main = Handler(Looper.getMainLooper())

    /** First half of a new code, held until it is typed a second time. */
    private var pendingNew: String? = null

    /** Polls for a remote unlock while the white sheet is up. */
    private val watchRemote = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            if (!LockStore.isLockedOut(this@LockActivity)) {
                // The bot opened it from elsewhere. Straight in: whoever sent that message is the
                // owner, and making them type the code as well would be theatre.
                proceed()
                return
            }
            main.postDelayed(this, REMOTE_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The bot has to be listening before anything else: on a locked-out phone it is the only
        // way back in, and it lives in its own service precisely so this screen cannot stop it.
        BotService.start(this)

        // The scanner restarting itself has nobody to type a code, so it carries a one-use token.
        if (LockStore.consumeAutoUnlock(this)) {
            proceed()
            return
        }
        if (LockStore.unlocked) {
            proceed()
            return
        }

        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Nothing here should ever end up in a screenshot or the recents thumbnail.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        refuseBack()

        if (LockStore.isLockedOut(this)) {
            showBlank()
            return
        }

        val creating = !LockStore.hasCode(this)
        binding.lockTitle.text = getString(
            if (creating) R.string.lock_title_create else R.string.lock_title_enter,
        )
        binding.lockSubmit.text = getString(R.string.lock_submit)
        renderHint(creating)

        binding.lockSubmit.setOnClickListener { submit(creating) }
        binding.lockInput.setOnEditorActionListener { _, actionId, _ ->
            // A hardware or on-screen Enter must do what the button does; reaching for the button
            // with one hand on the wheel is exactly the situation this screen appears in.
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit(creating)
                true
            } else {
                false
            }
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(watchRemote)
        super.onDestroy()
    }

    /**
     * The back gesture must not be a way past the door.
     *
     * Through the dispatcher rather than `onBackPressed`, which the platform stopped calling for
     * gesture navigation — the override compiled, looked right, and simply never ran on a modern
     * phone. Backing out sends the task away instead of finishing, so the lock screen is still
     * there when the app is opened again.
     */
    private fun refuseBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
    }

    private fun renderHint(creating: Boolean) {
        binding.lockHint.text = if (creating) {
            getString(R.string.lock_hint_create, LockPolicy.CODE_LENGTH)
        } else {
            getString(R.string.lock_hint_attempts, LockStore.attemptsLeft(this))
        }
    }

    private fun submit(creating: Boolean) {
        val code = binding.lockInput.text.toString().trim()
        binding.lockInput.setText("")
        if (!LockPolicy.isWellFormed(code)) {
            showError(getString(R.string.lock_error_format, LockPolicy.CODE_LENGTH))
            return
        }
        if (creating) {
            submitNew(code)
        } else {
            submitExisting(code)
        }
    }

    private fun submitNew(code: String) {
        val first = pendingNew
        if (first == null) {
            pendingNew = code
            binding.lockError.visibility = View.GONE
            binding.lockTitle.text = getString(R.string.lock_title_repeat)
            binding.lockHint.text = getString(R.string.lock_hint_repeat)
            return
        }
        if (first != code) {
            pendingNew = null
            binding.lockTitle.text = getString(R.string.lock_title_create)
            renderHint(creating = true)
            showError(getString(R.string.lock_error_mismatch))
            return
        }
        LockStore.setCode(this, code)
        proceed()
    }

    private fun submitExisting(code: String) {
        if (LockStore.verifyOnDevice(this, code)) {
            proceed()
            return
        }
        val left = LockStore.noteFailure(this)
        if (left > 0) {
            renderHint(creating = false)
            showError(getString(R.string.lock_error_wrong, left))
            return
        }
        engageLockout()
    }

    /**
     * The attempts are gone.
     *
     * Everything stops — the camera is released with the session service, and the screen turns into
     * a blank sheet. The bot survives this on purpose: it is in another service, it keeps its own
     * connection, and it is now the only thing in the world that can open this phone.
     */
    private fun engageLockout() {
        ScanSessionService.stop(this)
        BotService.announceLockout(this)
        showBlank()
    }

    private fun showBlank() {
        if (!this::binding.isInitialized) {
            binding = ActivityLockBinding.inflate(layoutInflater)
            setContentView(binding.root)
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
            refuseBack()
        }
        binding.prompt.visibility = View.GONE
        binding.blank.visibility = View.VISIBLE
        binding.lockRoot.setBackgroundColor(android.graphics.Color.WHITE)
        // Nothing to type, so nothing should be asking for input.
        runCatching {
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)
        }
        main.removeCallbacks(watchRemote)
        main.postDelayed(watchRemote, REMOTE_POLL_MS)
    }

    private fun showError(message: String) {
        binding.lockError.text = message
        binding.lockError.visibility = View.VISIBLE
    }

    private fun proceed() {
        startActivity(
            Intent(this, SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    private companion object {
        /** How often the white sheet checks whether the bot has opened the phone. */
        const val REMOTE_POLL_MS = 1_000L
    }
}
