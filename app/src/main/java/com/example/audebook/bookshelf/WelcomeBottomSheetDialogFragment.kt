package com.example.audebook.bookshelf

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.audebook.databinding.FragmentWelcomeBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import android.content.Context
import androidx.fragment.app.DialogFragment

class WelcomeBottomSheetDialogFragment : DialogFragment() {

    private var _binding: FragmentWelcomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWelcomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonGetStarted.setOnClickListener {
            handleDontShowAgain()
            dismiss()
        }

        binding.checkboxDontShowAgain.setOnCheckedChangeListener { _, isChecked ->
            // Store the preference when checkbox changes
            setWelcomeShown(isChecked)
        }

        binding.buttonSkip.setOnClickListener {
            handleDontShowAgain()
            dismiss()
        }
    }

    private fun handleDontShowAgain() {
        val dontShowAgain = binding.checkboxDontShowAgain.isChecked
        setWelcomeShown(dontShowAgain)
    }

    private fun setWelcomeShown(shown: Boolean) {
        val sharedPref = requireActivity().getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putBoolean("welcome_shown", shown)
            apply()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onStart() {
        super.onStart()
        // Make dialog full-width and centered
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}