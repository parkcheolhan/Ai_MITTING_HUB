"""Real-engine regression checks; set SPEAKER_TEST_MODEL_DIR to installed models."""
import os
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main/resources/speech"))
from diarize import load_engine, identify


class SpeakerPathsTest(unittest.TestCase):
    def test_missing_models_preserve_working_directory(self):
        before = Path.cwd()
        with tempfile.TemporaryDirectory(prefix="화자-모델-") as empty:
            with self.assertRaisesRegex(ValueError, "SPEAKER_MODEL_MISSING"):
                load_engine(empty)
        self.assertEqual(Path.cwd(), before)

    @unittest.skipUnless(os.environ.get("SPEAKER_TEST_MODEL_DIR"), "Requires installed speaker models")
    def test_models_load_from_unicode_path_and_inference_runs(self):
        import numpy as np
        model_dir = Path(os.environ["SPEAKER_TEST_MODEL_DIR"]).resolve()
        before = Path.cwd()
        # Enter a Unicode directory to reproduce Java's normal Windows working paths.
        with tempfile.TemporaryDirectory(prefix="한글-화자-검증-") as working:
            os.chdir(working)
            try:
                engine = load_engine(model_dir)
                self.assertEqual(engine.sample_rate, 16000)
                self.assertEqual(Path.cwd(), Path(working))
                result = identify(np.zeros(48000, dtype=np.float32),
                                  [{"start": 0, "end": 3, "text": ""}], model_dir)
                self.assertEqual(result[0]["speaker"], "화자 미지정")
                self.assertEqual(Path.cwd(), Path(working))
            finally:
                os.chdir(before)


if __name__ == "__main__":
    unittest.main()
