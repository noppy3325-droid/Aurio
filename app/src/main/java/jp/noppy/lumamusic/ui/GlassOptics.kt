package jp.noppy.lumamusic.ui

import androidx.compose.ui.unit.dp
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.runtimeShaderEffect

/** The source is a live, position-aligned recording of the page behind this surface. */
internal fun BackdropEffectScope.glassOptics(
    radius: Float,
    dark: Boolean,
    highQuality: Boolean,
    selection: Boolean,
    lift: Float,
    effects: GlassEffects,
) {
    if (!effects.refraction && !effects.highlight && !effects.tint) return
    val rim = (if (selection) 9f + 3f * lift else 7f + 2f * lift).dp.toPx()
    val shift = (if (selection) 7f + 4f * lift else 4f + 2f * lift).dp.toPx()
    val quality = if (highQuality) 1.25f else 1f
    runtimeShaderEffect("AurioGlassOptics", GlassShader, "scene") {
        setFloatUniform("extent", size.width, size.height)
        setFloatUniform("inset", padding, padding)
        setFloatUniform("radius", radius)
        setFloatUniform("rimWidth", rim)
        setFloatUniform("travel", if (effects.refraction) shift * quality else 0f)
        setFloatUniform("rimLight", if (effects.highlight) .32f + .20f * lift else 0f)
        setFloatUniform("glintWidth", (1.6f + .35f * lift).dp.toPx())
        setFloatUniform("adaptive", if (effects.tint) 1f else 0f)
        setFloatUniform("detailRadius", if (effects.blur) 2.2.dp.toPx() else 0f)
        setFloatUniform("darkSurface", if (dark) 1f else 0f)
        setFloatUniform("selectionDepth", if (selection) 1f else 0f)
    }
}

// Rounded-rectangle distance and its local normal determine how far a ray
// bends. The optical centre samples the original coordinates. The rim applies
// a soft displacement inward and catches light on the upper-left side.
private const val GlassShader = """
    uniform shader scene;
    uniform float2 extent;
    uniform float2 inset;
    uniform float radius;
    uniform float rimWidth;
    uniform float travel;
    uniform float rimLight;
    uniform float glintWidth;
    uniform float adaptive;
    uniform float detailRadius;
    uniform float darkSurface;
    uniform float selectionDepth;

    half4 main(float2 pixel) {
        float2 centre = extent * 0.5;
        float2 position = pixel - inset - centre;
        float2 box = abs(position) - (centre - radius);
        float2 corner = max(box, 0.0);
        float cornerLength = length(corner);
        float distanceInside = radius - cornerLength - min(max(box.x, box.y), 0.0);
        float2 nearestSide = box.x > box.y ? float2(1.0, 0.0) : float2(0.0, 1.0);
        float2 normal = sign(position) * (cornerLength > 0.001 ? corner / cornerLength : nearestSide);
        float edge = 1.0 - smoothstep(0.0, rimWidth, distanceInside);
        float2 samplePoint = pixel - normal * (travel * edge);
        float2 low = inset + 0.5;
        float2 high = max(low, inset + extent - 0.5);
        half4 ray = scene.eval(clamp(samplePoint, low, high));
        float3 colour = ray.rgb / max(ray.a, 0.001);
        float3 weights = float3(0.2126, 0.7152, 0.0722);
        float luminance = dot(colour, weights);
        float detail = 0.0;
        if (detailRadius > 0.0 && (adaptive > 0.5 || rimLight > 0.0)) {
            float2 nearbyPoint = samplePoint + float2(0.82, 0.57) * detailRadius;
            half4 nearbyRay = scene.eval(clamp(nearbyPoint, low, high));
            float3 nearby = nearbyRay.rgb / max(nearbyRay.a, 0.001);
            detail = smoothstep(0.09, 0.32, abs(luminance - dot(nearby, weights)));
            colour = mix(colour, nearby, detail * 0.21);
            luminance = dot(colour, weights);
        }

        // Lift dark detail behind dark text, or lower highlights behind white
        // text. Preserve the nearby hue and leave already-legible areas clear.
        if (adaptive > 0.5) {
            float3 lightened = mix(colour, float3(0.98), clamp((0.72 - luminance) / max(0.98 - luminance, 0.01), 0.0, 0.73));
            float3 shaded = colour * min(1.0, 0.33 / max(luminance, 0.01));
            colour = mix(lightened, shaded, darkSurface);
            // A thicker lens changes transmission slightly so its outline is
            // still visible over an evenly lit page.
            float3 transmission = mix(colour * 0.957, mix(colour, float3(1.0), 0.052), darkSurface);
            colour = mix(colour, transmission, selectionDepth);
        }

        if (rimLight > 0.0) {
            float rim = 1.0 - smoothstep(0.0, glintWidth, distanceInside);
            float facing = dot(normal, float2(-0.6, -0.8));
            float gleam = clamp(facing, 0.0, 1.0) * (0.95 - 0.52 * luminance);
            float occlusion = clamp(-facing, 0.0, 1.0) * (0.13 + 0.36 * luminance + 0.10 * detail);
            colour = mix(colour, float3(1.0), rimLight * gleam * rim);
            colour *= 1.0 - rimLight * occlusion * rim;
        }
        return half4(half3(colour) * ray.a, ray.a);
    }
"""
