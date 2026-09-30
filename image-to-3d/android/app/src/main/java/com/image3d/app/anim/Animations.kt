package com.image3d.app.anim

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.cos

/**
 * Animations prêtes à l'emploi, jouées sur le squelette automatique.
 * Chaque animation boucle sur [period] secondes ; les mêmes courbes sont utilisées
 * dans la visionneuse et dans l'export .glb (donc identiques dans Blender, Unity, Godot...).
 */
class Animation(
    val id: String,
    val label: String,
    val period: Float,
    private val apply: (pose: Pose, phase: Float, time: Float, h: Float) -> Unit,
) {
    /** Remplit [pose] pour l'instant [time] (secondes). [h] = hauteur de l'objet. */
    fun sample(pose: Pose, time: Float, h: Float) {
        pose.reset()
        val t = ((time % period) + period) % period
        apply(pose, (2 * PI * t / period).toFloat(), t, h)
    }
}

private fun deg(d: Float) = (d * PI / 180).toFloat()

object Animations {
    val NONE = Animation("none", "Aucune", 1f) { _, _, _, _ -> }

    val ALL: List<Animation> = listOf(
        NONE,
        Animation("spin", "Rotation", 6f) { p, w, _, _ ->
            p.rotate(0, 0f, w, 0f)
        },
        Animation("idle", "Respirer", 3.5f) { p, w, _, _ ->
            for (b in 1 until p.bones) p.scale(b, 1f + 0.015f * sin(w), 1f + 0.01f * sin(w), 1f + 0.015f * sin(w))
            p.rotate(p.bones - 1, deg(3f) * sin(w), 0f, 0f)
        },
        Animation("float", "Flotter", 4f) { p, w, _, h ->
            p.translate(0, 0f, 0.08f * h * (1 + sin(w)), 0f)
            p.rotate(0, deg(3f) * sin(w + 1f), deg(12f) * sin(w), deg(4f) * cos(w))
        },
        Animation("jump", "Sauter", 1.2f) { p, _, t, h ->
            val phi = t / 1.2f
            if (phi < 0.2f) { // écrasement au sol
                val sq = sin(PI.toFloat() * phi / 0.2f)
                p.scale(0, 1f + 0.12f * sq, 1f - 0.2f * sq, 1f + 0.12f * sq)
            } else { // saut en cloche avec étirement
                val u = (phi - 0.2f) / 0.8f
                p.translate(0, 0f, 0.35f * h * 4 * u * (1 - u), 0f)
                val st = sin(PI.toFloat() * u)
                p.scale(0, 1f - 0.05f * st, 1f + 0.08f * st, 1f - 0.05f * st)
            }
        },
        Animation("dance", "Danser", 2f) { p, w, _, h ->
            p.translate(0, 0f, 0.03f * h * abs(sin(w)), 0f)
            p.rotate(0, 0f, deg(20f) * sin(w), 0f)
            for (b in 1 until p.bones) p.rotate(b, 0f, 0f, deg(10f) * sin(w + b * 0.6f))
        },
        Animation("walk", "Dandiner", 1f) { p, w, _, h ->
            p.translate(0, 0f, 0.04f * h * abs(sin(w)), 0f)
            p.rotate(0, 0f, deg(5f) * sin(w), deg(8f) * sin(w))
            p.rotate(p.bones - 1, 0f, 0f, deg(-4f) * sin(w))
        },
        Animation("jelly", "Gelée", 1.5f) { p, w, _, _ ->
            for (b in 0 until p.bones) {
                val k = sin(w - b * 0.8f)
                p.scale(b, 1f - 0.035f * k, 1f + 0.05f * k, 1f - 0.035f * k)
                if (b > 0) p.rotate(b, deg(2f) * k, 0f, deg(3f) * cos(w - b * 0.8f))
            }
        },
        Animation("wave", "Onduler", 2.5f) { p, w, _, _ ->
            for (b in 1 until p.bones) {
                p.rotate(b, deg(6f) * cos(w - b.toFloat()), 0f, deg(14f) * sin(w - b.toFloat()))
            }
        },
        Animation("look", "Regarder", 3f) { p, w, _, _ ->
            p.rotate(p.bones - 2, deg(4f) * sin(2 * w), deg(15f) * sin(w), 0f)
            p.rotate(p.bones - 1, deg(4f) * sin(2 * w), deg(15f) * sin(w), 0f)
        },
    )

    fun byId(id: String) = ALL.firstOrNull { it.id == id } ?: NONE
}
