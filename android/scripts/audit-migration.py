"""Rebuild the exhaustive source inventory without turning a file mapping into a parity claim."""
from pathlib import Path
import hashlib, json, re, datetime

ROOT=Path(__file__).resolve().parents[2]
ANDROID=ROOT/'android'
JAVA='android/app/src/main/java/app/monogatari/android/'
UNIT='android/app/src/test/java/app/monogatari/android/'
DEVICE='android/app/src/androidTest/java/app/monogatari/android/'
MODULES={}
def module(key, files, summary, evidence=(), differences=(), pending=()):
    MODULES[key]={'android_files':[JAVA+f for f in files],'summary':summary,'evidence':list(evidence),
        'platform_differences':list(differences),'pending_items':list(pending),'verification_status':'final_combined_suite_pending'}

module('app',['AppBrand.kt','MonogatariApplication.kt','MainActivity.kt','MonogatariViewModel.kt','ui/MonogatariApp.kt','library/IncomingLinks.kt','library/ForegroundActivities.kt'],
       'Application lifecycle, five tabs, native navigation, state restoration, intents, image caches, privacy and reading-session lifecycle.',
       [UNIT+'library/LibraryInsightsTest.kt',UNIT+'library/IncomingLinksTest.kt'],['Android Activity/Compose replaces UIWindowScene/UIKit; native chrome, gestures and rendering differ.'])
module('settings',['data/SettingsStore.kt','ui/OriginalSettingsScreen.kt','ui/SettingsScreens.kt'],
       'Original UserDefaults names/defaults/schema, settings search, conditional settings, native appearance and actions.',
       [UNIT+'data/MigrationTest.kt'],['Shared JSON-backed settings and Android controls replace UserDefaults/SwiftUI; extra Android controls use English/Chinese fallback.'])
module('reader_settings',['reader/ReaderOptions.kt','reader/ReaderSettings.kt','reader/TapZones.kt','reader/ReaderInput.kt'],
       'Original reader defaults and dot-separated series overrides; direction/tap zones/layout/split/crop/AI/prefetch/text/pillarbox and native extras.',
       [UNIT+'data/ReaderArchiveTest.kt',UNIT+'reader/ReaderInputTest.kt',DEVICE+'reader/ReaderKeyboardInstrumentedTest.kt'],['Apple-specific font names fall back to Android fonts; native extras use Reader.androidOptions dot-suffix JSON while original settings remain authoritative.'])
module('reader',['ui/ReaderScreen.kt','reader/ReaderLayout.kt','reader/ChapterListing.kt','reader/ChapterNavigator.kt','reader/ReaderPageCache.kt','reader/ReaderTransition.kt','reader/ReaderImages.kt','reader/ReaderImageGeometry.kt'],
       'Image/text/EPUB reader, paged/vertical/webtoon/continuous, double spreads, wide-page handling, zoom, filters, progress, auto-scroll, OCR, descriptions, sharing, original duplicate/readability/scanlator chapter navigation, scoped real page cache eviction/reload/retry and hardware keyboard shortcuts with modal/input isolation.',
       [UNIT+'data/ReaderArchiveTest.kt',UNIT+'reader/ChapterNavigatorTest.kt',UNIT+'reader/ReaderPageCacheTest.kt',DEVICE+'reader/OriginalUpscaleModelsTest.kt',DEVICE+'reader/ReaderGalleryInstrumentedTest.kt',DEVICE+'reader/ReaderDirectionInstrumentedTest.kt',UNIT+'reader/ReaderInputTest.kt',DEVICE+'reader/ReaderKeyboardInstrumentedTest.kt'],
       ['Compose paging/scrolling, Android image decoding and Chromium layout differ from UIKit/Texture/WebKit; no pixel-identical UI claim.'],
       ['Latest SDK35 reader gallery/direction/keyboard regressions passed individually and in combination; full dual-device suite and additional actual local/EPUB UI interactions remain pending.'])
module('text',['reader/TextPaginator.kt','reader/ReaderMarkdown.kt','reader/ReaderFonts.kt','reader/ReaderTextSources.kt','ui/ReaderScreen.kt','reader/ReaderOptions.kt'],
       'Actual StaticLayout text/image-span measurement, lossless character partition, CommonMark formatting/links/images/tables, real system fonts, exact source-description spans, measured viewport-based scroll page counts and EPUB HTML/resource/chapter links.',
       [UNIT+'data/ReaderArchiveTest.kt'],['Android text shaping/line metrics/font families differ from CoreText; normalized progress and character positions preserve reading location.'])
