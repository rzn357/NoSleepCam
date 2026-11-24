package com.exciteddev.nosleepcam.camera

import android.hardware.camera2.CameraManager
import android.util.Log
import com.exciteddev.nosleepcam.LOG_TAG

/**
 * The device's cameras: a cache of their [CameraSpec]s (each read is a system call) and the choice of which camera to open.
 * Camera thread only.
 */
internal class CameraCatalog(
    private val manager: CameraManager,
) {
    private val reader = CameraSpecReader(manager)
    private val specs = HashMap<String, CameraSpec>()

    fun spec(id: String): CameraSpec = specs[id] ?: reader.read(id).also { specs[id] = it }

    private fun isBack(id: String) = !spec(id).isFront

    /**
     * Which camera to open. If the camera is already known (saved from the last launch) — we take it right away,
     * without iterating over the characteristics of all the device's cameras (each is a separate system call).
     */
    fun resolveTargetId(wantCameraId: String?): String? {
        if (wantCameraId != null) {
            try {
                val cached = specs[wantCameraId]
                if (cached != null) {
                    if (cached.resolutions.isNotEmpty()) return wantCameraId
                } else if (wantCameraId in manager.cameraIdList) {
                    // The characteristics have not been read yet: we don't spend time on them before opening — openCamera() reads them
                    // in parallel with the system opening the camera.
                    return wantCameraId
                }
            } catch (ex: Exception) {
                Log.w(LOG_TAG, "Camera $wantCameraId unavailable: ${ex.message}")
            }
        }

        val cameras = list()
        return (cameras.firstOrNull { isBack(it.id) } ?: cameras.firstOrNull())?.id
    }

    /** Cameras suitable for showing an image (without depth/IR sensors etc.). */
    fun list(): List<CameraDescriptor> {
        val usable = ArrayList<CameraSpec>()
        val ids =
            try {
                manager.cameraIdList
            } catch (ex: Exception) {
                Log.w(LOG_TAG, "Camera id list: ${ex.message}")
                emptyArray()
            }

        for (id in ids) {
            try {
                val spec = spec(id)
                if (spec.backwardCompatible && spec.resolutions.isNotEmpty()) usable.add(spec)
            } catch (ex: Exception) {
                Log.w(LOG_TAG, "Camera $id skipped: ${ex.message}")
            }
        }

        // If there are several cameras looking the same way, we number them: "Back camera 1", "Back camera 2".
        return usable
            .groupBy { it.lens }
            .values
            .flatMap { group ->
                if (group.size == 1) {
                    group.map { CameraDescriptor(it.id, it.lens) }
                } else {
                    group.mapIndexed { i, spec -> CameraDescriptor(spec.id, spec.lens, i + 1) }
                }
            }.sortedWith(compareBy({ if (isBack(it.id)) 0 else 1 }, { it.id }))
    }
}
