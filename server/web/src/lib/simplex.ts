// 3D simplex noise (Stefan Gustavson's reference algorithm).

const F3 = 1 / 3;
const G3 = 1 / 6;

// The 12 gradient directions: edge midpoints of a cube.
const GRAD = new Int8Array([
  1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1, 0,
  1, 0, 1, -1, 0, 1, 1, 0, -1, -1, 0, -1,
  0, 1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1,
]);

/** Returns a seeded `noise(x, y, z)` with values roughly in [-1, 1]. */
export function createSimplex3(random: () => number = Math.random) {
  const p = new Uint8Array(256);
  for (let i = 0; i < 256; i++) p[i] = i;
  for (let i = 255; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [p[i], p[j]] = [p[j], p[i]];
  }
  const perm = new Uint8Array(512);
  const grad = new Uint8Array(512); // perm index → offset into GRAD
  for (let i = 0; i < 512; i++) {
    perm[i] = p[i & 255];
    grad[i] = (perm[i] % 12) * 3;
  }

  const corner = (g: number, x: number, y: number, z: number) => {
    let t = 0.6 - x * x - y * y - z * z;
    if (t < 0) return 0;
    t *= t;
    return t * t * (GRAD[g] * x + GRAD[g + 1] * y + GRAD[g + 2] * z);
  };

  return (x: number, y: number, z: number): number => {
    // Skew into the simplex grid and find the containing cell.
    const s = (x + y + z) * F3;
    const i = Math.floor(x + s);
    const j = Math.floor(y + s);
    const k = Math.floor(z + s);
    const t = (i + j + k) * G3;
    const x0 = x - i + t;
    const y0 = y - j + t;
    const z0 = z - k + t;

    // Pick the simplex (one of six tetrahedra) the point lies in: the second
    // corner steps along the largest axis, the third along the two largest.
    const xy = x0 >= y0;
    const yz = y0 >= z0;
    const xz = x0 >= z0;
    const i1 = xy && xz ? 1 : 0;
    const j1 = !xy && yz ? 1 : 0;
    const k1 = !xz && !yz ? 1 : 0;
    const i2 = xy || xz ? 1 : 0;
    const j2 = !xy || yz ? 1 : 0;
    const k2 = !(xz && yz) ? 1 : 0;

    const ii = i & 255;
    const jj = j & 255;
    const kk = k & 255;
    return 32 * (
      corner(grad[ii + perm[jj + perm[kk]]], x0, y0, z0) +
      corner(grad[ii + i1 + perm[jj + j1 + perm[kk + k1]]], x0 - i1 + G3, y0 - j1 + G3, z0 - k1 + G3) +
      corner(grad[ii + i2 + perm[jj + j2 + perm[kk + k2]]], x0 - i2 + 2 * G3, y0 - j2 + 2 * G3, z0 - k2 + 2 * G3) +
      corner(grad[ii + 1 + perm[jj + 1 + perm[kk + 1]]], x0 - 1 + 3 * G3, y0 - 1 + 3 * G3, z0 - 1 + 3 * G3)
    );
  };
}
