package com.dan.timelapse.screens

import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.dan.timelapse.MainActivity
import com.dan.timelapse.MainViewModel
import com.dan.timelapse.utils.Settings

open class AppFragment : Fragment() {

    val mainActivity: MainActivity get() = activity as MainActivity
    val settings: Settings by lazy { Settings(requireActivity()) }
    val model: MainViewModel by activityViewModels()

    private var currentToast: Toast? = null

    fun runOnUiThread(action: ()->Unit) {
        activity?.runOnUiThread(action)
    }

    open fun onBack(homeButton: Boolean) {
    }

    fun showToast(message: String) {
        activity?.runOnUiThread {
            currentToast?.cancel()
            currentToast = Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT)
            currentToast?.show()
        }
    }
}