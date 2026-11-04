from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/StoreApp.kt"
LOCAL_SCREENS = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/DashboardScreen.kt"
PROFILE = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ActivityProfileScreen.kt"


def test_first_launch_requires_authentication_and_complete_project_context():
    main = MAIN.read_text(encoding="utf-8") + (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/MainActivity.kt").read_text(encoding="utf-8") + (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/LocalOperationalScreens.kt").read_text(encoding="utf-8")
    profile = PROFILE.read_text(encoding="utf-8")
    assert "restoringSession = true" in profile
    assert "CircularProgressIndicator()" in profile
    assert "var showEntryFlow by remember" in main
    assert "var showEntryFlow by remember { mutableStateOf(true) }" in main
    assert "LaunchedEffect(cloudContextSnapshot, cloudReloadToken, showEntryFlow)" in main
    assert "if (showEntryFlow)" in main
    assert "نتحقق من جلسة حسابك قبل فتح بيانات المؤسسة." in PROFILE.read_text(encoding="utf-8")
    assert "if (showEntryFlow)" in main
    assert "showBackButton = false" in main


def test_project_creation_returns_to_the_cloud_dashboard_and_refreshes_context():
    main = MAIN.read_text(encoding="utf-8")
    profile = PROFILE.read_text(encoding="utf-8")
    assert "onProfileCreated = {" in main
    assert "onAuthenticated = {" in main
    assert "hasSavedCommercialCloudContext(context)" in main
    assert "مشروعك مرتبط بالفعل" in profile
    assert "لن نُنشئ مشروعًا مكررًا" in profile
    assert "store-${UUID.randomUUID().toString().take(8)}" in profile
    assert "showEntryFlow = false" in main
    assert "cloudContextRevision++" in main
    assert "commercial_create_activity_profile" not in main  # The profile RPC stays encapsulated in the setup screen.


def test_existing_auth_session_is_restored_before_project_setup():
    profile = PROFILE.read_text(encoding="utf-8")
    assert "internal fun hasSavedCommercialAuthSession" in profile
    assert '"/auth/v1/token?grant_type=refresh_token"' in profile
    assert 'putString("refresh_token", rotated)' in profile
    assert 'remove("refresh_token")' in profile


def test_first_run_and_navigation_guidance_are_available_without_removing_features():
    main = MAIN.read_text(encoding="utf-8") + (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/MainActivity.kt").read_text(encoding="utf-8") + (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/LocalOperationalScreens.kt").read_text(encoding="utf-8")
    assert "إخفاء التلميحات" in main
    assert "الانتقال إلى إضافة الأصناف" in LOCAL_SCREENS.read_text(encoding="utf-8")
    assert "تسجيل أول عملية بيع" in LOCAL_SCREENS.read_text(encoding="utf-8")
    assert "ReportsCenterScreen" in main
    assert "CommercialCloudPurchasesScreen" in main
    assert "الأصناف والمخزون والمشتريات" in main
    assert "العملاء والمبيعات" in main
    assert "onBackup = {" in main
    assert "النسخ الاحتياطي لم تُربط بعد" in main
    assert "تم تحميل بيانات المؤسسة السحابية" in main
