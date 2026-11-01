#version 150

in vec2 panelPos;
in vec4 vertexColor;

uniform vec2 HalfSize;
uniform float Radius;
uniform float BorderWidth;
uniform vec4 BorderColor;
uniform float Sheen;

out vec4 fragColor;

/* 有符号距离：圆角矩形 */
float sdRoundedBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

void main() {
    vec2 p = panelPos - HalfSize;
    float d = sdRoundedBox(p, HalfSize, Radius);
    float aa = max(fwidth(d), 1e-4);

    /* 填充与描边掩码（抗锯齿 smoothstep） */
    float fillMask = 1.0 - smoothstep(-aa, aa, d);
    float borderMask = max(0.0, (1.0 - smoothstep(-aa, aa, d + BorderWidth)) - fillMask);

    /* 顶部高光（毛玻璃上缘亮、下缘暗的物理直觉） */
    float topRatio = clamp((HalfSize.y - panelPos.y) / max(HalfSize.y, 1.0), 0.0, 1.0);
    float sheenFactor = Sheen * topRatio;
    vec3 fillRgb = vertexColor.rgb + vec3(sheenFactor * 0.55);
    float fillA = vertexColor.a * fillMask;

    vec4 col = vec4(fillRgb * fillA, fillA);
    vec4 bc = vec4(BorderColor.rgb, BorderColor.a * borderMask);

    /* border over fill 的 over 合成 */
    float outA = col.a + bc.a * (1.0 - col.a);
    vec3 outRgb = (col.rgb + bc.rgb * bc.a * (1.0 - col.a)) / max(outA, 1e-5);

    fragColor = vec4(outRgb, outA);
    if (fragColor.a <= 0.003) discard;
}
