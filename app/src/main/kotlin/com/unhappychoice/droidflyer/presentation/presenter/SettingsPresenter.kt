package com.unhappychoice.droidflyer.presentation.presenter

import android.view.View
import com.google.android.material.snackbar.Snackbar
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import com.unhappychoice.droidflyer.infrastructure.preference.CryptoCfdAccountPreference
import com.unhappychoice.droidflyer.infrastructure.preference.StopSubmissionPreference
import com.unhappychoice.droidflyer.presentation.view.SettingsView
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.subjects.CompletableSubject
import mortar.ViewPresenter
import retrofit2.HttpException

class SettingsPresenter internal constructor(
    private val api: APIClient,
    private val preference: APITokenPreference,
    private val switchAccount: (APIClientV1?, APICredentials) -> Unit,
    internal val accountPreference: CryptoCfdAccountPreference,
    private val updateRealtimeAccount: (() -> Unit) -> Unit
) : ViewPresenter<SettingsView>() {
    internal constructor(
        api: APIClient,
        preference: APITokenPreference,
        currentStatusService: CurrentStatusService,
        realtimeClient: RealtimeClient,
        accountPreference: CryptoCfdAccountPreference,
        stopSubmissions: StopSubmissionPreference
    ) : this(api, preference, { client, credentials ->
        currentStatusService.switchAccount(client, stopSubmissions.forApiKey(credentials.key))
    }, accountPreference, realtimeClient::updateAccount)

    val key: Variable<String> = Variable("")
    val secret: Variable<String> = Variable("")
    internal val accountUnlinked = preference.accountUnlinked
    private val bag = CompositeDisposable()
    private var pendingSave: CompletableSubject? = null

    fun loadCredentials(onLoaded: () -> Unit = {}): Disposable =
        (pendingSave ?: Completable.complete())
            .andThen(Observable.fromCallable(preference::read).subscribeOnIoObserveOnUI())
            .subscribeNext { credentials ->
                key.value = credentials.key
                secret.value = credentials.secret
                onLoaded()
            }

    override fun onExitScope() {
        bag.dispose()
        super.onExitScope()
    }

    fun save() {
        if (pendingSave != null) return
        bag.clear()
        val snackbarAnchor = view.rootView.findViewById<View>(android.R.id.content) ?: view
        val completion = CompletableSubject.create()
        pendingSave = completion
        saveCredentials()
            .doFinally {
                pendingSave = null
                completion.onComplete()
            }
            .subscribe(
                { Snackbar.make(snackbarAnchor, R.string.api_key_saved, Snackbar.LENGTH_SHORT).show() },
                { error ->
                    Snackbar.make(snackbarAnchor, credentialSaveErrorMessage(error), Snackbar.LENGTH_LONG).show()
                }
            )
            .addTo(bag)
    }

    fun unlink(): Boolean {
        bag.clear()
        val cleared = preference.clear()
        loadCredentials()
        return cleared
    }

    internal fun saveCredentials(): Observable<List<Map<String, Any>>> {
        val submitted = APICredentials(key.value, secret.value)
        val generation = preference.unlinkGeneration
        return validateCredentials(submitted)
            .filter { generation == preference.unlinkGeneration }
            .map { balance ->
                var changed = false
                updateRealtimeAccount {
                    changed = preference.saveIfChanged(submitted, generation)
                }
                balance to changed
            }
            .subscribeOnIoObserveOnUI()
            .filter { generation == preference.unlinkGeneration }
            .map { (balance, changed) ->
                if (changed) switchAccount(api.privateStatusClient(submitted), submitted)
                balance
            }
    }

    private fun validateCredentials(credentials: APICredentials): Observable<List<Map<String, Any>>> =
        api.client(credentials).getBalance().onErrorResumeNext { error: Throwable ->
            Observable.error(CredentialValidationFailure(error))
        }
}

internal fun credentialSaveErrorMessage(error: Throwable): Int = when {
    error !is CredentialValidationFailure -> R.string.api_key_not_saved
    (error.cause as? HttpException)?.code() in listOf(401, 403) -> R.string.api_key_rejected
    else -> R.string.api_key_validation_failed
}

private class CredentialValidationFailure(cause: Throwable) : RuntimeException(cause)
