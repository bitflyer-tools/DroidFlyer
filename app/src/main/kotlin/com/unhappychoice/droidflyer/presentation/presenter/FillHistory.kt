package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.PrivateExecution
import io.reactivex.Single
import io.reactivex.disposables.SerialDisposable
import retrofit2.HttpException
import java.util.concurrent.ConcurrentSkipListMap

internal class FillHistory(
    private val fetch: (Int, Long?) -> Single<List<PrivateExecution>>
) {
    data class State(
        val fills: List<PrivateExecution> = emptyList(),
        val nextCursor: Long? = null,
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val error: Error? = null,
        val update: Update = Update.Replace(emptyList())
    )

    enum class Error { ACCESS_DENIED, REQUEST_FAILED }

    // A fresh instance marks a genuine change in fills; views can detect it with a reference check
    // instead of diffing the retained entries, since copies that only flip loading/error reuse it.
    sealed class Update {
        data class Replace(val fills: List<PrivateExecution>) : Update()
        data class Append(val older: List<PrivateExecution>) : Update()
    }

    val state = Variable(State())

    fun refresh() = load(null)

    fun loadOlder() {
        state.value.nextCursor?.let(::load)
    }

    fun clear() {
        request.set(null)
        pages = Pages()
        state.value = State()
    }

    private val request = SerialDisposable()
    private var pages = Pages()

    private fun load(before: Long?) {
        val previous = state.value
        if (previous.loading) return
        state.value = previous.copy(loading = true, error = null)
        request.set(fetch(PAGE_SIZE, before).subscribe({ page ->
            acceptPage(page, before)
        }, { error ->
            val status = (error as? HttpException)?.code()
            state.value = if (status == 401 || status == 403) {
                pages = Pages()
                State(error = Error.ACCESS_DENIED)
            } else {
                previous.copy(error = Error.REQUEST_FAILED)
            }
        }))
    }

    private fun acceptPage(page: List<PrivateExecution>, before: Long?) {
        val accepted = page.filter { before == null || it.id < before }
            .distinctBy { it.id }.sortedByDescending { it.id }
        if (before == null) pages = Pages()
        val fills = pages.append(accepted)
        state.value = State(
            fills = fills,
            nextCursor = accepted.lastOrNull()?.id?.takeIf { page.size >= PAGE_SIZE },
            loaded = true,
            update = if (before == null) Update.Replace(fills) else Update.Append(accepted)
        )
    }

    private class Pages {
        private val byOffset = ConcurrentSkipListMap<Int, List<PrivateExecution>>()
        private var snapshot: List<PrivateExecution> = emptyList()

        fun append(page: List<PrivateExecution>): List<PrivateExecution> {
            if (page.isEmpty()) return snapshot
            val offset = snapshot.size
            byOffset[offset] = page
            // Backed by the page map so appending stays O(1) instead of copying the whole history.
            snapshot = object : AbstractList<PrivateExecution>() {
                override val size = offset + page.size

                override fun get(index: Int): PrivateExecution {
                    if (index !in indices) throw IndexOutOfBoundsException("Index: $index, size: $size")
                    val entry = checkNotNull(byOffset.floorEntry(index))
                    return entry.value[index - entry.key]
                }
            }
            return snapshot
        }
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}
