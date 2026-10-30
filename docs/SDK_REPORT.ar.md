# تقرير مكتبة Shima Code Analyzer

**التاريخ:** 31 أكتوبر 2026

**المخرجات:** مشروع مكتبة Android في [`analyzer/`](../analyzer/)، ومشروع مستهلك تجريبي في [`sample/`](../sample/)، وهذا الدليل.

> **الخلاصة المهمة:** الموجود هو أساس SDK مستقل ومحدود النطاق، مهيأ للبناء كـ AAR، وليس نسخة مطابقة لمحرك AIDE أو بديلاً عن مترجمي Java وKotlin وAndroid Lint. نجحت اختبارات المضيف، لكن **لم يُبنَ AAR ولم يُختبر على جهاز Android في هذه البيئة**. لا توجد هنا قياسات تثبت سرعة AIDE أو صغر الذاكرة مقارنة به.

## 1. نتيجة دراسة مراجع AIDE وDEX

فُحص [`abeed.zip`](https://github.com/alabedabbas123-max/Abbas/blob/main/abeed.zip) وأرشيف المرجع [`dex.zip`](https://github.com/alabedabbas123-max/shima/blob/main/dex.zip) (غير المرفق بأرشيف SDK):

- يحتوي المصدر المفكوك على `CodeAnalysisEngineService`، وهي خدمة Android مرتبطة بـBinder ودورة الحياة، وتنشئ `C0048c`. توجد نماذج مثل `EngineSolution` و`SyntaxError` و`Modification`، ومخزن `CharArray` يعيد استخدام المصفوفة عند الحاجة. استُلهم من ذلك **فصل المحرك وبيانات التشخيص والتعديل عن المحرر**، لا نسخ التطبيق.
- يعتمد `C0048c` على أصناف `abcd.*` المموّهة: يحوي أرشيف Java ملفين فقط ضمن `abcd/`، بينما يتضمن `classes.dex` تعريف 2,784 صنفاً بهذا النطاق و122 صنفاً تحت `com/aide/engine/`. لا يكفي أرشيف Java الظاهر لاستخراج محرك مكتمل مستقل.
- يحتوي `classes3.dex` على `com.sun.tools.javac.parser.JavacParser`. فُحصت تعليمات من `accept` و`reportSyntaxError` و`syntaxError`: يظهر فيها قبول الرمز المتوقع أو الإبلاغ عن الخطأ، والتعامل مع نهاية الملف وموضع الخطأ، وإنشاء عقدة `JCErroneous`. **لم يثبت الفحص الجزئي صلة هذا الصنف بمحرك التحرير التزايدي أو تطابق سلوكه مع المكتبة الجديدة.**
- لم يظهر في المراجع المفحوصة ترخيص يسمح بإعادة توزيع محرك AIDE؛ لذلك لم يُنقل كوده أو DEX إلى المكتبة. استُخدمت بدلاً منه قواعد Tree-sitter المرخصة بـMIT، مع إشعارات مهيأة للتضمين في AAR. ترخيص كود المشروع الجديد يحدده مالك المستودع.

أحجام DEX هي 9,043 و6,922 و3,516 صنفاً على الترتيب؛ هذه أعداد الأرشيفات كلها وليست أعداد أصناف محرك التحليل وحده. البصمات وطرق إعادة فحص bytecode في [سجل تدقيق المراجع](REFERENCE_AUDIT.ar.md).

## 2. ملخص الميزات والعيوب

| الجانب | المتاح فعلياً | العيب أو الحد |
|---|---|---|
| التغليف | وحدة Android Library مستقلة بواجهة Java قابلة للاستدعاء من Kotlin، وإعداد AAR مع JNI وقواعد R8. | البناء الفعلي ودمج AAR على Android لم يُتحققا هنا؛ النتيجة ليست جاهزة للإنتاج دون اختبارات جهاز. |
| ربط المحرر | `EditText` عبر `TextWatcher`، أو محرر يطبق `EditorAdapter`؛ تتوفر طبقة اختيارية للخط المتعرج وToast والقوائم. | المضيف يربط الأحداث ودورة الحياة ويغلق الجلسة، وقد يحتاج الرسم إلى تحسين RTL واختبار جهاز؛ المحرر المخصص يرسم بنفسه. |
| الفحص اللحظي | Debounce افتراضي 250ms، وخيط عمل واحد، وإلغاء تعاوني، ومنع نتائج قديمة. | التنفيذ يستخدم `Handler` و`Executor` في Java، لا Coroutines؛ الإلغاء والمهلة ليسا حداً زمنياً قاطعاً. |
| Java وKotlin | أشجار syntax عبر Tree-sitter وإظهار أخطاء `ERROR` و`MISSING` وبعض الإصلاحات. | المحلل متسامح مع الأخطاء، وقد يفوّت أو يسيء تصنيف بعض تراكيب اللغة؛ ليس مترجماً كاملاً. |
| فحص المشروع والتزايد | لقطات workspace وأنواع المشروع والموارد؛ إعادة استعمال شجرة الملف المتغير وأشجار LRU وفهرس الموارد غير المتغيرة. | لا يقرأ ملفات القرص تلقائياً؛ لا يوجد classpath أو رسم اعتماديات دلالي تزايدي كامل، ولا ضمان لفحص سطر واحد. |
| Maven وGradle | مسح إحداثيات ثابتة، تنزيل HTTPS محدود وواجهة تقدم، وفهرسة اختيارية لأسماء الأنواع العامة العليا من JAR/AAR المحددة لمرشحي imports. | subset فقط؛ لا يحل كل Gradle/POM أو يفهرس الاعتماديات المتعدية والأعضاء تلقائياً، ولا يبني classpath دلالياً كاملاً. |
| الموارد وXML | فهرس `R` محدود، إصلاح `id` عبر ملف محروس، فحص صياغة XML وsubset من القيم والمراجع. | يعتمد على اكتمال لقطات المضيف ولا يستبدل AAPT2؛ لا يولّد كل أنواع الموارد. |
| الأنواع وimports | فحص حرفيات واستيرادات، وتكرار members وunused و`BaseAdapter` وبعض stubs Java المحافظة. | لا يوجد حل شامل للأنواع أو الوراثة أو overloads أو nullability، ولا كشف عام لمتغير مفقود؛ قد تفوت الحالات أو تخطئ الاقتراحات. `@Override` سياسة، وليس syntax. |
| Android API | تحذير لعينة صغيرة من الأنواع والطرق الساكنة، وواجهة لإضافة بيانات API من المضيف. | لا توجد قاعدة شاملة أو تحديث تلقائي لأحدث الإصدارات؛ لا تحليل guards مثل `SDK_INT`، أو instance methods، أو الحقول، أو overloads. عدم التحذير لا يضمن التوافق. |
| Quick Fix | إدراج بعض الرموز الناقصة أو import، مع بصمة تمنع تطبيق إصلاح على نص تغير. | الإصلاحات اقتراحات محدودة وتتطلب قرار المستخدم؛ ليست نظام intentions شاملاً ولا تصحح كل الأخطاء. |
| الموارد والذاكرة | حدود للأحجام والأحداث والتشخيصات، وLRU محدود وحالة `PARTIAL`. | لا يمكن ضمان استحالة `OutOfMemoryError` أو التسرب. قد يفشل تخصيص الذاكرة الأصلية، وقد يسرب المضيف المحرر إذا أهمل `close()`. |
| الأداء والتوافق | أُعدت معماريات `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` والحد الأدنى المستهدف API 21. | لم يُقَس حجم AAR أو استهلاك الذاكرة أو زمن الاستجابة على Android؛ لم تختبر المعماريات الفعلية أو التكافؤ مع AIDE. |

إعداد البناء الحالي يستخدم `compileSdk 35` مع AGP `8.2.2` الذي يستهدف رسمياً حتى API 34، وقد كُتم تحذيره في `gradle.properties`. ينبغي اختبار البناء وترقية AGP وGradle معاً عند اعتماد نسخة أحدث؛ لا يعني ذلك أن قاعدة فحص APIs تغطي API 35 أو أحدث تلقائياً.

تفاصيل تنفيذ الميزات الثماني وكود جلبها ودمجها وقيودها في [دليل الميزات والدمج](ADVANCED_FEATURES.ar.md).

## 3. الهيكل الهندسي

```text
تطبيق Android المضيف
  EditText / محرر مخصص ينفذ EditorAdapter
       │ TextWatcher أو إشعار تغيير على main thread
       ▼
  CodeAnalyzer.with(context).attachTo(editor).start()
       │ AnalysisHandle: debounce + لقطة String + جيل الطلب
       ▼
  عامل خلفي واحد + إلغاء تعاوني + تجاهل النتائج القديمة
       ▼
  Analyzer: لا يعتمد على واجهة Android
       ├─ LRU محدود للنصوص وأحداث AST والأشجار الأصلية
       ├─ JNI → Tree-sitter Java / Kotlin → شجرة syntax تزايدية
       ├─ فهرس أسماء المشروع وموارد R + XML محدود
       ├─ قواعد Java وAndroid API محدودة
       └─ AnalysisResult → Diagnostic → QuickFix / WorkspaceFix
  أدوات مستقلة: مسح Gradle وتنزيل Maven مقيد + واجهة تقدم اختيارية
       ▼
  callback على main thread → عرض التشخيصات بواجهة المضيف
```

### المجلدات والمسؤوليات

| المسار | الوظيفة |
|---|---|
| `analyzer/src/main/java/com/shima/analyzer/` | الواجهة `CodeAnalyzer` وجلسة `AnalysisHandle`. |
| `analyzer/src/main/java/com/shima/analyzer/editor/` | عقدة `EditorAdapter` وتكييف `EditText`. |
| `analyzer/src/main/java/com/shima/analyzer/core/` | النواة، XML، النتائج، إعدادات وفهرس workspace والإصلاحات والكتالوج. |
| `analyzer/src/main/java/com/shima/analyzer/dependencies/` | ماسح Gradle، فحص كاش وتنزيل Maven محدود. |
| `analyzer/src/main/java/com/shima/analyzer/ui/` | واجهات اختيارية للتقدم، والخط المتعرج، وToast وقائمة الإصلاح. |
| `analyzer/src/main/cpp/` | جسر JNI وCMake، وتحويل أحداث AST إلى النواة. |
| `third_party/` | مصادر Tree-sitter المثبتة وترخيصها؛ لا يحتاج فكها إلى الشبكة وقت البناء. |
| `sample/`, `tests/host/`, `analyzer/src/androidTest/` | مثال مستهلك، واختبارات مضيف، واختبارات جهاز مهيأة. |
| `docs/` | التدقيق، والهيكل، ودليل الدمج وهذا التقرير. |

### تدفق التحليل وإدارة الذاكرة

1. عند التعديل يُلغى الطلب السابق، وبعد فترة الهدوء تؤخذ لقطة UTF-16 على الخيط الرئيسي ثم يُحلَّل الملف في الخلفية. الجيل يمنع نشر نتيجة نص أقدم بعد التعديل.
2. أول تحليل يبني الشجرة؛ لاحقاً يُحسب اختلاف النص وتُعدل نسخة شجرة Tree-sitter ثم يُعاد تحليل الأجزاء المتأثرة. تراعي حدود الاختلاف أزواج UTF-16؛ لا تعني العملية فحص سطر واحد فقط.
3. فحص المشروع يستقبل **لقطات** الملفات صراحة، ويجمع الأنواع، ثم يعيد حساب القواعد المعتمدة على فهرس المشروع. إذا كان الفهرس ناقصاً بسبب خطأ syntax أو حد موارد، تُحجب اقتراحات الاستيراد المبنية عليه.
4. يعيد `close()` فصل المستمع وإيقاف الجلسة وتحرير الأشجار بعد توقف العمل؛ الجلسة تحتفظ بالمحرر والمستمع حتى إغلاقها.

الحدود الافتراضية لكل `Analyzer`: 200,000 وحدة UTF-16 للملف، و2,000,000 وحدة/500 ملف للمشروع، و8 ملفات في LRU، و12,000 حدث AST، و100 تشخيص، ومهلة تعاونية 1,000ms. يمكن ضبطها عبر `AnalysisConfig`. هذه ميزانيات لتقليل الضغط وليست ضماناً بعدم نفاد الذاكرة أو انتهاء التنفيذ خلال المهلة. التفاصيل والحدود القصوى في [الهيكل الهندسي](ARCHITECTURE.ar.md).

### عقد النتائج والإصلاحات

- `Diagnostic`: رمز مثل `SYNTAX_ERROR` أو `POSSIBLE_MISSING_IMPORT`، ورسالة وشدة ونطاق وقائمة إصلاحات. الرسائل الحالية بالإنجليزية.
- نطاق النص `[startOffset, endOffset)` بوحدات UTF-16؛ الأسطر والأعمدة تبدأ من 1. الإصلاح مرتبط بلقطة النص التي أنتجته.
- `COMPLETE` يعني اكتمال **الفحوص المفعّلة ضمن حدودها**، لا أن المترجم سيقبل البرنامج. `PARTIAL` يعني أن التحليل أو فهرساً غير مكتمل؛ اقرأ `getIncompleteReasons()` فقد يجتمع نقص الموارد واقتطاع النتائج، ولا تختزل كل الأسباب في المهلة. `CANCELLED` يعني إلغاء الطلب. أوضاع الشجرة: `FULL`, `INCREMENTAL`, `CACHED`, `NONE`.
- `DiagnosticTracker` وأداة `EditTextSquiggles` يبقيان العلامات السابقة معلّقة بعد التعديل بأماكن تقريبية حتى النتيجة الحديثة، ويجب منع إصلاحاتها القديمة. التفاصيل والاختبارات وحدود ربط `sh129` في [تقرير الدقة](QUALITY_AND_ACCURACY.ar.md).
- الإصلاح لا ينفَّذ تلقائياً. `AnalysisHandle.applyFix(fix)` يتحقق من هوية الملف وبصمة النص ويرفض إصلاحاً قديماً. عند استخدام `fix.applyTo(text)` خارج واجهة المحرر، على المضيف أيضاً التحقق من هوية `fix.sourceId`.

## 4. بناء ملف AAR

### المتطلبات

- JDK 17 وAndroid SDK، واتصال إلى مستودعات Gradle وGoogle Maven لتنزيل أدوات البناء أول مرة.
- Platform API 35 وBuild Tools `34.0.0` وNDK `25.1.8937393` وCMake `3.22.1`. يستخدم المشروع Gradle Wrapper للإصدار `8.2` وAGP `8.2.2`.
- في التطبيق المستهلك: `minSdk` لا يقل عن 21. استهدف واختبر معماريات جهازك؛ لا تحذف مكتبات JNI عند النقل أو تفعيل ABI splits.

مثال Linux/macOS، بعد تثبيت Android command-line tools وضبط `ANDROID_HOME` و`PATH`:

```bash
sdkmanager 'platforms;android-35' 'build-tools;34.0.0' \
  'ndk;25.1.8937393' 'cmake;3.22.1'
./gradlew :analyzer:assembleRelease
./scripts/verify-aar.sh
# اختياري: تجميع تطبيق يستعمل AAR كملف خارجي
./gradlew :sample:assembleDebug
```

في Windows استعمل `gradlew.bat`. المسار المتوقع بعد نجاح البناء:

```text
analyzer/build/outputs/aar/analyzer-release.aar
```

يُفترض أن يضم `classes.jar` و`AndroidManifest.xml` و`jni/<abi>/libshima_analyzer.so` وقواعد R8 وإشعارات الترخيص؛ يفحص `verify-aar.sh` وجودها بعد البناء. لا يُخزَّن AAR مبني مسبقاً في المستودع، ولا تضيف المكتبة أذونات أو خدمة أو نشاطاً. الاعتماديات الظاهرة في Gradle مخصصة لاختبارات الجهاز، وليست اعتماديات تشغيلية يحتاج المضيف لإضافتها.

**حالة هذه الجلسة:** توقفت محاولة البناء عند تنزيل Gradle بسبب قيود شبكة البيئة، التي لا تحتوي Android SDK/NDK. الأوامر ومسار الناتج أعلاه تعليمات بناء، وليست إثباتاً بأن AAR موجود أو تم اختباره.

## 5. دمج AAR في تطبيق خارجي

بعد بناء AAR بنجاح، انسخه إلى `app/libs/analyzer-release.aar`:

```kotlin
// app/build.gradle.kts
android { defaultConfig { minSdk = 21 } } // أو أعلى
dependencies { implementation(files("libs/analyzer-release.aar")) }
```

أو في Groovy:

```groovy
dependencies { implementation files('libs/analyzer-release.aar') }
```

### مثال Kotlin مع EditText

```kotlin
import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import com.shima.analyzer.AnalysisHandle
import com.shima.analyzer.CodeAnalyzer
import com.shima.analyzer.core.AnalysisConfig
import com.shima.analyzer.core.Language
import com.shima.analyzer.core.QuickFix

class EditorActivity : Activity() {
    private var handle: AnalysisHandle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val editor = EditText(this)
        editor.setText("class Main { val count: Int = \"wrong\" }")
        setContentView(editor)

        handle = CodeAnalyzer.with(this)
            .attachTo(editor)
            .sourceId("src/Main.kt")
            .language(Language.KOTLIN)
            .config(AnalysisConfig.builder().minApi(21).compileApi(35).build())
            .debounceMillis(250)
            .onResult { result ->
                // استبدل السجل برسم النتائج في محررك.
                Log.d("Analyzer", "status=${result.status}")
                result.diagnostics.forEach { d ->
                    Log.d("Analyzer", "${d.range.startLine}: ${d.message}")
                }
            }
            .onError { error -> Log.e("Analyzer", "Analysis failed", error) }
            .start()
    }

    // يستدعيها التطبيق على main thread بعد اختيار المستخدم لإصلاح من التشخيص.
    private fun applyFix(fix: QuickFix) {
        try {
            handle?.applyFix(fix)
        } catch (stale: IllegalStateException) {
            Log.d("Analyzer", "Text changed; wait for a new analysis")
        }
    }

    override fun onDestroy() {
        handle?.close()
        handle = null
        super.onDestroy()
    }
}
```

`start()` و`applyFix()` و`close()` وإشعارات الـadapter يجب أن تكون على main thread؛ الـcallbacks عليه أيضاً. في Fragment أغلق الجلسة في `onDestroyView()`. للمدخل المختصر `CodeAnalyzer.with(context).attachTo(editor).start()` اللغة الافتراضية Java والمعرّف `editor.java`؛ أضف `onResult` لعرض النتائج. لا يحتفظ `with(context)` بالـActivity، لكن الجلسة تحتفظ بالمحرر حتى `close()`.

للمحرر المخصص، نفّذ `EditorAdapter.getText()` كلقطة مستقلة و`replace()` بإحداثيات UTF-16 و`subscribe()` مع إزالة المستمع في `Subscription.close()`؛ يجب أن يولد `replace()` إشعار تغيير. مثال قابل للتكييف وأمثلة Java في [دليل الدمج](INTEGRATION.ar.md).

### فحص مشروع في الخلفية

الواجهة الأساسية متزامنة؛ لا تستدعها على خيط الواجهة. المثال التالي يفحص اللقطات المقدمة فقط:

```java
import android.util.Log;
import com.shima.analyzer.core.*;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

ExecutorService worker = Executors.newSingleThreadExecutor();
worker.execute(() -> {
    AnalysisConfig config = AnalysisConfig.builder().minApi(21).compileApi(35).build();
    try (Analyzer engine = new Analyzer(config)) {
        ProjectResult project = engine.analyzeProject(Arrays.asList(
            new SourceFile("src/a/Widget.java", Language.JAVA,
                "package a; public class Widget {}"),
            new SourceFile("src/b/Main.kt", Language.KOTLIN,
                "package b\nclass Main { val widget: Widget? = null }")));
        for (Map.Entry<String, AnalysisResult> file : project.getFiles().entrySet()) {
            Log.d("Analyzer", file.getKey() + ": " + file.getValue().getStatus());
            // انقل نتائج العرض إلى main thread مع مراعاة دورة حياة الشاشة.
        }
    }
});
worker.shutdown(); // يسمح للمهمة المرسلة بالانتهاء
```

لاستفادة متكررة من LRU، احتفظ بالمحرك على عامل متسلسل بدلاً من إنشائه كل مرة، ثم أغلقه على العامل. يمكن استدعاء `engine.cancel()` من أي خيط. معرّفات الملفات يجب أن تكون ثابتة وفريدة؛ تكرارها أو تجاوز حد المشروع يرمي `IllegalArgumentException`.

### توسيع بيانات Android API اختيارياً

```kotlin
val config = AnalysisConfig.builder()
    .minApi(21).compileApi(35)
    .addAndroidApiType("android.graphics.RenderNode", 29, 0)
    .addAndroidApiStaticMethod("android.os.Trace", "isEnabled", 29, 0)
    .build()
```

الرقم الأخير إصدار الإهمال، أو صفر إذا لم تُقدَّم بيانات إهمال. المصادر التي يضيفها المضيف تتجاوز المدخلات المضمنة المطابقة، وهو مسؤول عن صحتها وترخيصها وتحديثها؛ لا تصبح بقية APIs مفحوصة تلقائياً. إضافة النوع تجعله مرشح import؛ إضافة method وحدها لا تفهرس مالكها. [الكتالوج المضمن والقيود](ARCHITECTURE.ar.md#كتالوج-android-المضمن).

## 6. الاختبارات المنفذة وما تبقى

| الاختبار | النتيجة في هذه الجلسة | ما لا يثبته |
|---|---|---|
| `scripts/test-host.sh` | **561 فحصاً نجحت** على Linux x86_64 بمحللي JNI الحقيقيين وبدائل اختبار لواجهة Android. | لا يشغّل Android أو يثبت الأداء على الأجهزة. |
| `scripts/test-sh129-integration.sh` | 20 فحص ربط مع JNI و20 بدونه، و60 فحص جودة، و15 دورة اعتماديات، و24 نقل، و25 للمحلل القديم، و3 نشر و16 فحص تركيب Java. | ليست بناء APK أو مجموعة QA الكاملة أو تجربة جهاز. |
| `scripts/check-api21.sh <android-21/android.jar>` | جُمعت مصادر Java مقابل stubs خارجية لـAPI 21. | لا يفحص JNI أو تشغيل الجهاز أو كل سلوك منصة Android. |
| `:analyzer:assembleRelease` | **لم يكتمل**: حُظر تنزيل Gradle ولا يوجد SDK/NDK في البيئة. | لا يوجد AAR مبني أو تحقق من محتوياته في هذه الجلسة. |
| `:analyzer:connectedDebugAndroidTest` | لم يُشغّل؛ يوجد اختبار مهيأ فقط. | لا دليل بعد على سلامة دورة الحياة والمعماريات على Android. |

للتحقق لديك، بعد بناء AAR شغّل `./scripts/verify-aar.sh`، ثم `./gradlew :analyzer:connectedDebugAndroidTest` على API 21 وإصدار Android حديث وعلى المعماريات المستهدفة. اختبر تطبيق `sample` كمستهلك فعلي. شغّل أيضاً `./scripts/test-host.sh`، و`./scripts/check-api21.sh "$ANDROID_HOME/platforms/android-21/android.jar"` بعد تثبيت تلك المنصة. إعداد CI في المستودع موجود لكنه لم يُنفّذ هنا.

## 7. خطوات التطوير اللازمة قبل الادعاء بالتكامل الكامل

1. الحصول على مصدر المحرك الأصلي وحقوق استخدامه وتوزيعه، أو الاستمرار في التنفيذ المستقل، ثم إجراء اختبارات مقارنة فعلية على المدخلات والسلوك، لا على أسماء الأصناف فقط.
2. إضافة classpath وفهرسة وحدات Gradle وحل الرموز والنطاقات والوراثة والأنواع لـJava وKotlin، مع اختبارات ضد المترجمات؛ هذا مطلوب أيضاً لإصلاح المتغيرات المفقودة بثقة.
3. توفير بيانات Android مرخصة ومحدثة حسب الإصدار والتوقيع، وتحليل guards وinstance methods والحقول والإهمال؛ إضافة APIs يدوياً وحدها لا تحقق تغطية شاملة.
4. بناء AAR وتشغيله على Android API 21 وإصدارات حديثة وكل ABI المستهدف، مع اختبار الاستهلاك الخارجي وR8 ودورة الحياة والإلغاء والملفات الضخمة.
5. قياس حجم AAR وذاكرة الذروة ووقت الفحص الأول وp50/p95 للفحص التزايدي على أجهزة فعلية، ومقارنة AIDE على المدخلات والأجهزة نفسها. لا يمكن التعهد بتفوق الأداء أو استحالة OOM قبل ذلك.
