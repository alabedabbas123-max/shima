# Shima Code Analyzer SDK

مشروع مكتبة Android مستقلة مهيأ للبناء بصيغة **AAR** لتحليل ملفات Java وKotlin وموارد XML داخل المحرر، من دون ربط محرك التحليل بواجهة المستخدم. الحد الأدنى المستهدف Android 5.0 / API 21. يتضمن المستودع مشروع مستهلك بسيطاً في `sample/` مهيأً لاستهلاك ملف AAR كما يفعل تطبيق خارجي.

> **النطاق الحقيقي:** هذه قاعدة SDK عملية، وليست نسخة من مترجم AIDE أو بديلاً كاملاً عن `javac` وKotlin compiler وAndroid Lint. تستخدم Tree-sitter المرخّص بـMIT لبناء الأشجار، مع فحوص دلالية محدودة ومحافظة. قواعد توافق Android المضمنة **عينة صغيرة محددة** وليست قاعدة تغطي كل API. انظر [النطاق والقيود](docs/ARCHITECTURE.ar.md#النطاق-والقيود) و[نتائج فحص المصادر وDEX](docs/REFERENCE_AUDIT.ar.md).

## ما يعمل الآن

- تحليل شجرة syntax لـJava وKotlin، وأخطاء `ERROR` والعناصر المفقودة، مع إعادة استخدام الشجرة بعد تعديل الملف. يُعاد تحليل المناطق المتأثرة وفق القواعد، وليس مضموناً سطراً واحداً فقط.
- ربط `EditText` أو محرر مخصص، وdebounce على الخيط الرئيسي، وفحص في خيط خلفي، وإلغاء تعاوني، ومنع تسليم نتائج قديمة، وتحرير الموارد عند `close()`.
- فحص لقطات مشروع يلتقط أسماء الأنواع المعلنة، ويعيد تقييم القواعد المعتمدة على الفهرس عند تغيير المشروع. أشجار الملفات غير المتغيرة تُحفظ في LRU محدود.
- اقتراح `import` للأنواع المعروفة أو التي يزوّدها المضيف، وإصلاح بعض الرموز الناقصة. كل إصلاح مرتبط ببصمة النص الأصلي ويرفض النسخة القديمة.
- فحص تعارض بعض القيم الحرفية مع الأنواع البدائية، وعينة موثقة من قواعد Android API/deprecation يمكن للمضيف توسيعها ببيانات يزوّدها. لا يُستنتج النجاح الدلالي من عدم ظهور تحذيرات.
- لقطات workspace وفهرس موارد `R` محدود، وفحص XML، وإصلاحات guarded لملفات متعددة؛ لا يُدّعى غياب مورد إذا كان الفهرس ناقصاً.
- فحوص Java محافظة لتكرار members وunused و`BaseAdapter` وبعض method stubs؛ سياسة `@Override` اختيارية وليست شرطاً لغوياً. المزايا ليست محللاً دلالياً كاملاً أو تكافؤاً مع AIDE.
- ماسح Gradle وتنزيل Maven مقيدان مع SHA-256 موثوق افتراضياً وإلغاء وواجهة تقدم اختيارية؛ لا يجهزان classpath تلقائياً ولا يحلان كل صيغ Gradle/POM.
- طبقة Android اختيارية لرسم التشخيصات وتقديم Toast وقائمة الإصلاحات، إضافة إلى واجهات المحرر المخصص.
- حدود لأحجام الملفات والمشروعات والأحداث والتشخيصات والوقت، ونتيجة `PARTIAL` عند بلوغ حد بدلاً من الإيحاء بأن الملف سليم.

## البناء

يلزم JDK 17 وAndroid SDK Platform 35 وBuild Tools 34.0.0 وNDK 25.1.8937393 وCMake 3.22.1، واتصال Gradle/Google Maven في أول بناء:

```bash
sdkmanager 'platforms;android-35' 'build-tools;34.0.0' 'ndk;25.1.8937393' 'cmake;3.22.1'
./gradlew :analyzer:assembleRelease
./scripts/verify-aar.sh                 # يتحقق من المحتويات والمعماريات
# اختياري: بناء تطبيق يستهلك ملف الـ AAR الناتج
./gradlew :sample:assembleDebug
```

الناتج المتوقع: `analyzer/build/outputs/aar/analyzer-release.aar`. يُهيّئ البناء مكتبة JNI للمعماريات `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` من دون اعتماديات Gradle تشغيلية إضافية. لا يُخزّن AAR مبني مسبقاً في المستودع.

**حالة التحقق:** نجحت 344 فحصاً على Linux باستخدام JNI الحقيقي وبدائل لواجهة Android، وجُمعت مصادر Java وإصلاحات نموذجية ضد stubs API 21. لم يُبنَ AAR أو يُشغّل emulator في هذه البيئة بسبب غياب SDK/NDK وحظر تنزيل Gradle؛ لا تُعد الاختبارات المذكورة إثباتاً لتشغيل Android. التفاصيل في [التحقق المنفذ](docs/ARCHITECTURE.ar.md#التحقق-المنفذ-وحدوده).

## دمج سريع

انسخ AAR إلى `app/libs/` في مشروع التطبيق، ثم:

```kotlin
// app/build.gradle.kts
android { defaultConfig { minSdk = 21 } } // أو أعلى
dependencies { implementation(files("libs/analyzer-release.aar")) }
```

```kotlin
private var analyzerHandle: AnalysisHandle? = null

// على الخيط الرئيسي بعد إنشاء editor
analyzerHandle = CodeAnalyzer.with(this)
    .attachTo(editor)                         // EditText أو EditorAdapter
    .sourceId("Main.kt")
    .language(Language.KOTLIN)
    .config(AnalysisConfig.builder().minApi(21).compileApi(35).build())
    .onResult { result -> renderDiagnostics(result.diagnostics) }
    .onError { error -> showAnalysisError(error) }
    .start()

// في onDestroy، أو onDestroyView إذا كان المحرر في Fragment
analyzerHandle?.close()
analyzerHandle = null
```

يمكن استعمال الصيغة المختصرة `CodeAnalyzer.with(context).attachTo(editor).start()`؛ يجب إضافة `onResult` إذا أراد التطبيق عرض النتائج. شرح الجلب والدمج وكود كل ميزة وحدودها في [دليل الميزات الثماني](docs/ADVANCED_FEATURES.ar.md). الملف الجامع للمزايا والعيوب والهندسة والبناء والدمج هو [تقرير المكتبة بالعربية](docs/SDK_REPORT.ar.md). توضيحات إضافية في [دليل الدمج](docs/INTEGRATION.ar.md)، و[الهيكل الهندسي](docs/ARCHITECTURE.ar.md)، و[تدقيق المصادر وDEX](docs/REFERENCE_AUDIT.ar.md).
