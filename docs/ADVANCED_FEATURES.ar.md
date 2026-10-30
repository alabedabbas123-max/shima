# جلب مكتبة Shima ودمجها واستخدام الميزات الثماني

**الحالة في 30 أكتوبر 2026:** المستودع يحتوي الشيفرة ومشروع بناء AAR، وليس ملف AAR مبنياً مسبقاً. التعديلات في جلسة Arena لم تُرفع إلى GitHub تلقائياً؛ لا تفترض أن فرع `main` هناك يحتوي هذا الإصدار. يمكن تنزيل أرشيف مصادر SDK المرفق في المحادثة، أو تصدير ملفات مساحة العمل. يُستبعد من أرشيف المصادر DEX والأرشيفات المرجعية الكبيرة لأنها ليست لازمة للبناء.

> هذه نسخة SDK مستقلة مستلهمة من فصل المكونات في AIDE، وليست محرك AIDE أو بديلاً مكتملاً عن Gradle و`javac` وKotlin compiler وAndroid Lint. الفحوص الدلالية والموارد والتنزيل **محدودة كما هو موضح أدناه**. لم يُبنَ AAR أو يُشغّل على جهاز Android في بيئة العمل الحالية؛ اختبارات المضيف وواجهات API 21 لا تثبت نجاح البناء أو الأداء على جهاز.

## 1) الحصول على AAR وبناؤه

1. نزّل أرشيف المصادر المرفق في المحادثة، وفكّه. بدلاً من ذلك، بعد أن يرفع مالك المستودع هذا التغيير، يمكن استنساخ الفرع الذي يحتويه. `git clone` للفرع `main` الحالي **لا يجلب التعديلات غير المرفوعة**.
2. ثبّت JDK 17 وAndroid command-line tools، واضبط `ANDROID_HOME`، ثم نزّل الأدوات التالية. أول بناء يحتاج وصولاً إلى Gradle وGoogle Maven:

```bash
sdkmanager 'platforms;android-35' 'build-tools;34.0.0' \
  'ndk;25.1.8937393' 'cmake;3.22.1'
cd shima-sdk-source                 # أو مجلد المصادر بعد فك الأرشيف
chmod +x gradlew scripts/*.sh       # على Linux/macOS عند الحاجة
./gradlew :analyzer:assembleRelease
./scripts/verify-aar.sh
./gradlew :sample:assembleDebug      # اختياري: تطبيق يستهلك AAR كملف خارجي
```

على Windows استخدم `gradlew.bat :analyzer:assembleRelease`. بعد نجاح البناء يكون المسار:

```text
analyzer/build/outputs/aar/analyzer-release.aar
```

تحتاج ملفات `third_party/` المرفقة عند البناء؛ مصدر Tree-sitter المثبّت يُفك محلياً من الأرشيف ولا يُجلب وقت البناء. النسخة مهيأة لـ`armeabi-v7a` و`arm64-v8a` و`x86` و`x86_64` و`minSdk 21`. لا تحذف ملفات `jni/` عند نقل AAR أو تهيئة ABI splits. يتضمن المشروع إشعارات تراخيص المكونات الخارجية وقواعد R8 للمكتبة، لكن **افحص AAR الناتج** بالأمر المذكور واختبر التطبيق المبني فعلياً.

3. انسخ الملف الناتج إلى `app/libs/analyzer-release.aar` في مشروعك. في `app/build.gradle.kts`:

```kotlin
android {
    defaultConfig { minSdk = 21 } // أو أعلى
}
dependencies {
    implementation(files("libs/analyzer-release.aar"))
}
```

وفي `app/build.gradle` بنمط Groovy:

```groovy
android { defaultConfig { minSdk 21 } }
dependencies { implementation files('libs/analyzer-release.aar') }
```

ثم نفّذ Gradle Sync وأعد بناء تطبيقك. إضافة الملف بصيغة AAR أفضل من نسخ `classes.jar` وحده لأن JNI يجب أن يكون معه. المكتبة لا تضيف Activity أو Service أو إذناً تلقائياً، ولا تشغّل خادماً.

## 2) ربط EditText وعرض الأخطاء