module('image_processing',['reader/PageTransformation.kt','reader/CropBordersProcessor.kt','reader/DownsamplePageTransformation.kt','reader/PageBackground.kt','reader/AiUpscaleTransformation.kt','sources/WasmSourceRuntime.kt','ui/Components.kt'],
       'Real page/cover request modification and image processing; original full-pixel decode, JPEG-quality-zero/0.4 full-image black-white-alpha crop scan with inclusive final sample cells, downsample-before-AI precedence and processed-image split order; automatic border-derived backgrounds and ONNX AI.',
       [UNIT+'data/ReaderArchiveTest.kt',UNIT+'reader/CropBordersTest.kt',DEVICE+'reader/OriginalUpscaleModelsTest.kt'],
       ['JPEG and bitmap thumbnail kernels can produce different boundary pixels from ImageIO.'],
       ['Full sampled-image black/white/alpha scan migration and inclusive final-pixel repair are implemented in both platforms. Four native Android crop regressions passed; the two additional Swift crop methods require Xcode execution.'])
module('upscale',['reader/UpscaleModels.kt','reader/UpscaleGeometry.kt','reader/UpscaleModelCatalog.kt','reader/UpscaleModelScreens.kt','reader/AiUpscaleTransformation.kt'],
       'Original waifu2x/RealESRGAN FP16 weights execute ONNX; custom NN/MIL/quantized conversion, finite named model ZIP import, cosine overlap and positive integer tile scale. ImageFeature uses whole-image scaleFill/fixed rectangular-gray-RGB-BGR geometry. Official catalog refresh/cache/version and actual source download/export use a complete metadata bundle and Windows conversion/import workflow.',
       [UNIT+'reader/UpscaleGeometryTest.kt',UNIT+'reader/UpscaleCatalogTest.kt',DEVICE+'reader/OriginalUpscaleModelsTest.kt',DEVICE+'reader/CustomModelImportInstrumentedTest.kt','android/verification/original-ai-models.txt','android/model-conversion/test_convert_model.py','android/verification/model-converter/official-graph-comparison.json','android/verification/model-converter/image-fixtures/verification.json','android/verification/model-converter/catalog-live.log'],
       ['FP32 inference preserves stored FP16 weight values; intermediate Apple FP16 rounding may differ. Android bitmap scaleFill and color conversion may differ from Vision color management. Windows cannot execute Apple Core ML for direct pixel comparison.'],
       ['Converter has 82 passing host tests (61 numerical/converter and 21 catalog/contract) and exact full-tensor official comparisons; latest custom import/overlap/rectangular-gray/5x device fixtures remain pending. Public operator parameter guards are documented coverage, not platform impossibility.'])
module('downloads',['downloads/DownloadService.kt','downloads/DownloadConnectivity.kt','downloads/ArchivePageStore.kt','downloads/ChapterExporter.kt','ui/DownloadScreens.kt'],
       'Durable queue, bounded concurrent requests, pause/resume/retry/cancel, atomic staging/promotion, true Wi-Fi and per-job override, compression, CBZ export, migration rollback and deletion.',
       [UNIT+'data/DownloadLifecycleTest.kt',UNIT+'data/ReaderArchiveTest.kt'],
       ['WorkManager/foreground-service scheduling replaces BGContinuedProcessingTask; OS background execution windows differ.'])
module('local',['data/ArchiveImporter.kt','local/ArchiveXml.kt','local/LocalLibrary.kt','local/LocalFileNameParser.kt','local/LocalFileNamePatterns.kt','ui/MangaScreens.kt','downloads/ChapterExporter.kt'],
       'Safe UUID storage, public XML-pull DTD rejection plus portable DOM metadata, ComicInfo precedence and full original ordered filename parser with runtime-only Unicode flag capability probing (series/volume/chapter/ranges/part/multilingual/release tags), natural sorting, folder chapters, original 1-based ordinal descriptions before stem fallback scoped per chapter, ZIP/CBZ/text/EPUB assets and reference-aware append/edit/cover/export/delete.',
       [UNIT+'data/ReaderArchiveTest.kt',UNIT+'data/LocalFileNameParserTest.kt',DEVICE+'reader/LocalArchiveInstrumentedTest.kt','android/app/src/test/resources/local-filename-cases.json','android/scripts/import-local-filename-parser.py',UNIT+'data/MigrationTest.kt','android/verification/ui-fixtures/fixture-manifest.json'],
       ['Android SAF/content/file URIs replace iOS document picker/container URLs; EPUB HTML/resources are an additional native import path.'])
