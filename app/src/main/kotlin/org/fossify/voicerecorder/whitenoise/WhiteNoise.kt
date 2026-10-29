package org.fossify.voicerecorder.whitenoise

import android.content.Context
import java.io.File

/** Entry point the pipeline uses to send; the MarmotKit runtime is started on demand. */
object WhiteNoise {
    suspend fun sendVoice(context: Context, groupId: String, file: File, fileName: String, mime: String, caption: String?) {
        error("White Noise is not available yet")
    }

    suspend fun sendText(context: Context, groupId: String, text: String) {
        error("White Noise is not available yet")
    }
}
