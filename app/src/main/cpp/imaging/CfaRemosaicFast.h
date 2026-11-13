#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <thread>
#include <vector>

namespace rawrcam::imaging {

// Fast block-CFA to Bayer for large stills (Xiaomi 200 MP stills arrive as 4x4 same-colour blocks).
// 1. Flat-field the fixed per-position gain pattern inside the blocks (PDAF / shared-lens sub-pixels),
//    measured on the frame itself.
// 2. Block means form a Bayer mosaic at block resolution; demosaic it bilinearly into R, G, B planes.
// 3. Each output pixel keeps its own measured detail and takes the wanted colour by ratio:
//    out = want(x, y) * (pixel + e) / own(x, y) + e), planes interpolated between block centres.
// One pass over the frame, in place, rows split across threads. Clipped pixels are left as they are.
namespace cfafast {
inline int colourAt(uint32_t order, uint32_t py, uint32_t px) {
    // order: 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR. Returns 0 R, 1 G, 2 B.
    static constexpr uint32_t ry[4] = {0, 0, 1, 1}, rx[4] = {0, 1, 0, 1};
    const uint32_t o = order & 3u;
    if (py == ry[o] && px == rx[o]) return 0;
    if (py != ry[o] && px != rx[o]) return 2;
    return 1;
}

template <typename F>
inline void parallelRows(uint32_t rows, uint32_t threads, F&& fn) {
    threads = std::max(1u, std::min(threads, rows));
    std::vector<std::thread> pool;
    const uint32_t step = (rows + threads - 1) / threads;
    for (uint32_t t = 0; t < threads; ++t) {
        const uint32_t a = t * step, b = std::min(rows, a + step);
        if (a >= b) break;
        pool.emplace_back([&fn, a, b] { fn(a, b); });
    }
    for (auto& th : pool) th.join();
}
}  // namespace cfafast

inline void remosaicBlockCfaFast(uint16_t* raw, uint32_t w, uint32_t h, uint32_t n, uint32_t order, float black,
                                 float white, uint32_t threads) {
    using namespace cfafast;
    if (!raw || n < 2 || w < 4 * n || h < 4 * n) return;
    const uint32_t bw = w / n, bh = h / n;
    if (white <= black) white = 65535.0f;
    const float range = white - black;
    const size_t nn = size_t(n) * n;

    // 1. Intra-block gain per (block colour, position): sampled block rows, mid-tone blocks only.
    std::vector<double> sum(3 * nn, 0.0);
    std::vector<uint32_t> cnt(3 * nn, 0);
    const uint32_t rowStep = std::max(1u, bh / 256u);
    for (uint32_t by = 0; by < bh; by += rowStep)
        for (uint32_t bx = 0; bx < bw; ++bx) {
            float m = 0.0f, mx = 0.0f;
            for (uint32_t iy = 0; iy < n; ++iy)
                for (uint32_t ix = 0; ix < n; ++ix) {
                    const float v = float(raw[size_t(by * n + iy) * w + bx * n + ix]);
                    m += v;
                    mx = std::max(mx, v);
                }
            m = m / float(nn) - black;
            if (m < 0.05f * range || mx >= white - 1.0f) continue;
            const int c = colourAt(order, by & 1u, bx & 1u);
            for (uint32_t iy = 0; iy < n; ++iy)
                for (uint32_t ix = 0; ix < n; ++ix) {
                    const size_t k = size_t(c) * nn + iy * n + ix;
                    sum[k] += (float(raw[size_t(by * n + iy) * w + bx * n + ix]) - black) / m;
                    ++cnt[k];
                }
        }
    std::vector<float> invGain(3 * nn, 1.0f);
    for (int c = 0; c < 3; ++c) {
        double mean = 0.0;
        bool ok = true;
        for (size_t i = 0; i < nn; ++i) {
            const size_t k = size_t(c) * nn + i;
            if (cnt[k] < 64) ok = false;
            else mean += sum[k] / cnt[k];
        }
        if (!ok || mean <= 0.0) continue;
        mean /= double(nn);
        for (size_t i = 0; i < nn; ++i) {
            const size_t k = size_t(c) * nn + i;
            const double g = (sum[k] / cnt[k]) / mean;
            if (g > 0.5 && g < 2.0) invGain[k] = float(1.0 / g);
        }
    }

    // 2. Flat-fielded block means and their bilinear demosaic.
    std::vector<float> means(size_t(bw) * bh);
    parallelRows(bh, threads, [&](uint32_t a, uint32_t b) {
        for (uint32_t by = a; by < b; ++by)
            for (uint32_t bx = 0; bx < bw; ++bx) {
                const int c = colourAt(order, by & 1u, bx & 1u);
                float m = 0.0f;
                for (uint32_t iy = 0; iy < n; ++iy)
                    for (uint32_t ix = 0; ix < n; ++ix)
                        m += (float(raw[size_t(by * n + iy) * w + bx * n + ix]) - black) *
                             invGain[size_t(c) * nn + iy * n + ix];
                means[size_t(by) * bw + bx] = std::max(0.0f, m / float(nn));
            }
    });
    std::array<std::vector<float>, 3> planes;
    for (auto& p : planes) p.resize(means.size());
    parallelRows(bh, threads, [&](uint32_t a, uint32_t b) {
        static constexpr float k[3][3] = {{1, 2, 1}, {2, 4, 2}, {1, 2, 1}};
        for (uint32_t by = a; by < b; ++by)
            for (uint32_t bx = 0; bx < bw; ++bx) {
                const int own = colourAt(order, by & 1u, bx & 1u);
                float acc[3] = {0, 0, 0}, wt[3] = {0, 0, 0};
                for (int dy = -1; dy <= 1; ++dy)
                    for (int dx = -1; dx <= 1; ++dx) {
                        const int yy = std::clamp(int(by) + dy, 0, int(bh) - 1);
                        const int xx = std::clamp(int(bx) + dx, 0, int(bw) - 1);
                        const int c = colourAt(order, uint32_t(yy) & 1u, uint32_t(xx) & 1u);
                        acc[c] += k[dy + 1][dx + 1] * means[size_t(yy) * bw + xx];
                        wt[c] += k[dy + 1][dx + 1];
                    }
                for (int c = 0; c < 3; ++c)
                    planes[c][size_t(by) * bw + bx] =
                        c == own ? means[size_t(by) * bw + bx] : (wt[c] > 0 ? acc[c] / wt[c] : 0.0f);
            }
    });

    // 3. Per pixel: wanted colour from its own detail, in place.
    const float e = std::max(1.0f, 0.004f * range);
    const float half = 0.5f * float(n - 1);
    std::vector<int> x0(w);
    std::vector<float> fx(w);
    for (uint32_t x = 0; x < w; ++x) {
        const float s = (float(x) - half) / float(n);
        const int i = std::clamp(int(std::floor(s)), 0, int(bw) - 2);
        x0[x] = i;
        fx[x] = std::clamp(s - float(i), 0.0f, 1.0f);
    }
    parallelRows(h, threads, [&](uint32_t a, uint32_t b) {
        for (uint32_t y = a; y < b; ++y) {
            const float s = (float(y) - half) / float(n);
            const int y0 = std::clamp(int(std::floor(s)), 0, int(bh) - 2);
            const float fy = std::clamp(s - float(y0), 0.0f, 1.0f);
            const uint32_t by = y / n, iy = y % n;
            uint16_t* row = raw + size_t(y) * w;
            for (uint32_t x = 0; x < w; ++x) {
                const uint32_t bx = std::min(x / n, bw - 1), ix = x % n;
                const int own = colourAt(order, by & 1u, bx & 1u);
                const int want = colourAt(order, y & 1u, x & 1u);
                const float v = float(row[x]);
                if (v >= white - 1.0f) continue;  // clipped: keep
                const size_t i00 = size_t(y0) * bw + x0[x];
                const auto sample = [&](int c) {
                    const float* p = planes[c].data();
                    const float top = p[i00] + (p[i00 + 1] - p[i00]) * fx[x];
                    const float bot = p[i00 + bw] + (p[i00 + bw + 1] - p[i00 + bw]) * fx[x];
                    return top + (bot - top) * fy;
                };
                const float f = std::max(0.0f, (v - black) * invGain[size_t(own) * nn + iy * n + ix]);
                const float out = own == want ? f : sample(want) * (f + e) / (sample(own) + e);
                row[x] = uint16_t(std::clamp(out + black, 0.0f, 65535.0f));
            }
        }
    });
}

}  // namespace rawrcam::imaging
