package com.atrum.chat

import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.atrum.chat.databinding.ActivityChangePinBinding

class ChangePinActivity : SecureActivity() {

    private lateinit var binding: ActivityChangePinBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChangePinBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        if (!prefs.hasLocalPassword()) {
            showNewPinStep()
        } else {
            showOldPinStep()
        }

        binding.btnNextToNew.setOnClickListener { verifyOldPin() }
        binding.btnSavePin.setOnClickListener { saveNewPin() }
        binding.btnCancel.setOnClickListener { finish() }
    }

    private fun showOldPinStep() {
        binding.stepOldPin.visibility = View.VISIBLE
        binding.stepNewPin.visibility = View.GONE
        binding.etOldPin.requestFocus()
    }

    private fun showNewPinStep() {
        binding.stepOldPin.visibility = View.GONE
        binding.stepNewPin.visibility = View.VISIBLE
        binding.etNewPin.requestFocus()
    }

    private fun verifyOldPin() {
        val oldPin = binding.etOldPin.text.toString()
        if (prefs.checkLocalPassword(oldPin)) {
            showNewPinStep()
        } else {
            Toast.makeText(this, R.string.error_old_pin_wrong, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveNewPin() {
        val newPin = binding.etNewPin.text.toString()
        val repeat = binding.etNewPinRepeat.text.toString()

        if (newPin.length < 4) {
            Toast.makeText(this, R.string.error_pwd_short, Toast.LENGTH_SHORT).show()
            return
        }
        if (newPin != repeat) {
            Toast.makeText(this, R.string.error_pwd_mismatch, Toast.LENGTH_SHORT).show()
            return
        }

        prefs.setLocalPassword(newPin)
        Toast.makeText(this, R.string.settings_pin_changed, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onBackPressed() {
        if (binding.stepNewPin.visibility == View.VISIBLE && prefs.hasLocalPassword()) {
            showOldPinStep()
        } else {
            super.onBackPressed()
        }
    }
}
