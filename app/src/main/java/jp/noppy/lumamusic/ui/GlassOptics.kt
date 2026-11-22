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
    if (!effects.blur && !effects.refraction && !effects.highlight && !effects.tint) return
    val rim = (if (selection) 8.5f + 2.6f * lift else 6.4f + 1.6f * lift).dp.toPx()
    val shift = (if (selection) 5.6f + 3.6f * lift else 3.1f + 1.8f * lift).dp.toPx()
    val quality = if (highQuality) 1.16f else 1f
    val softness = if (highQuality) 1.45f else 1.05f
    runtimeShaderEffect("AurioGlassOptics", GlassShader, "scene") {
        setFloatUniform("extent", size.width, size.height)
        setFloatUniform("inset", padding, padding)
        setFloatUniform("radius", radius)
        setFloatUniform("rimWidth", rim)
        setFloatUniform("travel", if (effects.refraction) shift * quality else 0f)
        setFloatUniform("rimLight", if (effects.highlight) .23f + .22f * lift else 0f)
        setFloatUniform("glintWidth", (1.05f + .33f * lift).dp.toPx())
        setFloatUniform("adaptive", if (effects.tint) 1f else 0f)
        setFloatUniform("softness", if (effects.blur) softness.dp.toPx() else 0f)
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
    uniform float softness;
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
        if (softness > 0.0) {
            // Keep most of the central ray. Symmetric taps soften high-frequency
            // edges without a full-surface blur pass or a shifted duplicate.
            float2 tap = float2(0.707, 0.707) * softness;
            half4 fringe = (
                scene.eval(clamp(samplePoint - tap, low, high)) +
                scene.eval(clamp(samplePoint + tap, low, high))
            ) * 0.5;
            ray = mix(ray, fringe, half(0.16 + 0.21 * edge));
        }
        float3 colour = ray.rgb / max(ray.a, 0.001);
        float3 weights = float3(0.2126, 0.7152, 0.0722);
        float luminance = dot(colour, weights);

        if (adaptive > 0.5) {
            // Compress tones that compete with the foreground ink. Both
            // curves retain tonality instead of clipping shadows/highlights
            // to a single luminance, and leave compatible pixels untouched.
            float lightLift = max(0.0, 0.54 + luminance * 0.39 - luminance);
            // Work in an approximation of linear light for dark mode so
            // saturated greens and reds also stay behind the light ink.
            float energy = dot(colour * colour, weights);
            float darkScale = sqrt(min(1.0, (0.036 + energy * 0.059) / max(energy, 0.001)));
            float3 lightened = mix(colour, float3(1.0), lightLift / max(1.0 - luminance, 0.01));
            float3 shaded = colour * darkScale;
            colour = mix(lightened, shaded, darkSurface);
            float3 transmission = mix(colour * 0.968, mix(colour, float3(1.0), 0.028), darkSurface);
            colour = mix(colour, transmission, selectionDepth);
        }

        if (rimLight > 0.0) {
            float rim = 1.0 - smoothstep(0.0, glintWidth, distanceInside);
            float facing = dot(normal, float2(-0.6, -0.8));
            float gleam = clamp(facing, 0.0, 1.0) * (0.91 - 0.54 * luminance);
            float occlusion = clamp(-facing, 0.0, 1.0) * (0.14 + 0.38 * luminance);
            colour = mix(colour, float3(1.0), rimLight * gleam * rim);
            colour *= 1.0 - rimLight * occlusion * rim;
        }
        return half4(half3(colour) * ray.a, ray.a);
    }
"""
