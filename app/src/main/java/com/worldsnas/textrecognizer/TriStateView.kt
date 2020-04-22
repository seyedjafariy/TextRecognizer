package com.worldsnas.textrecognizer

import android.content.Context
import android.os.Parcelable
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatImageView
import kotlinx.android.parcel.Parcelize

typealias TriStateListener = (View, currentState: Int, states: Triple<Int, Int, Int>) -> Unit

class TriStateView @JvmOverloads constructor(
    context: Context,
    attr: AttributeSet? = null,
    defStyleAtr: Int = 0
) : AppCompatImageView(context, attr, defStyleAtr) {


    var states = Triple(0, 0, 0)
        set(value) {
            field = value
            if (currentState == 0) {
                nextState()
            } else {
                renderState(currentState)
            }
        }
    private var currentState: Int = 0

    var clickListener: TriStateListener? = null

    init {

        setOnClickListener {
            if (currentState == 0) {
                return@setOnClickListener
            }

            nextState()

            clickListener?.invoke(it, currentState, states)
        }
    }

    override fun setOnClickListener(l: OnClickListener?) {
        super.setOnClickListener(l)
    }

    fun nextState() {
        val newState = when (currentState) {
            0 -> states.first
            states.first -> states.second
            states.second -> states.third
            states.third -> states.first
            else ->
                throw IllegalStateException("current state can not be different")
        }
        renderState(newState)
    }

    private fun renderState(state: Int) {
        setImageResource(state)
        currentState = state
    }

    override fun onSaveInstanceState(): Parcelable? {
        return TriState(
            super.onSaveInstanceState(),
            states.first,
            states.second,
            states.third,
            currentState
        )
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is TriState) {
            super.onRestoreInstanceState(state)

        }

        state as TriState

        super.onRestoreInstanceState(state.parentState)

        currentState = state.currentState
        states = Triple(
            state.firstState,
            state.secondState,
            state.thirdState
        )
    }

    @Parcelize
    data class TriState(
        val parentState: Parcelable?,
        val firstState: Int,
        val secondState: Int,
        val thirdState: Int,
        val currentState: Int
    ) : Parcelable
}