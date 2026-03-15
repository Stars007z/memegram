package com.example.memegram

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import kotlin.math.roundToInt
import androidx.core.graphics.toColorInt

class ColorPickerDialog(
    private val initialColor: Int,
    private val onColorPicked: (Int) -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireContext())
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.dialog_color_picker, null)

        val preview = view.findViewById<View>(R.id.viewPreviewColor)
        val sbRed = view.findViewById<SeekBar>(R.id.sbRed)
        val sbGreen = view.findViewById<SeekBar>(R.id.sbGreen)
        val sbBlue = view.findViewById<SeekBar>(R.id.sbBlue)
        val etHex = view.findViewById<EditText>(R.id.etHex)
        val btnDone = view.findViewById<Button>(R.id.btnDone)

        var currentColor = initialColor

        fun updateColor(color: Int, updateHex: Boolean = true) {
            currentColor = color
            preview.setBackgroundColor(color)
            if (updateHex) {
                etHex.setText(String.format("%06X", (0xFFFFFF and color)))
            }
        }

        fun updateFromSeekBars() {
            val r = sbRed.progress
            val g = sbGreen.progress
            val b = sbBlue.progress
            val color = Color.rgb(r, g, b)
            updateColor(color)
        }

        sbRed.progress = Color.red(initialColor)
        sbGreen.progress = Color.green(initialColor)
        sbBlue.progress = Color.blue(initialColor)
        updateColor(initialColor)

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) updateFromSeekBars()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        sbRed.setOnSeekBarChangeListener(listener)
        sbGreen.setOnSeekBarChangeListener(listener)
        sbBlue.setOnSeekBarChangeListener(listener)

        etHex.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val hex = s.toString()
                if (hex.length == 6) {
                    try {
                        val color = "#$hex".toColorInt()
                        sbRed.progress = Color.red(color)
                        sbGreen.progress = Color.green(color)
                        sbBlue.progress = Color.blue(color)
                        updateColor(color, false)
                    } catch (e: Exception) {}
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnDone.setOnClickListener {
            onColorPicked(currentColor)
            dismiss()
        }

        builder.setView(view)
        return builder.create()
    }
}
