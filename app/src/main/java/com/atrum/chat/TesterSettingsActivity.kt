package com.atrum.chat

import android.os.Bundle
import android.view.View
import com.atrum.chat.databinding.ActivityTesterSettingsBinding

class TesterSettingsActivity : SecureActivity() {

    private lateinit var binding: ActivityTesterSettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTesterSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.btnBack.setOnClickListener { finish() }

        binding.switchScreenshots.isChecked = prefs.isScreenshotsAllowed
        binding.itemScreenshots.setOnClickListener {
            val newVal = !binding.switchScreenshots.isChecked
            binding.switchScreenshots.isChecked = newVal
            prefs.isScreenshotsAllowed = newVal
            
            // To apply changes immediately to current window, 
            // though most apps require restart or apply to next activities
            if (newVal) {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        binding.itemCrash.setOnClickListener {
            showCrashConfirmDialog()
        }

        binding.itemGroupProfileDemo.setOnClickListener {
            startActivity(
                android.content.Intent(this, PartnerProfileActivity::class.java)
                    .putExtra(PartnerProfileActivity.EXTRA_DEMO_GROUP, true)
            )
        }

        binding.itemBlockPreview.setOnClickListener {
            UpdateRequiredActivity.launch(this)
        }

        binding.itemScreenshots.visibility = View.VISIBLE
        binding.itemCrash.visibility = View.VISIBLE
        binding.itemGroupProfileDemo.visibility = View.VISIBLE
        binding.itemBlockPreview.visibility = View.VISIBLE
    }

    private fun showCrashConfirmDialog() {
        val dialog = android.app.Dialog(this, R.style.Theme_AtrumChat_Dialog)
        val dialogBinding = com.atrum.chat.databinding.DialogGenericConfirmBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        dialogBinding.tvTitle.text = getString(R.string.tester_crash_confirm_title)
        dialogBinding.tvMessage.text = getString(R.string.tester_crash_confirm_msg)
        dialogBinding.btnConfirm.text = getString(R.string.tester_crash_btn)
        dialogBinding.btnConfirm.setTextColor(getColor(R.color.error))

        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnConfirm.setOnClickListener {
            throw RuntimeException("Manual tester crash")
        }

        dialog.show()
    }
}
