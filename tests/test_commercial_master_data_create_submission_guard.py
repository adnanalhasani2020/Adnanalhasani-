from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/MasterDataScreen.kt"


class MasterDataCreateSubmissionGuardTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.screen = SCREEN.read_text(encoding="utf-8")

    def test_save_button_is_disabled_during_request(self):
        self.assertIn('var createSaving by remember { mutableStateOf(false) }', self.screen)
        self.assertIn('enabled = !createSaving && code.isNotBlank() && name.isNotBlank()', self.screen)
        self.assertIn('if (createSaving) "جارٍ الحفظ..." else "حفظ"', self.screen)

    def test_dialog_stays_open_and_displays_error_when_save_fails(self):
        self.assertIn('var createError by remember { mutableStateOf("") }', self.screen)
        self.assertIn('Text(createError, color = MaterialTheme.colorScheme.error', self.screen)
        self.assertIn('createError = it.message ?: "تعذر الحفظ."', self.screen)
        self.assertIn('onDismissRequest = { if (!createSaving) showCreate = false }', self.screen)

    def test_submission_trims_user_input_and_only_closes_after_success(self):
        self.assertIn('val submittedCode = code.trim()', self.screen)
        self.assertIn('val submittedName = name.trim()', self.screen)
        self.assertIn('showCreate = false', self.screen)
        self.assertIn('result.onSuccess {', self.screen)


if __name__ == "__main__":
    unittest.main()
