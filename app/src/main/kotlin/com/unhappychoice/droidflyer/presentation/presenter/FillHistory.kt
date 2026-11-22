package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.PrivateExecution
import io.reactivex.Single
import io.reactivex.disposables.SerialDisposable
import retrofit2.HttpException

internal class FillHistory(
    private val fetch: (Int, Long?) -> Single<List<PrivateExecution>>
) {
    data class State(
        val fills: List<PrivateExecution> = emptyList(),
        val nextCursor: Long? = null,
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val error: Error? = null
    )

    enum class Error { ACCESS_DENIED, REQUEST_FAILED }

    val state = Variable(State())

    fun refresh() = load(null)

    fun loadOlder() {
        state.value.nextCursor?.let(::load)
    }

    fun clear() {
        request.set(null)
        state.value = State()
    }

    private val request = SerialDisposable()

    private fun load(before: Long?) {
        val previous = state.value
        if (previous.loading) return
        state.value = previous.copy(loading = true, error = null)
        request.set(fetch(PAGE_SIZE, before).subscribe({ page ->
            val older = page.filter { before == null || it.id < before }
            val existing = if (before == null) emptyList() else previous.fills
            state.value = State(
                fills = (existing + older).distinctBy { it.id }.sortedByDescending { it.id },
                nextCursor = older.minOfOrNull { it.id }?.takeIf { page.size >= PAGE_SIZE },
                loaded = true
            )
        }, { error ->
            val status = (error as? HttpException)?.code()
            state.value = if (status == 401 || status == 403) {
                State(error = Error.ACCESS_DENIED)
            } else {
                previous.copy(error = Error.REQUEST_FAILED)
            }
        }))
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}