`CodeAnalyzer` يعمل بخيط خلفي، ويعيد callback على الخيط الرئيسي. صنف `EditTextSquiggles` اختياري: يرسم `ERROR` بالأحمر و`WARNING` بالأصفر، ولا يرسم `INFO`. يحتفظ المضيف بقائمة منفصلة لأخطاء `ERROR` حتى لا يدخل unused إلى لوحة الأخطاء الحرجة. المثال الكامل القابل للتعديل موجود في [`sample/.../MainActivity.java`](../sample/src/main/java/com/shima/analyzer/sample/MainActivity.java). هذه صيغة مختصرة بـKotlin؛ `status` هنا TextView داخل واجهتك:

```kotlin
import android.text.Editable
import android.text.TextWatcher
import com.shima.analyzer.AnalysisHandle
import com.shima.analyzer.CodeAnalyzer
import com.shima.analyzer.core.*
import com.shima.analyzer.ui.EditTextSquiggles

private var handle: AnalysisHandle? = null
private var squiggles: EditTextSquiggles? = null
private var latest: List<Diagnostic> = emptyList()

// بعد إنشاء editor وstatus، على main thread:
fun connectEditor(editor: android.widget.EditText, status: android.widget.TextView) {
    squiggles = EditTextSquiggles(editor)
    editor.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            latest = emptyList() // لا تعرض إجراءً قديماً بعد تغيير النص
        }
        override fun afterTextChanged(s: Editable?) {}
    })
    val workspace = WorkspaceSnapshot.builder()
        .namespace("com.example.app")
        .resourceIndexComplete(false) // غيّرها فقط بعد تزويد كل الموارد فعلاً
        .build()
    handle = CodeAnalyzer.with(editor.context)
        .attachTo(editor)
        .sourceId("Main.java")
        .language(Language.JAVA) // Language.KOTLIN أو Language.XML حسب الملف
        .workspace(workspace)
        .debounceMillis(250)
        .onResult { result ->
            latest = result.diagnostics
            squiggles?.setDiagnostics(latest)
            val errors = latest.filter { it.severity == Diagnostic.Severity.ERROR }
            val warnings = latest.filter { it.severity == Diagnostic.Severity.WARNING }
            status.text = "${result.status}: ${errors.size} errors, ${warnings.size} warnings"
            // اعرض errors في لوحة الأخطاء وwarnings في لوحة التحذيرات.
        }
        .onError { error -> status.text = "Analysis failed: ${error.message}" }
        .start()
}

// في Activity.onDestroy أو Fragment.onDestroyView، على main thread:
fun disconnectEditor() {
    handle?.close(); handle = null
    squiggles?.close(); squiggles = null
    latest = emptyList()
}
```

