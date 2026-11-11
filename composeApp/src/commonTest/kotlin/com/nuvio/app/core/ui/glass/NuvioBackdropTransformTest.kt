package com.nuvio.app.core.ui.glass

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.TransformOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NuvioBackdropTransformTest {
    @Test
    fun inverseRestoresPointsAfterTranslatedNonUniformScaleAndRotation() {
        assertInverseRoundTrip(
            translationX = 19f,
            translationY = -8f,
            scaleX = 1.18f,
            scaleY = 0.87f,
            rotationZ = 11f,
            transformOrigin = TransformOrigin(0.82f, 0.21f),
        )
    }

    @Test
    fun inverseRestoresPointsAfterDefaultOriginScaleAndTranslation() {
        assertInverseRoundTrip(
            translationX = -12f,
            translationY = 23f,
            scaleX = 0.94f,
            scaleY = 1.12f,
            rotationZ = 0f,
            transformOrigin = TransformOrigin.Center,
        )
    }

    @Test
    fun identityTransformDoesNotCreateAnInverse() {
        assertNull(
            inverseBackdropLayerTransform(
                size = Size(180f, 72f),
                translationX = 0f,
                translationY = 0f,
                scaleX = 1f,
                scaleY = 1f,
                rotationZ = 0f,
            ),
        )
    }

    @Test
    fun singularUnsupportedOrNonFiniteTransformsDoNotProduceAnInverse() {
        val size = Size(180f, 72f)
        assertNull(
            inverseBackdropLayerTransform(
                size = size,
                translationX = 0f,
                translationY = 0f,
                scaleX = 0f,
                scaleY = 1f,
                rotationZ = 0f,
            ),
        )
        assertNull(
            inverseBackdropLayerTransform(
                size = size,
                translationX = 0f,
                translationY = 0f,
                scaleX = 1f,
                scaleY = 1f,
                rotationZ = 0f,
                rotationX = 8f,
            ),
        )
        assertNull(
            inverseBackdropLayerTransform(
                size = size,
                translationX = Float.NaN,
                translationY = 0f,
                scaleX = 1f,
                scaleY = 1f,
                rotationZ = 0f,
            ),
        )
        assertNull(
            inverseBackdropLayerTransform(
                size = size,
                translationX = 0f,
                translationY = 0f,
                scaleX = Float.MAX_VALUE,
                scaleY = Float.MAX_VALUE,
                rotationZ = 0f,
            ),
        )

        assertNull(
            inverseBackdropLayerTransform(
                size = size,
                translationX = 0f,
                translationY = 0f,
                scaleX = Float.MIN_VALUE,
                scaleY = Float.MIN_VALUE,
                rotationZ = 0f,
            ),
        )
    }

    private fun assertInverseRoundTrip(
        translationX: Float,
        translationY: Float,
        scaleX: Float,
        scaleY: Float,
        rotationZ: Float,
        transformOrigin: TransformOrigin,
    ) {
        val size = Size(180f, 72f)
        val pivotX = size.width * transformOrigin.pivotFractionX
        val pivotY = size.height * transformOrigin.pivotFractionY
        val forward = Matrix().apply {
            resetToPivotedTransform(
                pivotX = pivotX,
                pivotY = pivotY,
                translationX = translationX,
                translationY = translationY,
                rotationZ = rotationZ,
                scaleX = scaleX,
                scaleY = scaleY,
            )
        }
        val inverse = assertNotNull(
            inverseBackdropLayerTransform(
                size = size,
                translationX = translationX,
                translationY = translationY,
                scaleX = scaleX,
                scaleY = scaleY,
                rotationZ = rotationZ,
                transformOrigin = transformOrigin,
            ),
        )

        for (point in listOf(Offset(12f, 21f), Offset(94f, 39f), Offset(161f, 65f))) {
            val restored = inverse.map(forward.map(point))
            assertEquals(point.x, restored.x, 0.001f)
            assertEquals(point.y, restored.y, 0.001f)
        }
    }
}
