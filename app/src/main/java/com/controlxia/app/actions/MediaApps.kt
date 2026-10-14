package com.controlxia.app.actions

/** Apps conocidas por tipo, para decidir qué gesto tiene sentido en cada una. */
object MediaApps {

    /** Apps de videos (reels, shorts, series): ahí tiene sentido pausar o pasar de video con la mano. */
    val VIDEO = setOf(
        "com.instagram.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "com.google.android.youtube", "com.facebook.katana", "com.netflix.mediaclient",
        "com.snapchat.android",
    )
}