module('manga',['ui/MangaScreens.kt','reader/ChapterListing.kt','data/AppRepository.kt'],
       'Atomic metadata/category/bookmark edits, original chapter flag bitmasks, language/scanlator/title modes, recent unfinished resume, locked/download states, orphan downloads, local actions and links.',
       [UNIT+'data/ReaderArchiveTest.kt'],['Compose menus/dialogs replace UIKit/context menus; original labels and metadata are retained.'])
module('library',['library/LibraryRules.kt','library/BackgroundLibrary.kt','library/LibraryRefreshPolicy.kt','library/CategoryMaintenance.kt','library/UpdateRecords.kt','ui/LibraryWorkbenchScreens.kt','ui/MonogatariApp.kt','ui/Components.kt','ui/MangaCollectionSelection.kt','ui/CallerContextPopup.kt','data/AppRepository.kt','data/Models.kt'],
       'Categories/filter groups/layout/search/pinning/locks/updates, transactional merge and background/foreground library refresh. Multi-selection retains the original grid/list layout and saves editing/selected IDs with their category scope across native tab switches, exposes cell selection markers, applies current-category batch actions and supports two-finger range selection/reversal with edge scrolling and fresh lock checks. Category and whole-library lock messages retain their distinct original keys. Three range unit methods and both complete native selection methods pass on SDK35 and SDK26 within the final 217-unit/41-device/35-device runs. Actual one/two-pointer MotionEvents verify scrolling/long press, grid/list geometry, checked states, tab restoration, selected-only batch edits, all/offscreen selection and fresh locks without replacing the real AppState. Fixture-only corrections refresh actual accessibility nodes, wait for stable asynchronous scroll results and establish true touch mode, hidden IME, empty/unfocused search and stable viewport; API26 transfers focus to the non-editable decor instead of repeatedly clearing the Compose owner. Actual screenshot/geometry artifacts include library-selection-input-ready, single-scroll, grid, list and autoscroll.',
       [UNIT+'library/LibraryInsightsTest.kt',UNIT+'library/MangaSelectionRangeTest.kt',DEVICE+'library/LibrarySelectionInstrumentedTest.kt','android/verification/final-native35.log','android/verification/final-native26-complete.log','android/verification/final-library-decor-device26.log'],
       ['Android biometric/device credential and WorkManager replace LocalAuthentication/BGTaskScheduler; public Compose pointer APIs implement the original two-finger selection interaction.'])
module('history',['ui/HistoryScreen.kt','data/AppRepository.kt','library/ReadingSessions.kt','library/HistoryRecords.kt'],
       'Reading history, orphan chapter records, explicit read/unread operations and distinct-visible-page foreground sessions.',
       [UNIT+'library/LibraryInsightsTest.kt',UNIT+'data/MigrationTest.kt'])
module('insights',['library/ReadingInsights.kt','library/ReadingSessions.kt','ui/InsightsScreen.kt'],
       'Reading-session streaks, distinct chapter heatmap, yearly/monthly completions, pages/series/hours and interactive charts.',
       [UNIT+'library/LibraryInsightsTest.kt'],['Compose chart drawing/animation replaces Swift Charts; metric rules use the original session entities.'])
module('migration',['library/MangaMigration.kt','library/SourceKeyMigration.kt','library/SourceMigrationJournal.kt','ui/MigrationScreen.kt','downloads/DownloadService.kt'],
       'Source/series migration, chapter/volume/history matching, atomic key swaps, dot/legacy settings and tracker bindings with filesystem rollback.',
       [UNIT+'data/MigrationTest.kt',UNIT+'data/DownloadLifecycleTest.kt',UNIT+'backup/CompleteBackupTest.kt'])
module('database',['data/AppDatabase.kt','data/AppRepository.kt','data/Models.kt','backup/IOSBackupCodec.kt'],
       'SQLite transactional native models and lossless original backup metadata instead of Core Data objects.',
       [UNIT+'backup/CompleteBackupTest.kt',UNIT+'data/MigrationTest.kt'],
       ['Core Data/NSPersistentCloudKitContainer cannot execute on Android. Existing private-container mirroring additionally needs owner-issued CloudKit API token, user auth and a schema adapter; those credentials are absent from this repository.'])
module('backup',['data/BackupCodec.kt','backup/IOSBackupCodec.kt','backup/CustomSourceCodec.kt','backup/PortableFiles.kt','backup/CompleteBackupManager.kt','backup/DataRestoreJournal.kt','ui/CompleteBackupsScreen.kt'],
       'Original binary/XML/JSON plist entities and unknown typed fields; source/settings/media/dictionary/model/EPUB/CBZ streaming ZIP, selective merge/replace, preview, rollback, managed/automatic backups and sharing.',
       [UNIT+'backup/CompleteBackupTest.kt',UNIT+'data/MigrationTest.kt'],['SAF/shareable local backups replace iCloud Drive URLs; public/private CloudKit access is separately authorization-dependent.'])
