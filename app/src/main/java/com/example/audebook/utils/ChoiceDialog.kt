package com.example.audebook.utils

import android.content.Context
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.audebook.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * A dialog that offers a few options as cards, each with an icon, a title and a line saying what
 * it's for, so they read clearly as things to tap rather than plain text.
 */
object ChoiceDialog {

    class Choice(
        @DrawableRes val icon: Int,
        @StringRes val title: Int,
        @StringRes val description: Int,
        val onChoose: () -> Unit
    )

    fun show(context: Context, @StringRes title: Int, choices: List<Choice>) {
        val padding = context.resources.getDimensionPixelSize(R.dimen.choice_dialog_padding)
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(list)
            .setNegativeButton(R.string.cancel, null)
            .create()

        val inflater = LayoutInflater.from(context)
        for (choice in choices) {
            val card = inflater.inflate(R.layout.item_dialog_choice, list, false)
            card.findViewById<ImageView>(R.id.choice_icon).setImageResource(choice.icon)
            card.findViewById<TextView>(R.id.choice_title).setText(choice.title)
            card.findViewById<TextView>(R.id.choice_description).setText(choice.description)
            card.setOnClickListener {
                dialog.dismiss()
                choice.onChoose()
            }
            list.addView(card)
        }
        dialog.show()
    }
}
