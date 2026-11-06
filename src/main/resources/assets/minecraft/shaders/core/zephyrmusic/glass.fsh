#version 150

in vec2 panelPos;
in vec4 vertexColor;

uniform vec2 HalfSize;
uniform vec2 Center;      /* SDF 原点在 quad 内的位置（阴影扩边时不等于 HalfSize） */
uniform float Radius;
uniform float BorderWidth;
uniform vec4 BorderColor;
uniform float Sheen;
uniform float Shadow;     /* 外侧软阴影强度 0..1（只在 d>0 区域生效） */
uniform float ShapeKind;  /* 0 = 圆角矩形, 1 = 左右指向三角形 */
uniform float FlipX;      /* 1 = 水平镜像（三角形指左） */

out vec4 fragColor;

/* 有符号距离：圆角矩形 */
float sdRoundedBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

/* 有符号距离：等边三角形（顶点朝上，r = 外接半径） */
float sdEqTriangle(vec2 p, float r) {
    const float k = sqrt(3.0);
    p.x = abs(p.x) - r;
    p.y = p.y + r / k;
    if (p.x + k * p.y > 0.0) p = vec2(p.x - k * p.y, -k * p.x - p.y) / 2.0;
    p.x -= clamp(p.x, -2.0 * r, 0.0);
    return -length(p) * sign(p.y);
}

/* 顶到中部的轻微整体提亮系数（0=顶部 1=底部） */
float topTint(float distTop) {
    return clamp(1.0 - distTop, 0.0, 1.0);
}

void main() {
    vec2 p = panelPos - Center;
    float d;
    float aa;
    if (ShapeKind > 0.5) {
        vec2 pt = p;
        /* 顶点朝上 → 顺时针旋转 90° 变为顶点朝右：(x,y) -> (y,-x)。
           镜像必须在旋转之后（朝上的等边三角形左右对称，先镜像无效果） */
        pt = vec2(pt.y, -pt.x);
        if (FlipX > 0.5) pt.x = -pt.x;
        float r = min(HalfSize.x, HalfSize.y) * 0.92;
        d = sdEqTriangle(pt, r);
        aa = max(fwidth(d), 1e-4);
    } else {
        d = sdRoundedBox(p, HalfSize, Radius);
        aa = max(fwidth(d), 1e-4);
    }

    /* 填充与描边掩码（抗锯齿 smoothstep） */
    float fillMask = 1.0 - smoothstep(-aa, aa, d);
    float borderMask = max(0.0, (1.0 - smoothstep(-aa, aa, d + BorderWidth)) - fillMask);

    /* 顶部高光：真实玻璃是"上缘一条窄亮带"，不是整个上半面均匀提亮。
       指数衰减把高光压在顶缘附近，其余区域只保留轻微渐变。 */
    float distTop = clamp((Center.y - panelPos.y) / max(HalfSize.y, 1.0), 0.0, 2.0); /* 0=顶 1=底 */
    float band = exp(-distTop * distTop * 60.0);   /* 顶缘窄带 */
    float sheenFactor = Sheen * (band * 0.85 + topTint(distTop) * 0.15);

    vec3 fillRgb = vertexColor.rgb + vec3(sheenFactor * 0.5);
    float fillA = vertexColor.a * fillMask;
    vec4 col = vec4(fillRgb * fillA, fillA);

    /* 描边 alpha 随高度衰减：顶部亮（受光边），底部只留 30% —— 立体感的关键 */
    float edgeK = mix(1.0, 0.30, clamp(distTop, 0.0, 1.0));
    vec4 bc = vec4(BorderColor.rgb, BorderColor.a * borderMask * edgeK);

    /* border over fill 的 over 合成 */
    float outA = col.a + bc.a * (1.0 - col.a);
    vec3 outRgb = (col.rgb + bc.rgb * bc.a * (1.0 - col.a)) / max(outA, 1e-5);

    /* 外侧软阴影：黑色、指数衰减，配合扩边四边形产生真实悬浮感 */
    vec3 finRgb = outRgb;
    float finA = outA;
    if (Shadow > 0.001) {
        float sd = max(d, 0.0);
        float shA = Shadow * exp(-sd / 16.0) * (1.0 - fillMask);
        float a2 = outA + shA * (1.0 - outA);
        finRgb = (outRgb * outA) / max(a2, 1e-5);   /* 阴影颜色 = 黑 */
        finA = a2;
    }

    fragColor = vec4(finRgb, finA);
    if (fragColor.a <= 0.003) discard;
}