module('tracking',['tracking/TrackerApi.kt','tracking/TrackerOAuth.kt','tracking/TrackingModels.kt','tracking/TrackingRepository.kt','ui/TrackingScreens.kt'],
       'Eight original trackers, OAuth/PKCE/state/tokens, search/bind/status/score/offset/progress/dates, enhanced source synchronization, backup and durable retry.',
       [UNIT+'tracking/TrackingTest.kt',DEVICE+'dictionary/OcrInstrumentedTest.kt'],['Android Keystore/browser authentication replaces Keychain/ASWebAuthenticationSession; provider policy may forbid embedded login.'])
module('dictionary',['dictionary/DictionaryRepository.kt','dictionary/DictionaryModels.kt','dictionary/HoshiDeinflector.kt','dictionary/OcrEngine.kt','dictionary/OcrLanguages.kt','dictionary/ScriptOcr.kt','dictionary/OcrVerticalSegments.kt','dictionary/OcrLayout.kt','dictionary/DictionaryGlossary.kt','dictionary/AnkiService.kt','dictionary/AnkiContent.kt','dictionary/VocabularyMedia.kt','dictionary/VocabularyCloze.kt','dictionary/VocabularySource.kt','ui/DictionaryScreens.kt','ui/DictionaryPopupBackground.kt','ui/DictionaryGlossaryView.kt','ui/VocabularyDetails.kt'],
       'Yomitan/frequency/pitch/romaji, pinned HoshiDicts 889 rules/67 groups/2122 variants and seven preprocessors, POS-aware longest matching, structured ruby/table/CSS/image glossary, vocabulary detail relookup, protected UTF16 cloze/context editing, cached/orphan/source Read-at-page and ViewSeries recovery, original vocabulary identity/date/page conversion, media/Anki, original Auto/strict source-language policy with five bundled MLKit scripts and official offline Tesseract Arabic/Cyrillic/Greek/Hebrew/Thai plus independent Devanagari fallback, original glyph clustering, normalized tap hit, RTL symbol boxes/confidence and context crop. Dictionary/OCR/glossary regressions pass within the final 217-unit/SDK35 41-device/SDK26 35-device runs. Glossary scripts and external resource loads are blocked while user-initiated links remain handled.',
       [UNIT+'dictionary/DictionaryTest.kt',UNIT+'dictionary/HoshiLookupTest.kt',UNIT+'dictionary/OcrLayoutTest.kt',UNIT+'dictionary/OcrLanguageTest.kt',UNIT+'dictionary/DictionaryGlossaryTest.kt',UNIT+'dictionary/VocabularyDetailsTest.kt',DEVICE+'dictionary/OcrInstrumentedTest.kt',DEVICE+'dictionary/OcrAutomaticInstrumentedTest.kt',DEVICE+'dictionary/GlossaryInstrumentedTest.kt','android/verification/ocr-language-provenance.md','android/verification/ocr-verification.txt','android/scripts/import-hoshi-rules.mjs','android/verification/final-native35.log','android/verification/final-native26-complete.log'],
       ['MLKit/Tesseract recognition results differ from Apple Vision/Live Text models; script routes do not imply a separate language dictionary or Apple-runtime pixel comparison. Original HoshiDicts itself has TODO preprocessors which are not claimed as implemented. Dictionary.popupDisableTransparency controls both popup surfaces: public API31 Window background blur uses a floating translucent caller-safe dialog and a readable tint; WindowManager capability/power-save callbacks restore opaque backgrounds when disabled or unsupported. API26-30 have no public cross-window background blur. Listeners/background/attributes are restored on disposal, with no projection or graphics-setting changes. The blur integration passes compilation/lint; no enabled-hardware blur pixel comparison is claimed. Official contracts: https://developer.android.com/reference/android/view/Window#setBackgroundBlurRadius(int) and https://developer.android.com/reference/android/view/WindowManager#addCrossWindowBlurEnabledListener(java.util.function.Consumer).'])
module('source_runtime',['sources/WasmSourceRuntime.kt','sources/SourceHost.kt','sources/Postcard.kt','sources/SourceCanvas.kt','sources/SourceSession.kt'],
       'Real Chicory execution of legacy and pinned modern source ABIs, Postcard serialization, exact page kinds/context, HTML/JSON/network/store/defaults/canvas/logging and image processors.',
       [UNIT+'sources/SourceRuntimeTest.kt',DEVICE+'sources/SourceBrowserInstrumentedTest.kt'],['Java/Android native services replace Swift imports; source bytecode is actually executed.'])
