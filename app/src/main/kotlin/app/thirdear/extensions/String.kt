package app.thirdear.extensions

fun String?.isAudioMimeType(): Boolean {
    return this?.startsWith("audio") == true
}
