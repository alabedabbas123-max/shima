# دليل البناء والدمج

لشرح جلب نسخة الجلسة وكود workspace والموارد وMaven والواجهة الاختيارية والميزات الثماني وحدود كل منها، راجع [دليل الميزات والدمج المفصل](ADVANCED_FEATURES.ar.md).

## 1. بناء AAR واستهلاكه

افتح المستودع في Android Studio مع JDK 17؛ ثبّت SDK Platform 35 وNDK 25.1.8937393 وCMake 3.22.1 وBuild Tools 34.0.0. شغّل `./gradlew :analyzer:assembleRelease` (`gradlew.bat` في Windows). يقوم CMake بفك أرشيف المصادر المثبّت في `third_party/` محلياً وبناء المكتبات الأصلية؛ لا يحتاج إلى تنزيل قواعد Tree-sitter أثناء البناء. يتطلب تنزيل Gradle وAndroid Gradle Plugin/SDK اتصالاً بمستودعاتها في أول استخدام.

انسخ `analyzer/build/outputs/aar/analyzer-release.aar` إلى `app/libs/` ثم أضفه:

```kotlin
// Kotlin DSL
android { defaultConfig { minSdk = 21 } }
dependencies { implementation(files("libs/analyzer-release.aar")) }
```

```groovy
// Groovy DSL بدلاً من السطر السابق
dependencies { implementation files('libs/analyzer-release.aar') }
```

ليس ملف `.jar` بديلاً عن AAR؛ يجب شحن مجلدات `jni/<abi>/libshima_analyzer.so` التي يحتويها. إذا فعّل المضيف ABI splits أو `abiFilters`، عليه تضمين المعماريات المستهدفة فعلاً. يتضمن AAR قواعد R8 اللازمة لأسماء JNI وإشعارات تراخيص المكونات ضمن `assets/shima-analyzer/`. لا يضيف أذونات أو خدمة Android أو نشاطاً. مشروع [`sample`](../sample/) يستهلك AAR مباشرة، وليس `project(':analyzer')`.

## 2. محرر EditText بلغة Kotlin

الاستيرادات:

```kotlin
import com.shima.analyzer.AnalysisHandle
import com.shima.analyzer.CodeAnalyzer
import com.shima.analyzer.core.AnalysisConfig
import com.shima.analyzer.core.AnalysisResult
import com.shima.analyzer.core.Diagnostic
import com.shima.analyzer.core.Language
import com.shima.analyzer.core.QuickFix
```

```kotlin
private var handle: AnalysisHandle? = null
private var lastResult: AnalysisResult? = null

private fun attach(editor: android.widget.EditText) { // main thread
    val config = AnalysisConfig.builder()
        .minApi(21)             // minSdk للمشروع الجاري تحليله، لا للجهاز الحالي
        .compileApi(35)         // يستخدم لتحديد تحذيرات deprecation المعروفة
        .timeoutMillis(1000)
        .maxCachedFiles(8)
        .build()
    handle = CodeAnalyzer.with(this)
        .attachTo(editor)
        .sourceId("src/Main.kt") // ثابت وفريد للملف
        .language(Language.KOTLIN)
        .config(config)
        .debounceMillis(250)
        .onResult { result ->
            lastResult = result
            // Status.COMPLETE لا يعني أن المترجم سيقبل الملف.
            renderDiagnostics(result.status, result.diagnostics)
        }
        .onError { error -> showAnalysisError(error) }
        .start()
}

private fun applyFix(fix: QuickFix) { // main thread؛ بعد اختيار المستخدم
    try {
        handle?.applyFix(fix)
    } catch (stale: IllegalStateException) {
        // تغيّر النص منذ الفحص؛ انتظر النتيجة الجديدة بدلاً من فرض الإصلاح.
    }
}

override fun onDestroy() {
    val oldHandle = handle
    handle = null // أزل مرجع المضيف قبل استدعاء أي كود قد يعيد الدخول
    oldHandle?.close()
    lastResult = null
    super.onDestroy()
}
```

دوال `renderDiagnostics` و`showAnalysisError` تنفذها في تطبيقك. الاستدعاءات `start()` و`requestAnalysis()` و`applyFix()` و`close()` كلها على الخيط الرئيسي؛ النتائج والأخطاء كذلك. في Fragment استخدم `onDestroyView()` لا `onDestroy()` إذا كان المحرر مربوطاً بالـView. لا يخزن `with(context)` الـActivity، لكن الجلسة تحتفظ بالمحرر والمستمع حتى `close()`، فلا تهمل إغلاقها. `onResult` اختياري؛ من دونه يشتغل التحليل ولكن لا تُعرض نتائجه تلقائياً.

## 3. Java

```java
import com.shima.analyzer.AnalysisHandle;
import com.shima.analyzer.CodeAnalyzer;
import com.shima.analyzer.core.Language;

// مثلاً في onCreate، على الخيط الرئيسي
handle = CodeAnalyzer.with(this)
        .attachTo(editor)
        .sourceId("Main.java")
        .language(Language.JAVA)
        .debounceMillis(250)
        .onResult(result -> renderDiagnostics(result.getDiagnostics()))
        .onError(error -> showAnalysisError(error))
        .start();

// في onDestroy: أزل مرجع المضيف قبل فصل الاشتراك الذي قد يعيد الدخول
AnalysisHandle oldHandle = handle;
handle = null;
if (oldHandle != null) oldHandle.close();
```