module('source_browser',['sources/SourceBrowser.kt','sources/SourceWebProfiles.kt','sources/DeferredProfileCleanup.kt','sources/SourceContentRules.kt','sources/SourceOAuthSession.kt'],
       'Real Chromium DOM/modern JavaScript/async execution, isolated profiles, cookies/content rules/auth, actual fetch/XHR/form/sendBeacon body bridges, immediate temporary-data clearing and durable cold-process reclamation. All eight source device methods pass within the final SDK35 41/41 run; the two WebAssembly-only methods also pass within the final SDK26 35/35 selection.',
       [UNIT+'sources/DeferredProfileCleanupTest.kt',DEVICE+'sources/SourceBrowserInstrumentedTest.kt','android/verification/final-native35.log','android/verification/final-native26-complete.log','android/SOURCE_RUNTIME.md'],
       ['Chromium differs from WebKit; MULTI_PROFILE/DOCUMENT_START are provider capabilities. Current API26 Google69 lacks a compatible modern provider; inspected complete official r06 inputs require minSdk29, while the apparent minSdk26 artifact is only a stub. This is a provider input/signature/allowlist limitation, not an intrinsic Android26 code exclusion. Service-worker/internal network POST bodies are not exposed through WebResourceRequest; ordinary fetch/XHR/form/sendBeacon have real verified bridges. Chrome124 retains loaded profiles until process exit; the passing fixture verifies native metadata commit, a different process PID, a present residual, production cleanup and actual directory deletion.'])
module('source_packages',['sources/SourcePackages.kt','sources/SourceCompatibility.kt','sources/SourceSession.kt','data/SourceClient.kt','ui/SourceBrowseScreens.kt','ui/SourceRuntimeScreens.kt'],
       'Source lists/catalog/install/update/validation/version compatibility/key migration, language/settings/home/listings/search/deep links.',
       [UNIT+'sources/SourceRuntimeTest.kt',UNIT+'sources/NativeSourceBackendTest.kt'],['Unknown catalog/SDK metadata are preserved; protocol changes require source updates as on iOS.'])
module('source_ui',['ui/SourceRuntimeScreens.kt','ui/SourceBrowseScreens.kt','ui/CallerContextPopup.kt','sources/SourceSession.kt'],
       'Original source-home component API, listings, full filter kinds/saved searches, source details/settings/login/errors, retained ViewModel requests and immutable pagination snapshots. Dynamic settings observe preference revisions and pass current row-value/dependency snapshots into lazy content without resetting scroll state. All 30 source unit methods and eight source device methods pass within the final 217-unit/SDK35 41-device runs. The additional isolated SourceSettings method passes within both SDK35 41/41 and SDK26 35/35 runs: actual parent-Switch touches persist false/true/false and repaint native checked state for two seconds without changing AppState or control/scroll geometry. Caller-context localized metadata/editor/popups preserve original keys and storage scope. The fixture locates the real checkable parent through its decorative label, refreshes state and waits for stable touch bounds; fake-label nodes never substitute for the Switch. It records source-settings-toggle-before/on/off JSON/PNG artifacts without external network or modern-provider requirements.',
       [UNIT+'sources/NativeSourceBackendTest.kt',UNIT+'sources/SourceBrowseRequestsTest.kt',DEVICE+'sources/SourceBrowserInstrumentedTest.kt',DEVICE+'sources/SourceSettingsInstrumentedTest.kt','android/verification/final-native35.log','android/verification/final-native26-complete.log'],
       ['Compose carousels/grids/chips/menus replace SwiftUI/UIKit widgets.'])
module('built_in',['sources/BuiltInBackend.kt','sources/KavitaClient.kt','sources/SuwayomiClient.kt','data/SourceClient.kt'],
       'Komga/Kavita/Suwayomi home/listings/filter/settings/details/pages and original Basic/API-key/OIDC/JWT/cookie mechanisms.',
       [UNIT+'sources/NativeSourceBackendTest.kt'])
module('bookstore_custom',['ui/BookstoreScreens.kt','sources/CopySourceClient.kt','sources/CopySourceBackend.kt','data/SourceClient.kt'],
       'User-requested native Bookstore tab, independent recommendation/category/ranking/finished/topic/search interfaces backed by CopyManga JSON, with retained source management and common library/download adapters.',
       [UNIT+'sources/CopySourceClientTest.kt','android/verification/bookstore-source-review.json'],
       ['Android-specific user customization; the original iOS project is unchanged. Browse and reading use separate public JSON services; old mobile-route null/restriction diagnostics do not describe the working web data routes. Paid/locked chapters remain permission-dependent.'])
