package com.unhappychoice.droidflyer.domain.service

internal interface StopSubmissionGuard {
    fun pending(): String?
    fun reserve(id: String): Boolean
    fun release(id: String): Boolean

    companion object {
        fun inMemory(): StopSubmissionGuard = object : StopSubmissionGuard {
            private var pending: String? = null

            override fun pending() = pending

            override fun reserve(id: String): Boolean {
                if (pending != null) return false
                pending = id
                return true
            }

            override fun release(id: String): Boolean {
                if (pending != id) return false
                pending = null
                return true
            }
        }
    }
}