اربط `TextWatcher` الخاص بالتطبيق بدورة حياة الواجهة وأزله أيضاً عند الفصل إذا كان المحرر سيبقى حياً. استخدام `Language.XML` مع workspace يحتاج `sourceId` من نمط `res/layout/main.xml`؛ تضع الجلسة لقطة المحرر تلقائياً مكان هذا المورد. للمحرر المخصص نفّذ `EditorAdapter` واستعمل نظام رسم محررك بدلاً من `EditTextSquiggles`؛ انظر [دليل المحرر المخصص](INTEGRATION.ar.md#4-محرر-مخصص). الرسم المرفق تقريبي، وقد يحتاج محررك إلى معالجة أفضل لـRTL والخطوط المعقدة وscroll؛ اختبره على الجهاز.

لعرض Toast وقائمة الإصلاحات، استدعِ على main thread:

```java
// diagnostic من آخر onResult فقط؛ anchor زر أو المحرر.
DiagnosticActions.show(activity, anchor, handle, diagnostic, workspaceFixListener);
```

أو استعمل `DiagnosticActions.showAt(activity, editor, x, y, handle, diagnostics, workspaceFixListener)` من معالج اللمس الموجود لديك. يُظهر Toast برسالة التشخيص ثم قائمة Quick Fix إن توفرت؛ لا يستبدل المعالج حركة المحرر تلقائياً. مرّر `null` للمستمع إن كنت لا تريد عرض إصلاحات الملفات المتعددة. `handle.applyFix()` يتحقق من لقطة النص؛ إذا تغير النص يرمي `IllegalStateException` ويجب انتظار تحليل جديد. انظر مثال `sample/` للتعامل مع اللمس والقائمة.

## 3) تفاصيل كل ميزة وخوارزميتها

### 1. كشف التبعيات وتنزيل Maven بواجهة تقدم

- `BuildDependencyScanner.scan(text)` يستخرج صيغ GAV ثابتة مثل `implementation("g:a:1.2")` و`api 'g:a:1.2'` من Groovy أو Kotlin DSL. يرجع العبارات الديناميكية/المعروفة غير المدعومة في `getUnsupported()`. `isComplete()` يعني عدم اكتشاف تعبير غير مدعوم **في الحالات التي تعرفها الماسحة**، وليس إثبات اكتمال فهم Gradle أو الملف كله.
- `DependencyAudit.inspect(text, installer)` يفحص **التبعيات المباشرة الثابتة فقط** دون شبكة. يتحقق من POM والـJAR/AAR في الكاش وسياسة البصمة، ويعيد `getMissing()` و`getUnknown()` و`getIssues()`. وجود الملف المباشر لا يثبت أن كل التبعيات المتعدية موجودة.
- `MavenInstaller.install()` يقرأ POM وينزل `jar` أو `aar`، ويمر على subset من التبعيات المتعدية بإصدارات ثابتة. الإصدارات القديمة والحديثة مقبولة إن كانت GAV ثابتة والـPOM ضمن هذا subset؛ **ليس كل POM حديث أو قديم مدعوماً**. يعيد `Report.getFiles()` و`getIssues()` و`isCancelled()`؛ افحص أيضاً عبارات الماسحة غير المدعومة. `Report.isComplete()` لا يعني أن Gradle حُلّ كاملاً أو أن الـclasspath أُعدّ.
- واجهة `DependencyProgressDialog` تشبه اللقطة المرجعية: اسم الملف، مؤشر دوران، وشريط تقدم للملف الحالي؛ `CANCEL` يطلب إلغاء العملية و`HIDE` يخفي النافذة دون إيقافها. يمكن استدعاء `show()` مجدداً بعد الإخفاء. الأحداث من العامل تُجمع لتجنب إغراق طابور الواجهة. الإلغاء تعاوني وقد ينتظر قراءة الشبكة الحالية حتى تنتهي أو تنقضي مهلة الاتصال.
- افتراضياً يجب توفير SHA-256 موثوق **لكل** `group:artifact:version@pom` و`@jar` أو `@aar` بما فيها الملفات المتعدية. لا تثق ببصمة تُجلب من نفس المصدر غير الموثوق وحدها. يوجد HTTPS فقط، ولا تُتبع redirects، وتُحد أحجام POM والملفات والعدد والعمق والإجمالي. لا تنفّذ المكتبة plugins أو ملفات Gradle ولا تحمّل الكود المُنزّل.

أضف الإذن **في تطبيقك فقط** إذا أردت التنزيل:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

مثال يوضح واجهة API؛ `trustedSha256` يجب أن يزوّده تطبيقك من lockfile موثوق، و`worker` منفّذ يديره التطبيق:

```java
import com.shima.analyzer.dependencies.BuildDependencyScanner;
import com.shima.analyzer.dependencies.DependencyAudit;
import com.shima.analyzer.dependencies.MavenInstaller;
import com.shima.analyzer.ui.DependencyProgressDialog;

// داخل Activity/المضيف، على main thread؛ احتفظ بهذين الحقلين وأغلقهما في onDestroy.
MavenInstaller.Cancellation cancel = new MavenInstaller.Cancellation();
DependencyProgressDialog dialog = new DependencyProgressDialog(activity, cancel);
dialog.show();

worker.execute(() -> {
    try {
        MavenInstaller installer = new MavenInstaller(
            new java.io.File(activity.getCacheDir(), "shima-maven"),
            java.util.Arrays.asList("https://repo.maven.apache.org/maven2/",
                                    "https://dl.google.com/dl/android/maven2/"),
            trustedSha256);
        BuildDependencyScanner.Result scan = new BuildDependencyScanner().scan(gradleText);
        DependencyAudit.Result audit = new DependencyAudit().inspect(gradleText, installer);
        // audit.getMissing(): اعرضها للمستخدم. getUnknown()/getIssues(): لا تفترض الاكتمال.
        // مرر كل الإحداثيات المباشرة لا الناقصة فقط لإعادة فحص التبعيات المتعدية.
        MavenInstaller.Report report = installer.install(scan.getCoordinates(), cancel, dialog);
        activity.runOnUiThread(() -> {
            if (activity.isDestroyed()) return; // أغلق النافذة مسبقاً في onDestroy
            dialog.close();
            // اعرض scan.getUnsupported() وreport.getIssues() وreport.isCancelled().
            // report.getFiles() ملفات كاش فقط وليست classpath جاهزاً.
        });
    } catch (java.io.IOException | RuntimeException error) {
        activity.runOnUiThread(() -> {
            if (!activity.isDestroyed()) { dialog.close(); /* اعرض error للمستخدم */ }
        });
    }
});
// onDestroy على main: cancel.cancel(); dialog.close(); ثم أغلق worker الذي يملكه المضيف.
```

المثال يوضح دورة الطلب وليس خدمة تنزيل تستمر بعد إغلاق الشاشة؛ تجنب احتجاز Activity في عمل طويل ونسّق دورة الحياة في تطبيقك. حمّل على worker فقط، واستخدم كاشاً خاصاً بتطبيقك ومنسقاً بين النسخ المتزامنة من المثبّت. يمكن تعطيل اشتراط SHA عمداً عبر `new MavenInstaller.Options().requireSha256(false)` وتمريره مع `new MavenInstaller.HttpsTransport()` إلى المُنشئ الموسّع، **لكن هذا يقبل محتوى غير متحقق ويفضل تجنبه**.

الحدود المهمة: لا يدعم resolver كل properties/parent/BOM/`dependencyManagement`/profiles/exclusions/classifiers وGradle version catalogs والمشاريع المحلية أو SNAPSHOT والديناميكية؛ يبلغ عما يتعرف عليه ولا يعد graph مكتملاً. لا يفك AAR ولا يفهرس JAR ولا يضيف classpath أو يغيّر ملفات Gradle أو يعطي المحلل كل رموز المكتبات تلقائياً. على المضيف فهرسة الرموز من مصدر موثوق وتمرير الأنواع المطلوبة إلى `AnalysisConfig.Builder.addSymbol`، أو استخدام resolver/فهرس حقيقي منفصل؛ هذه الواجهة تقبل أسماء أنواع فقط وليست classpath كاملاً.

### 2. موارد Android و`R`

كوّن `WorkspaceSnapshot` من نسخ الملفات التي يديرها تطبيقك. يقرأ الفهرس أسماء الملفات تحت `res/drawable` و`layout` وغيرها، وتعريفات `values`، و`@+id` داخل XML. يفحص استعمال `R.type.name` في Java/Kotlin ومراجع XML المعروفة. مثال:

```java
WorkspaceSnapshot ws = WorkspaceSnapshot.builder()
    .namespace("com.example.app")
    .addSource(new SourceFile("src/Other.java", Language.JAVA, otherJavaText))
    .addResource(new ResourceFile("res/layout/main.xml", layoutXmlText))
    .addResource(new ResourceFile("res/drawable/icon.png", null)) // مورد ثنائي
    // true فقط إذا ضممت كل موارد variants والمكتبات ذات الصلة فعلاً:
    .resourceIndexComplete(true)
    .build();
// عند تغيير أي ملف خارج المحرر، كوّن لقطة محدثة ثم على main:
handle.updateWorkspace(ws);
```

إذا كان الفهرس ناقصاً أو ملف XML غير مكتمل، تُحجب أحكام `MISSING_RESOURCE` ويظهر `RESOURCE_INDEX_INCOMPLETE` مع `PARTIAL` عند وجود مرجع مدعوم؛ `WorkspaceResult.isResourceIndexComplete()` يبين الحالة عند الفحص المباشر. لا تجعل العلم `true` إلا عند تزويد الموارد اللازمة بما فيها موارد المكتبات/variants؛ وإلا يمكن أن تكون التشخيصات خاطئة. لا يُبنى `R` الحقيقي ولا يجري دمج AAPT أو حل كل qualifiers وnamespaces تلقائياً. عند غياب `id` يمكن اقتراح إنشائه أو إضافته إلى `res/values/ids.xml`، وليس في موضع `@+id` الأصلي. لا يوجد إنشاء تلقائي عام لـstring/layout/drawable.

إصلاح المورد عبر ملف آخر هو `WorkspaceFix`، ولا ينفذ كتابة بنفسه. `getGuardedIds()` يحدد الملفات الواجب فحصها، و`preview(map)` يتحقق من SHA-256 لكل لقطة ويعيد النصوص المتغيرة فقط؛ احذف مفتاح الملف غير الموجود من map ولا تضع له `null`. يجب على مخزن المشروع لدى المضيف قفل/فحص اللقطات مجدداً وتنفيذ transaction آمنة قبل الكتابة، ثم إرسال workspace جديد إلى `updateWorkspace`. إن تغير ملف أو ظهر الهدف الجديد قبل التنفيذ يُرفض الإصلاح. مثال sample يطبقها على map في الذاكرة فقط، وليس نظام ملفات إنتاجياً.

### 3. وراثة `BaseAdapter` وتوليد overrides

عند ثبوت `extends android.widget.BaseAdapter` في Java وعدم كون الصنف abstract، يفحص SDK الطرق الأربع `getCount`, `getItem`, `getItemId`, `getView`. يظهر `MISSING_ABSTRACT_METHODS` بوصفه `ERROR` على نطاق `extends`؛ يقدم `QuickFix` لإضافة stubs و`@Override` وimport لـ`View` و`ViewGroup` عندما يكون ذلك آمناً، ويستخدم أسماء مؤهلة عند احتمال تعارض الاستيراد. مثال الصنف قبل الإصلاح:

```java
import android.widget.BaseAdapter;
public class Abed extends BaseAdapter { }
```

اضغط التشخيص لاستدعاء Toast والقائمة السابقة. لا يوجد محلل شامل لهرم الوراثة أو كافة abstract classes وinterfaces والـclasspath؛ إذا كان التوقيع أو النوع غامضاً تُحجب بعض النتائج/الإصلاحات بدلاً من التخمين. لا تضمن stubs المولدة سلوكاً وظيفياً؛ عدّل الأجسام الافتراضية.

### 4. الدوال والمتغيرات والأنواع والـstubs

لـJava فقط، تفحص القاعدة المحافظة بعض استدعاءات **بلا معاملات** غير المعرفة في صنف معروف لا يملك `extends`/`implements` صريحاً. تستنتج نوع العودة من تعريف محلي مباشر مثل `String s = getStart();`، أو `void` للاستدعاء المستقل. تقترح method خاصة محلياً؛ ويمكنها اقتراح method عامة في صنف كائن محلي معروف أعلى الملف أو في ملف Java آخر مرئي من نفس الحزمة/استيراد صريح، مثل `abd.getName()`. إذا وُجد field مطابق `name` بنوع مناسب، قد تستخدم `return name;`، وإلا تعيد قيمة افتراضية. الإصلاح عبر ملف منفصل هو `WorkspaceFix` ويحتاج transaction المضيف.

هذه **ليست** تغطية عامة للمتغيرات المفقودة أو type inference أو الاستدعاءات بمعاملات/overloads/المراجع المعقدة، ولا تحليلاً دلالياً لـKotlin. لا تُصدر القاعدة تشخيصاً عندما يكون السياق غير آمن بحسب الحواجز المطبقة؛ وقد تفوت حالات أو تخطئ خارج subset. استدعِ compiler للتحقق الفعلي.

### 5. العناصر غير المستخدمة

تحذيرات `UNUSED_FIELD`, `UNUSED_METHOD`, `UNUSED_LOCAL`, `UNUSED_CLASS` من نوع `WARNING`، والخط الأصفر المرفق يرسمها منفصلة عن `ERROR`. نطاقها محدود بعناصر Java المحلية وحقول/طرق private والأصناف الداخلية private في المصدر المفحوص. لا يفهم الفحص reflection أو generated code أو الاستخدامات خارج المصدر؛ يمكن أن يفوّت تحذيرات أو يعطي تحذيراً رغم استخدام انعكاسي. لا يوجد فحص unused دلالي شامل لـKotlin.

### 6. سياسة `@Override`

غياب `@Override` **قانوني في Java وليس خطأ syntax**. عندما تثبت القاعدة المحدودة override لإحدى طرق `BaseAdapter`، يصدر `MISSING_OVERRIDE_ANNOTATION` تحذيراً مع إصلاح إضافة الوسم. لتحويله إلى `ERROR` بحسب سياسة مشروعك فقط:

```java
WorkspaceSnapshot ws = WorkspaceSnapshot.builder()
    .requireOverride(true)
    .build();
```

هذه السياسة ليست فحصاً شاملاً لكل هياكل الوراثة أو Kotlin، والخط الأحمر عند تفعيلها يعبر عن سياسة لا عن خطأ قواعد Java.

### 7. تعريفات مكررة

لـJava، تعرض `DUPLICATE_FIELD` و`DUPLICATE_METHOD` في **كل موضع** مكرر داخل الصنف. مفتاح method هو الاسم وأنواع المعاملات النصية المعروفة، وليس return type؛ اختلاف return type وحده لا يصنع overload صالحاً. لا تُعلَّم overloads مختلفة المعاملات المدعومة على أنها مكررة. المقارنة ليست حل تواقيع/erasure كاملاً، ولا تشمل جميع المتغيرات المحلية والـconstructors وKotlin؛ قد تفوت تكرارات بعد محو generics أو aliases.

### 8. XML للموارد

`Language.XML` أو `XmlAnalyzer` يفحصان well-formed XML عبر SAX، ويضعان `XML_SYNTAX_ERROR` عند موضع فشل المحلل، ويفحصان subset من قيم `android:layout_width`/`layout_height` مع نطاق قيمة السمة، إضافة لمراجع الموارد المدعومة في workspace. يُرفض DTD والكيانات الخارجية لأسباب أمنية. حدود الحجم/الأحداث والمهلة تعيد `PARTIAL`. ليس هذا AAPT2 ولا يفحص schema وكل attributes وnamespaces وdata binding أو كل الدلالات؛ إحداثيات أخطاء الصياغة تعتمد على موضع SAX التقريبي.

```java
XmlAnalyzer xml = new XmlAnalyzer();
AnalysisResult result = xml.analyze(new ResourceFile("res/layout/main.xml", xmlText));
// result.getDiagnostics()؛ أو حلّل WorkspaceSnapshot لفحص المراجع أيضاً.
```

## 4) الفحص والتشغيل والسرعة

- `Analyzer.analyzeWorkspace(ws)` متزامن ويجب تشغيله خارج main؛ يعيد خرائط `getSources()` و`getResources()`. احتفظ بالمحرك وأغلقه عند الانتهاء.
- Tree-sitter يعيد استخدام شجرة الملف المتغير وأشجار LRU؛ تُعاد القواعد الدلالية المحدودة على ملفات اللقطة، وتُعاد نتائج XML المكتملة غير المتغيرة ويُعاد بناء فهرس الموارد عند الحاجة. لا يُضمن فحص سطر واحد ولا يوجد رسم تبعيات دلالي كامل أو قياس تكافؤ مع AIDE. `PARTIAL` و`CANCELLED` لا تعنيان نجاح البرنامج؛ اقرأ أسباب الجزئية عبر `getIncompleteReasons()`.
- نطاقات التشخيص وإحداثيات التحرير UTF-16، البداية شاملة والنهاية مستبعدة؛ أرقام السطر والعمود تبدأ من 1. لا تطبق نتائج على لقطة تغيرت.
- على Linux مع JDK وC compiler، ثم Android SDK API 21، شغّل:

```bash
./scripts/test-generated-fixes.sh "$ANDROID_HOME/platforms/android-21/android.jar"
./scripts/check-api21.sh "$ANDROID_HOME/platforms/android-21/android.jar"
./gradlew :analyzer:assembleRelease :sample:assembleDebug
./scripts/verify-aar.sh
./gradlew :analyzer:connectedDebugAndroidTest # جهاز/محاكي يعمل
```

في هذه البيئة: نجحت اختبارات مضيف Linux مع JNI حقيقي وبدائل Android، وتُرجمت إصلاحات Java نموذجية ومصادر SDK ضد stubs خارجية API 21. لا يوجد بناء AAR فعلي أو تشغيل Android instrumentation أو قياس سرعة/ذاكرة؛ يجب إجراء هذه الخطوات في بيئة SDK/NDK كاملة قبل الاستخدام الإنتاجي.