`Diagnostic` يعطي `code`, `message`, `severity`, `range` و`quickFixes`. النطاق `[startOffset, endOffset)` بوحدات UTF-16 مثل `Editable.replace`؛ رقم السطر والعمود يبدأان من 1 ويقيس العمود وحدات UTF-16. التشخيصات والإصلاحات مرتبطة **بنفس لقطة النص** التي أنتجتها. يمكن للمضيف عرض `QuickFix.getTitle()` في القائمة، ثم `handle.applyFix(fix)` بعد الاختيار. في محرك بلا واجهة استخدم `fix.applyTo(originalText)` للحصول على نص جديد؛ يتحقق من البصمة أولاً. لا تطبّق إحداثيات إصلاح قديم بعد تحرير النص.

## 4. محرر مخصص

نفّذ `EditorAdapter` دون توريث `EditText`:

```java
EditorAdapter adapter = new EditorAdapter() {
    public String getText() { return myEditor.snapshotAsString(); }
    public void replace(int start, int end, String replacement) {
        myEditor.replaceRange(start, end, replacement);
    }
    public Subscription subscribe(final Runnable onChange) {
        // تكييف أسماء الدوال حسب مكتبة المحرر المستخدمة:
        myEditor.addDocumentListener(onChange);
        return new Subscription() {
            public void close() { myEditor.removeDocumentListener(onChange); }
        };
    }
};
AnalysisHandle handle = CodeAnalyzer.with(context).attachTo(adapter)
        .sourceId("Main.java").language(Language.JAVA)
        .onResult(result -> renderDiagnostics(result.getDiagnostics())).start();
```

على الـadapter إرسال إشعارات التعديل، وتنفيذ جميع دواله والإشعارات على الخيط الرئيسي، وإرجاع نسخة نصية مستقلة، والتعامل مع إحداثيات UTF-16. `replace` يجب أن يولّد إشعار تغيير كالمعتاد. إن كان محررك يصدر الأحداث من خيط آخر، انقلها إلى الـmain thread في الـadapter. أسماء `myEditor` أعلاه إرشادية وليست جزءاً من SDK.

### منع حلقة التشخيصات وتركيز المحرر

استدعاء `AnalysisHandle.close()` يعلّم جلسة SDK مغلقة قبل فصل الاشتراك، ولا يستدعي `onResult` مباشرةً. لكن تنفيذ `Subscription.close()` في الـadapter ملك للمضيف، وقد يستدعي كود التطبيق بصورة متزامنة؛ وكذلك قد يسبّب عرض النتائج أحداثاً من المحرر. لهذا لا تكفي حماية SDK وحدها لمنع حلقة في كود الربط:

- اجعل عرض التشخيصات أو مسحها عملية عرض فقط، لا إشعار تركيز جديداً. إن كان المحرر يبلّغ التركيز أثناء العرض، تحقّق من تغيّر المحرر/الملف الفعلي قبل إعادة ربط المحلل.
- عند إغلاق مشروع أو تبديل محرر، حدّث حالة المضيف وأزل مرجع الجلسة القديمة **قبل** استدعاء `close()` أو أي callback للمضيف؛ اجعل التحرير قابلاً للتكرار دون إعادة نشر التغيير نفسه. عالج إشعارات التركيز الحقيقية الجديدة بعد انتهاء الانتقال، ولا تتجاهلها كلها بحارس عام.
- اختبر فتح الشاشة دون مشروع، وإغلاق آخر ملف، وتكرار التركيز على الملف نفسه، والتبديل بين ملفين، ووقوع callback أثناء فصل الاشتراك. `Handler.post()` وحده قد يحوّل الاستدعاء المتكرر إلى حلقة رسائل بدلاً من إصلاحها؛ لا تعالج السبب بمجرد التقاط `StackOverflowError`.

التطبيق يملك حالة المشروع والمحرر وطريقة ربط الأحداث؛ يجب تكييف هذه القواعد مع كوده، ولا يعني اختبار SDK وحده أن دورة أحداث التطبيق خالية من إعادة الدخول.

## 5. فحص لقطة مشروع دون واجهة

هذه الواجهة **متزامنة**؛ استدعها على executor أو `Dispatchers.Default` في تطبيق Kotlin، وليس على الخيط الرئيسي. احتفظ بالمحرك بين الطلبات للاستفادة من LRU؛ يجب تسلسل الطلبات، وتحريره على خيط العمل عند الانتهاء:

