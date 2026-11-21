package com.rawr.camera.renderer

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The Renderer's own icon in the app drawer: the disabled-by-default `.renderer.RendererLauncher`
 * alias in the manifest. Its state lives in the package manager, so nothing is stored here.
 */
internal object RendererLauncherIcon {
    private fun component(context: Context) =
        ComponentName(context.packageName, "com.rawr.camera.renderer.RendererLauncher")

    fun isShown(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(component(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setShown(context: Context, shown: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            component(context),
            if (shown) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP
        )
    }
}