module('search',['ui/GlobalSearchScreen.kt','ui/SourceRuntimeScreens.kt','sources/SourceSession.kt','data/SettingsStore.kt'],
       'Debounced/cancelable per-source search, filters/saved queries/history, disabled source/language/rating settings and pagination.',
       [UNIT+'sources/SourceRuntimeTest.kt',UNIT+'sources/NativeSourceBackendTest.kt'])
module('logging',['sources/SourceLogStore.kt','ui/MaintenanceScreens.kt'],
       'Persistent level-aware logs, original HTTP log streaming, display/filter/export/clear.',[UNIT+'sources/SourceRuntimeTest.kt'])
module('maintenance',['library/Maintenance.kt','library/ResetRequest.kt','library/DeviceAuthentication.kt','ui/MaintenanceScreens.kt'],
       'Original maintenance actions, cache/history/settings/source reset, log export, license/links and device authentication.',
       [UNIT+'data/MigrationTest.kt'],['Reset semantics follow the original action boundaries; unrelated vocabulary/dictionary data are not implicitly erased.'])
module('common_ui',['ui/Components.kt','ui/Localization.kt','ui/PlatformText.kt','ui/MonogatariApp.kt'],
       'Shared cover/grid/badge/empty/progress/button/navigation components, 41 original language catalogs and original image assets.',
       ['android/scripts/import-ios-resources.mjs'],['Android Material/native fonts/icons replace Apple system fonts and SF Symbols; original repository artwork remains an asset.'])
module('native_utilities',['data/Models.kt','data/AppRepository.kt','data/SettingsStore.kt','library/ReadingInsights.kt','ui/PlatformText.kt','ui/Components.kt','sources/SourceHost.kt'],
       'Swift collection/string/date/error convenience extensions are replaced at call sites by Kotlin collections, Android text/JSON, Java date and platform primitives; their wrapper types are not required at runtime.',
       [UNIT+'library/LibraryInsightsTest.kt',UNIT+'data/ReaderArchiveTest.kt',UNIT+'sources/SourceRuntimeTest.kt'],
       ['Platform primitive and locale formatting behavior can differ; this maps the shared call-site modules rather than asserting each Swift extension exists as an Android method.'])

