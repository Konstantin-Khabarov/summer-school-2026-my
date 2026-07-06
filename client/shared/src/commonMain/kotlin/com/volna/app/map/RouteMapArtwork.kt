package com.volna.app.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.volna.app.core.theme.VolnaTheme
import com.volna.app.domain.model.GeoPoint

// Route line and pin are real projections of route.geometry/meetingPoint; the water/land backdrop is decorative, not geographic.
@Composable
fun RouteMapArtwork(
    routePoints: List<GeoPoint>,
    meetingPoint: GeoPoint,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(Color(0xFFF2F2F2), RoundedCornerShape(VolnaTheme.tokens.radius.md)),
    ) {
        val corner = 12.dp.toPx()
        val waterColor = Color(0xFF8BD1F1)
        val landColor = Color(0xFFDDF3CC)
        val streetColor = Color(0xFFF9F6F0)
        val routeColor = Color(0xFF00A59D)
        val pinColor = Color(0xFF00A59D)

        drawRoundRect(color = waterColor, cornerRadius = CornerRadius(corner, corner))
        drawRoundRect(
            color = landColor,
            topLeft = Offset(size.width * 0.02f, 0f),
            size = Size(size.width * 0.2f, size.height),
            cornerRadius = CornerRadius(corner, corner),
        )
        drawRoundRect(
            color = landColor,
            topLeft = Offset(size.width * 0.82f, 0f),
            size = Size(size.width * 0.16f, size.height),
            cornerRadius = CornerRadius(corner, corner),
        )
        listOf(0.22f, 0.5f, 0.78f).forEach { y ->
            drawLine(
                color = streetColor,
                start = Offset(0f, size.height * y),
                end = Offset(size.width, size.height * (y - 0.12f)),
                strokeWidth = 6.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        val allPoints = routePoints + meetingPoint
        val padding = 24.dp.toPx()
        val projected = projectGeoPoints(allPoints, size, padding)
        if (projected.isEmpty()) return@Canvas

        val pinOffset = projected.last()
        val routeOffsets = projected.dropLast(1)
        if (routeOffsets.size >= 2) {
            val path = Path().apply {
                moveTo(routeOffsets.first().x, routeOffsets.first().y)
                routeOffsets.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(
                path = path,
                color = routeColor,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        drawCircle(color = routeColor.copy(alpha = 0.16f), radius = 20.dp.toPx(), center = pinOffset)
        drawCircle(color = pinColor, radius = 6.dp.toPx(), center = pinOffset)
        drawCircle(color = Color.White, radius = 2.5.dp.toPx(), center = pinOffset)
    }
}

// Local equirectangular scaling is fine here since routes are short/local; no need for Mercator.
internal fun projectGeoPoints(points: List<GeoPoint>, size: Size, paddingPx: Float): List<Offset> {
    if (points.isEmpty()) return emptyList()
    if (points.size == 1) return listOf(Offset(size.width / 2f, size.height / 2f))

    val minLat = points.minOf { it.lat }
    val maxLat = points.maxOf { it.lat }
    val minLng = points.minOf { it.lng }
    val maxLng = points.maxOf { it.lng }
    val latSpan = (maxLat - minLat).takeIf { it > 1e-9 } ?: 1e-4
    val lngSpan = (maxLng - minLng).takeIf { it > 1e-9 } ?: 1e-4
    val drawWidth = size.width - paddingPx * 2
    val drawHeight = size.height - paddingPx * 2

    return points.map { point ->
        val x = paddingPx + ((point.lng - minLng) / lngSpan).toFloat() * drawWidth
        val y = paddingPx + (1f - ((point.lat - minLat) / latSpan).toFloat()) * drawHeight
        Offset(x, y)
    }
}
