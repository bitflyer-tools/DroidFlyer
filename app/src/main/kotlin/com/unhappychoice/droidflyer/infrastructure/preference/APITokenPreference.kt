package com.unhappychoice.droidflyer.infrastructure.preference

import android.content.Context
import jp.takuji31.koreference.KoreferenceModel
import jp.takuji31.koreference.stringPreference

class APITokenPreference(context: Context) : KoreferenceModel(context, name = "api_token") {
    var secret: String by stringPreference("")
    var key: String by stringPreference("")
}