NEW_TESTS={'BackupValidationTests.swift','JSONAnyValueNumberTests.swift','CoverRecoveryTests.swift','PartialDownloadTests.swift','LocalImportSafetyTests.swift','DownloadCacheResetTests.swift','ChapterScanlatorPredicateTests.swift'}
def select(path):
    p=path.replace('\\','/')
    n=Path(p).name
    if p.startswith('MonogatariTests/'):
        if 'Backup' in n or 'JSONAny' in n:return 'backup'
        if 'Tracker' in n:return 'tracking'
        if 'ChapterScanlator' in n:return 'manga'
        if 'TextPaginator' in n:return 'text'
        if 'PageProcessor' in n or 'CoverRecovery' in n:return 'image_processing'
        if 'Download' in n:return 'downloads'
        if 'TestableSource' in n:return 'source_runtime'
        return 'local'
    if n=='CoverRecovery.swift':return 'image_processing'
    if '/Core/Upscaling/' in p or n=='UpscaleModelListView.swift':return 'upscale'
    if '/Core/Backup/' in p or '/Settings/Backups/' in p:return 'backup'
    if '/Core/Database/' in p:return 'database'
    if '/Core/Dictionary/' in p or '/Features/Dictionary/' in p or '/Settings/Dictionaries/' in p:return 'dictionary'
    if '/Core/Tracking/' in p or '/Manga/Tracking/' in p or n=='SettingsTrackingView.swift':return 'tracking'
    if '/Core/Downloads/' in p or '/Settings/Downloads/' in p or n=='DownloadQueueView.swift':return 'downloads'
    if '/BuiltIn/Local/' in p or n in ('LocalFileImportView.swift','LocalSetupView.swift'):return 'local'
    if '/BuiltIn/' in p or '/SourceSetup/' in p:return 'built_in'
    if '/Sources/Cloudflare/' in p or '/WebView/' in p or '/Extensions/WebKit/' in p:return 'source_browser'
    if '/Sources/Legacy/' in p or '/Library/Legacy/' in p or '/Extensions/AidokuRunner/' in p:return 'source_runtime'
    if '/Core/Sources/' in p:return 'source_packages'
    if '/Core/Logging/' in p or n=='LogViewController.swift':return 'logging'
    if '/Core/Settings/Reader/' in p or n in ('ReaderSettingsView.swift','TapZone.swift','TapZonesSelectView.swift'):return 'reader_settings'
    if '/Core/Settings/' in p:return 'settings'
    if '/Models/Insights/' in p or '/Settings/Insights/' in p:return 'insights'
    if '/Core/Library/' in p:
        if n.startswith('Chapter') or n in ('EditedKeys.swift','MangaInfo.swift'):return 'manga'
        if n=='HistoryManager.swift':return 'history'
        return 'library'
    if '/Features/Reader/Processing/' in p or n=='PageBackground.swift':return 'image_processing'
    if '/Features/Reader/Readers/Text/' in p or n=='MarkdownView.swift':return 'text'
    if '/Features/Reader/' in p:return 'reader'
    if '/Features/Manga/' in p:return 'manga'
    if '/Features/Library/' in p:return 'library'
    if '/Features/History/' in p:return 'history'
    if '/Features/Updates/' in p:return 'library'
    if '/Features/Migration/' in p:return 'migration'
    if '/Features/Search/' in p:return 'search'
    if '/Features/Source/' in p:return 'source_ui'
    if '/Features/Browse/' in p:return 'source_packages'
    if '/Features/Settings/' in p:return 'settings' if 'About' not in n else 'maintenance'
    if '/App/Common/Settings/' in p:return 'settings'
    if '/App/Common/Manga/' in p:return 'common_ui'
    if '/App/Navigation/' in p or '/App/Root/' in p or n in ('AppDelegate.swift','SceneDelegate.swift'):return 'app'
    if '/Extensions/LocalAuthentication/' in p:return 'maintenance'
    if '/Extensions/ZIPFoundation/' in p:return 'local'
    if '/Extensions/CoreData/' in p:return 'database'
    if '/Extensions/BackgroundTasks/' in p or n=='Reachability.swift':return 'downloads'
    if '/Extensions/Foundation/' in p:
        if n=='NSLocalizedString.swift':return 'common_ui'
        if n=='FileManager.swift':return 'local'
        if n=='URLSession.swift':return 'source_runtime'
        if n=='String+Color.swift':return 'image_processing'
        if n=='String+Highlight.swift':return 'common_ui'
        return 'native_utilities'
    if '/Core/Utilities/' in p:return 'native_utilities'
    return 'common_ui'

def verification_summary():
    evidence_path=ANDROID/'verification/final-verification.json'
    evidence=json.loads(evidence_path.read_text(encoding='utf-8')) if evidence_path.exists() else {'regressionsPassed':False,'status':'Final combined device verification remains pending.'}
    if evidence.get('sourceChangesPendingBuild'):
        evidence['historicalRegressionsPassed']=evidence.get('regressionsPassed',False)
        evidence['regressionsPassed']=False
        evidence['status']='The recorded APK passed its checks; subsequent source edits await the user-requested combined build.'
    evidence['scope']='Actual native Android build/regressions and recorded UI flows; not proof of identical behavior for every original Swift method or Apple pixel output.'
    evidence['current_declared_unit_test_methods']={p.stem:len(re.findall(r'@Test\b',p.read_text(encoding='utf-8-sig'))) for p in (ANDROID/'app/src/test/java').rglob('*Test.kt')}
    evidence['current_declared_device_test_methods']={p.stem:len(re.findall(r'@Test\b',p.read_text(encoding='utf-8-sig'))) for p in (ANDROID/'app/src/androidTest/java').rglob('*Test.kt')}
    return evidence

