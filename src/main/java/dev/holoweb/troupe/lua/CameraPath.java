package dev.holoweb.troupe.lua;

import java.util.List;

/**
 * A camera move through key frames ({@code x, y, z, yaw, pitch}), for {@code render.camera_path}: the position
 * follows a smooth curve through every point (Catmull-Rom), the yaw turns the short way round.
 */
public final class CameraPath {
    private final double[][] keys;
    private final boolean ease;

    /** {@code ease}: start and stop gently instead of at constant speed. */
    public CameraPath(List<double[]> keys, boolean ease) {
        if (keys.size() < 2) throw new IllegalArgumentException("a camera path needs at least 2 points");
        if (keys.size() > 256) throw new IllegalArgumentException("a camera path has at most 256 points");
        this.keys = keys.toArray(new double[0][]);
        this.ease = ease;
    }

    /** The camera at {@code t} (0 = first point, 1 = last): {@code x, y, z, yaw, pitch}. */
    public double[] at(double t) {
        t = Math.max(0, Math.min(1, t));
        if (ease) t = t * t * (3 - 2 * t);
        int n = keys.length;
        double f = t * (n - 1);
        int i = Math.min((int) f, n - 2);
        double u = f - i;
        double[] p0 = keys[Math.max(i - 1, 0)], p1 = keys[i], p2 = keys[i + 1], p3 = keys[Math.min(i + 2, n - 1)];
        double[] out = new double[5];
        for (int c = 0; c < 3; c++)
            out[c] = 0.5 * (2 * p1[c] + (p2[c] - p0[c]) * u + (2 * p0[c] - 5 * p1[c] + 4 * p2[c] - p3[c]) * u * u
                    + (3 * p1[c] - p0[c] - 3 * p2[c] + p3[c]) * u * u * u);
        double turn = ((p2[3] - p1[3]) % 360 + 540) % 360 - 180;
        out[3] = p1[3] + turn * u;
        out[4] = p1[4] + (p2[4] - p1[4]) * u;
        return out;
    }
}
