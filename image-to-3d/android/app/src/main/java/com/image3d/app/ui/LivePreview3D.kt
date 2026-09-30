package com.image3d.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.image3d.app.anim.Animations
import com.image3d.app.anim.Rig
import com.image3d.app.mesh.Mesh
import com.image3d.app.render.ModelView

/** Petit modèle 3D qui tourne sur lui-même (aperçu pendant la génération). */
@Composable
fun LivePreview3D(mesh: Mesh, modifier: Modifier = Modifier) {
    key(mesh) {
        val ctx = LocalContext.current
        val view = remember {
            ModelView(ctx).apply {
                setMesh(mesh, Rig.build(mesh))
                setAnimation(Animations.byId("spin"))
            }
        }
        DisposableEffect(view) { onDispose { view.onPause() } }
        AndroidView(factory = { view }, modifier = modifier)
    }
}