def build():
    verified=verification_summary()
    if verified.get('regressionsPassed'):
        for key,spec in MODULES.items():
            spec['verification_status']='native_android_build_and_recorded_regressions_verified'
            spec['pending_items']=[]
        MODULES['image_processing']['verification_limits']=['Additional Swift crop regressions require Xcode; Android results do not prove iOS execution.']
        MODULES['upscale']['verification_limits']=['Public NN/MIL dispatch coverage and parameter guards are documented in model-conversion/README.md and operator-coverage.json; not every future model or parameter combination is numerically verified.']
        MODULES['dictionary']['verification_limits']=['Window blur capability integration is built; manual transparent compositor pixels were not independently compared with Apple ultraThinMaterial.']
        for key in ('reader','local','text','library','dictionary','source_ui','upscale'):
            MODULES[key]['evidence'].extend(['android/verification/final-native35.log','android/verification/final-native26-complete.log'])
    files=sorted(p for directory in (ROOT/'Monogatari',ROOT/'MonogatariTests') for p in directory.rglob('*.swift'))
    result=[]
    for file in files:
        rel=file.relative_to(ROOT).as_posix();key=select(rel);data=file.read_text(encoding='utf-8-sig');spec=MODULES[key]
        android_files=[p for p in spec['android_files'] if (ROOT/p).exists()]
        assert android_files,(rel,key)
        tests=rel.startswith('MonogatariTests/')
        status='new_regression_test' if tests and file.name in NEW_TESTS else 'ios_test_with_android_behavioral_coverage' if tests else 'native_module_mapping'
        if file.name=='CoreDataManager.swift':status='native_sqlite_adapter_cloudkit_removed_at_user_request'
        if file.name in ('ImageModel.swift','ImageProcessingModel.swift','ModelManager.swift','MultiArrayModel.swift'):status='official_graphs_converted_custom_converter_with_explicit_coverage'
        if key=='native_utilities':status='native_primitives_and_call_site_mapping'
        result.append({'ios_file':rel,'module':key,'status':status,'source_sha256':hashlib.sha256(file.read_bytes()).hexdigest(),'source_lines':len(data.splitlines()),
            'declared_types':re.findall(r'\b(?:class|actor|struct|enum|protocol)\s+([A-Za-z_][A-Za-z0-9_]*)',data),
            'android_files':android_files,'summary':spec['summary'],'platform_differences':spec['platform_differences'],
            'pending_items':spec['pending_items'],'evidence':[p for p in spec['evidence'] if (ROOT/p).exists()],
            'verification_status':'ios_tests_not_executed_on_windows' if tests else spec['verification_status'], 'verification_limits':spec.get('verification_limits',[])})
    catalog=json.loads((ANDROID/'app/src/main/assets/ios/localizations.json').read_text(encoding='utf-8'))
    schema=json.loads((ANDROID/'app/src/main/assets/ios/settings-schema.json').read_text(encoding='utf-8'))
    defaults=json.loads((ANDROID/'app/src/main/assets/ios/defaults.json').read_text(encoding='utf-8'))
    matrix={'schema_version':1,'generated_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'scope':'Current complete Swift inventory. This mapping is not a declaration of pixel-identical UI or identical behavior of every Swift method.',
        'inventory':{'app_swift_files':sum(not p['ios_file'].startswith('MonogatariTests/') for p in result),'swift_test_support_files':sum(p['ios_file'].startswith('MonogatariTests/') for p in result),'total':len(result)},
        'assets':{'languages':len(catalog),'english_keys':len(catalog.get('en',{})),'defaults':len(defaults),'settings_schema_rows':len(schema),'image_files':len(list((ANDROID/'app/src/main/assets/ios/images').glob('*')))},
        'verification':verification_summary(),
        'platform_constraints':[{'feature':'original_cloudkit_private_container','reason':'Android iCloud synchronization was explicitly removed at user request. The original iOS implementation is retained.',
            'alternative':'Original .aib/JSON/XML/binary backups still exchange portable data; no Android iCloud menu or synchronization control is provided.',
            'primary_sources':['https://developer.apple.com/library/archive/documentation/DataManagement/Conceptual/CloudKitWebServicesReference/SettingUpWebServices.html','https://developer.apple.com/documentation/CloudKitJS']},
            {'feature':'apple_fonts_and_symbols','reason':'Android uses its licensed system fonts and native icon resources; Apple fonts are not bundled. SF Symbols have their own usage terms.',
             'primary_sources':['https://developer.apple.com/fonts/','https://developer.apple.com/design/human-interface-guidelines/sf-symbols']},
            {'feature':'ios_execution_verification','reason':'This Windows host has no Xcode/iOS runtime. Original Swift fixes/tests cannot be executed here; native Android checks are recorded separately.'}],
        'user_customizations':{'app_display_name':'物语','repository_url':'https://github.com/Teikaraku/monogatari-reader','launcher_icon':'White background with pink 漫; Android adaptive launcher icon.','removed_android_ui':['iCloud synchronization','manual chapter history migration','source list management','Discord','Ko-Fi','about build number'],'bookstore':'Native CopyManga JSON-backed pages with a compact toolbar and retained source management.','ios_ui_unchanged':True,
            'provider_limits':'Provider domains may change, and paid/locked chapters require account reading permissions. Public web detail/chapter/image JSON has been verified separately from historical failing mobile routes.'},
        'modules':MODULES,'files':result}
    destination=ANDROID/'MIGRATION_MATRIX.json';destination.write_text(json.dumps(matrix,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'inventory':matrix['inventory'],'assets':matrix['assets'],'output':str(destination)}))

if __name__=='__main__':build()
