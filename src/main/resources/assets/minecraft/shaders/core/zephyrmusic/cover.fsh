#version 150

in vec2 panelPos;
in vec4 vertexColor;

uniform vec2 HalfSize;
uniform float Radius;
uniform sampler2D Sampler0;

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

    /* 圆角掩码（抗锯齿 smoothstep） */
    float m = 1.0 - smoothstep(-aa, aa, d);
    if (m <= 0.003) discard;

    /* 局部坐标 → 贴图 UV（DynamicTexture 行 0 = 图片顶部 = v 0） */
    vec2 size = max(HalfSize * 2.0, vec2(1.0));
    vec2 uv = clamp(panelPos / size, vec2(0.0), vec2(1.0));
    vec4 tex = texture(Sampler0, uv);

    vec4 col = tex * vertexColor;
    fragColor = vec4(col.rgb * col.a * m, col.a * m);
}