```java
AnalysisConfig config = AnalysisConfig.builder()
        .minApi(23).compileApi(35)
        .addSymbol("com.example.library.Widget") // أنواع معروفة من الاعتماديات
        .build();
Analyzer projectEngine = new Analyzer(config);
// على خيط خلفي:
ProjectResult project = projectEngine.analyzeProject(Arrays.asList(
        new SourceFile("src/a/Widget.java", Language.JAVA, javaSource),
        new SourceFile("src/b/Screen.kt", Language.KOTLIN, kotlinSource)));
for (Map.Entry<String, AnalysisResult> entry : project.getFiles().entrySet()) {
    // سلّم نسخة النتائج إلى واجهة المستخدم على main thread إن كانت الشاشة ما تزال حية.
}
// عند إنهاء المشروع: projectEngine.cancel() من أي خيط، ثم projectEngine.close() على خيط العمل.
```

أضف imports لـ`Analyzer`, `AnalysisConfig`, `ProjectResult`, `AnalysisResult`, `SourceFile`, `Language` من `com.shima.analyzer.core`، و`java.util.Arrays` و`java.util.Map`. القائمة يجب أن تضم لقطات جميع الملفات المراد تضمينها، ومعرّفات فريدة وثابتة. المكرر أو تجاوز حد المشروع يرمي `IllegalArgumentException`. الملف الأكبر من حد الملف يعيد `PARTIAL`. إذا تعذر إكمال فهرسة ملف أو كان به خطأ نحوي، تُحجب اقتراحات الاستيراد المعتمدة على الفهرس ويُبلّغ `PROJECT_INDEX_INCOMPLETE`. لا تُقرأ ملفات القرص أو Gradle تلقائياً.

يمكن للمضيف توسيع بيانات Android المحدودة بمصدر محدث ومرخّص؛ البيانات الإضافية تتجاوز المدخل المضمن إن تطابق الاسم:

```java
AnalysisConfig config = AnalysisConfig.builder()
        .minApi(21).compileApi(35)
        .addAndroidApiType("android.graphics.RenderNode", 29, 0)
        .addAndroidApiStaticMethod("android.os.Trace", "isEnabled", 29, 0)
        .build();
```

المعامل الأخير نسخة الإهمال، أو صفر إذا لم تُقدَّم. الإضافة لا تفحص تلقائياً بقية SDK أو signatures أو instance methods، ولا تتحقق من `SDK_INT`. نوع API المضاف يصبح مرشح import، لكن إضافة method وحدها لا تضيف مالكها للفهرس. راجع [حدود الكتالوج](ARCHITECTURE.ar.md#كتالوج-android-المضمن).

## 6. الحالات والأخطاء

- `COMPLETE`: انتهت الآلية المفعّلة ضمن الحدود، وليس ضماناً لصحة البرنامج. تُوقف القواعد الدلالية عندما تظهر أخطاء بناء في الملف لتقليل الإيجابيات الخاطئة.
- `PARTIAL`: مهلة أو تجاوز حدود/اقتطاع أو فهرس مشروع غير مكتمل؛ اقرأ التشخيص المعلوماتي، ولا تعتبر غياب الأخطاء نجاحاً.
- `CANCELLED`: أُلغي الطلب؛ جلسة المحرر لا تنشره. واجهة المشروع تعيد نتائج `CANCELLED` للقائمة كلها عند الإلغاء.
- `FULL`, `INCREMENTAL`, `CACHED`, `NONE`: طريقة الوصول إلى الشجرة لهذه النتيجة؛ التحليل الدلالي المحدود يُعاد عند الحاجة حتى عندما تكون الشجرة `CACHED`.

الاستثناءات غير المتوقعة و`LinkageError` تصل إلى `onError` في الجلسة، أو إلى مستدعي المحرك المتزامن. حد الموارد يقلل احتمالات الضغط ولا يضمن عدم حدوث OOM أو تسرب بسبب المضيف.

## 7. الاختبارات والتحقق على الجهاز

لاختبار النواة وJNI على Linux مع JDK وC compiler، والتحقق الإضافي من واجهات API 21 بعد تثبيت تلك المنصة:

```bash
sdkmanager 'platforms;android-21'
./scripts/test-generated-fixes.sh "$ANDROID_HOME/platforms/android-21/android.jar"
./scripts/check-api21.sh "$ANDROID_HOME/platforms/android-21/android.jar"
```

يوجد `AnalyzerInstrumentedTest` لاختبار مكتبة JNI و`EditText` على Android. بعد تشغيل emulator أو توصيل جهاز:

```bash
./gradlew :analyzer:connectedDebugAndroidTest
```

شغّله على API 21 وعلى إصدار حديث، وبالمعماريات التي يوزعها تطبيقك. تجميع الاختبار ليس بديلاً عن تشغيله على جهاز، ولا يثبت تكافؤ الأداء أو التغطية الكاملة. المستودع يتضمن إعداد CI لتجميع AAR وتطبيق المستهلك وتجميع اختبار الجهاز؛ تشغيل المحاكي يظل خطوة منفصلة.

**حالة التحقق في هذه البيئة:** نجحت اختبارات المضيف وتجميع Java ضد stubs API 21، لكن لم يُبْنَ AAR ولم يُشغّل Android emulator بسبب غياب SDK/NDK وحظر تنزيل Gradle؛ راجع [نتائج التحقق وحدودها](ARCHITECTURE.ar.md#التحقق-المنفذ-وحدوده).
