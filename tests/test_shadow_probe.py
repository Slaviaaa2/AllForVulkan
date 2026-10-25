import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import shadow_probe


class ShadowProbeDiffTest(unittest.TestCase):
    def image(self, width, height, fill, edits=()):
        rgba = bytearray(fill) * (width * height)
        for index, value in edits:
            rgba[index] = value
        path = Path(tempfile.mkdtemp()) / 'shadow.rgba'
        shadow_probe.write_image(path, width, height, bytes(rgba))
        return shadow_probe.read_image(path)

    def test_center_ignores_the_border_and_honors_tolerance(self):
        width = height = 32
        fill = bytes((128, 64, 192, 255))
        left = self.image(width, height, fill)
        corner = self.image(width, height, fill, edits=((0, 0),))
        self.assertEqual(shadow_probe.center_mismatches(left[2], corner[2], width, height, 8), 0)
        center = ((height // 2) * width + (width // 2)) * 4
        moved = self.image(width, height, fill, edits=((center, 0),))
        self.assertEqual(shadow_probe.center_mismatches(left[2], moved[2], width, height, 8), 1)
        near = self.image(width, height, fill, edits=((center, 128 - 8),))
        self.assertEqual(shadow_probe.center_mismatches(left[2], near[2], width, height, 8), 0)
        self.assertEqual(left[0], width)
        self.assertEqual(left[1], height)

    def test_pass_line_requires_the_pack_clear_color(self):
        shadow_probe.require_constant(
            'AFV_SHADOW_PROBE_PASS: width=32 height=32 format=RGBA hook=false file=afv-shadow-off.rgba nonzero=1024/1024 center=128,64,192,255')
        with self.assertRaises(SystemExit):
            shadow_probe.require_constant(
                'AFV_SHADOW_PROBE_PASS: width=1024 height=1024 format=RGBA hook=false file=afv-shadow-off.rgba nonzero=1/1 center=255,255,255,255')
        pack = Path(__file__).resolve().parents[1] / 'tests/shaderpacks/AFV-Shadow/shaders/shadow.fsh'
        text = pack.read_text()
        self.assertIn('const int shadowMapResolution = 32;', text)
        self.assertIn('const vec4 shadowcolor0ClearColor', text)


if __name__ == '__main__':
    unittest.main()
