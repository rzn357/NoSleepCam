package com.exciteddev.nosleepcam.ui

import android.content.Context
import com.exciteddev.nosleepcam.R
import com.exciteddev.nosleepcam.camera.CameraDescriptor
import com.exciteddev.nosleepcam.camera.CameraStatus
import com.exciteddev.nosleepcam.camera.LensFacing

/** The camera's name for the user: "Back camera", or "Back camera 2" when several cameras look the same way. */
internal fun CameraDescriptor.label(context: Context): String {
    val name =
        context.getString(
            when (lens) {
                LensFacing.Front -> R.string.camera_front
                LensFacing.External -> R.string.camera_external
                LensFacing.Back -> R.string.camera_back
            },
        )
    return if (number != null) context.getString(R.string.camera_numbered, name, number) else name
}

/** The message shown for a camera problem, in the language of [context]. */
internal fun CameraStatus.message(context: Context): String =
    when (this) {
        CameraStatus.NoPermission -> {
            context.getString(R.string.status_no_permission)
        }

        CameraStatus.NoCameras -> {
            context.getString(R.string.status_no_cameras)
        }

        CameraStatus.StartFailed -> {
            context.getString(R.string.status_start_failed)
        }

        CameraStatus.ConnectionLost -> {
            context.getString(R.string.status_connection_lost)
        }

        CameraStatus.HighSpeedUnsupported -> {
            context.getString(R.string.status_highspeed_unsupported)
        }

        is CameraStatus.ResolutionUnsupported -> {
            context.getString(
                R.string.status_resolution_unsupported,
                requested.width,
                requested.height,
                fallback.width,
                fallback.height,
            )
        }
    }
