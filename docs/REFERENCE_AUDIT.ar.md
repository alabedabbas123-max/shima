# سجل فحص مصادر AIDE وDEX

## المدخلات وما فُحص

- [abeed.zip](https://github.com/alabedabbas123-max/Abbas/blob/main/abeed.zip)، حجم 46,654,497 بايت، SHA-256: `d1dc7b64d1215d8528d3d769f94ab8fb3fd895b16a824a2aa96ee50cbf211430`. يحتوي `abeed/app/src/main/java.zip` (2,998,830 بايت، SHA-256 `26003c424575cc903f7d208347511f4931b82fef54ec52e6c45e8bf99c4c8d05`) وأرشيفات assets وJNI وresources.
- [`dex.zip`](https://github.com/alabedabbas123-max/shima/blob/main/dex.zip) المرجعي (غير المرفق بأرشيف SDK)، حجم 8,638,284 بايت، SHA-256: `10471081defe74eb326db0f66ee1b7515344309b8c63649546408fd464ed0bae`. استُخرجت ملفات DEX الثلاثة وفُحصت الجداول والتواقيع بواسطة Androguard؛ هذه الأرقام لا تعني أن كل صنف تابع للمحرك:

| الملف | الأصناف | تواقيع methods في جدول DEX | SHA-256 |
|---|---:|---:|---|
| `classes.dex` | 9,043 | 64,000 | `9520223cf3ee6474be2aabdcb8947f46b10e141e24d4040ac65de3106dee2409` |
| `classes2.dex` | 6,922 | 64,005 | `a3497149fb357e1e66f76dabaa956f0f5d0a44b5e6ccda705df9b7e133ee20c3` |
| `classes3.dex` | 3,516 | 33,732 | `209a5b8704394d2f0a1b0cf3dcee034006ecb17f8d73c6677c7e610bc49f4f23` |

## الملاحظات المثبتة

1. في `java/com/aide/engine/service/CodeAnalysisEngineService.java` توجد خدمة Android مبنية على Binder ودورة حياة، وتنشئ `C0048c`. هذه نقطة فصل خدمة/محرر، وليست واجهة AAR مستقلة.
2. `EngineSolution` و`EngineSolutionProject` يحملان نماذج مشروع، و`SyntaxError` يحمل مواقع ورسائل وبيانات أخطاء عبر `Parcelable`، و`Modification` يمثل تعديلات. أخذ التصميم الجديد الفكرة العامة لتشخيصات وإصلاحات مستقلة عن العرض، ولم ينسخ تطبيقها.
3. `service/CharArray.java` يحمل مخزن `char[]` وطولاً ويدعم إعادة استخدامه عند قراءة Parcel. هذا دليل على إدارة نصوص صريحة في المرجع؛ التنفيذ الجديد يستخدم لقطات UTF-16 وحدوداً للملف ولا يدّعي أنه نفس الخوارزمية.
4. يعتمد `com/aide/engine/C0048c.java` بكثافة على أصناف `abcd.*` المموّهة. الأرشيف المفكوك `java.zip` يحتوي ملفين فقط في `java/abcd/`، بينما يحتوي `classes.dex` على 2,784 صنفاً تبدأ أسماؤها بـ`abcd/`؛ لا يتوافر من أرشيف Java وحده مصدر مكتمل يمكن عزله وتجميعه كمحرك. كما تظهر نحو 122 صنفاً تحت `com/aide/engine/` في `classes.dex`.
5. يحتوي `classes3.dex` على الصنف `com/sun/tools/javac/parser/JavacParser` وتعريف 123 method فيه. فُحصت تعليمات DEX لعدة دوال، لا أسماؤها فقط:
   - `accept(Token)` يقارن الرمز الحالي بالمطلوب؛ يتقدم إلى الرمز التالي عند التطابق، وإلا يبلغ عن `expected` مستعملاً موضع نهاية الرمز السابق.
   - `reportSyntaxError(DiagnosticPosition, String, Object[])` يفحص موضع الخطأ المسجل و`EOF`، ويبلغ عن `premature.eof` في المسار الخاص به، وقد يستدعي `nextToken()` عند تكرار موضع الخطأ.
   - `syntaxError(...)` ينشئ عقدة خطأ `JCErroneous` عبر `TreeMaker` ويبلّغ عنها ويحفظ موضع النهاية؛ ودالة `block` تقبل الأقواس وتستدعي محلل العبارات.

   هذه ملاحظات على مسارات bytecode محددة، **وليست** إثباتاً بأن هذا الصنف هو محرك التحرير التزايدي أو أن AIDE يستدعيه في كل جلسة؛ لم نثبت سلسلة الاستدعاءات ولا ننقل الصنف أو نخترع تكافؤاً سلوكياً. تطبيق المكتبة يستخدم أحداث أخطاء Tree-sitter بدلاً منه. لم نعثر على parser يحمل اسم Kotlin صراحةً في أسماء DEX؛ التمويه يمنع اعتبار ذلك دليلاً قاطعاً على عدم وجود أي دعم Kotlin.
6. `com/aide/engine/service/Native` في المصادر الظاهرة وDEX ليس دليلاً كافياً على وجود parser أصلي قابل للاقتطاع: دالة `init` الظاهرة فارغة، وأرشيف JNI يحتوي مثلاً أدوات `libaapt2` و`libzipalign` و`liblink`. لا نعرف من هذا الفحص الجزئي كل مسارات التنفيذ أو أداء المحرك.
7. يحتوي `kay_id.zip` في المستودع على `kay/assets/data/api-versions.xml`؛ قُورنت به القيم القليلة في `ApiCatalog`. البيانات الظاهرة قديمة/غير كاملة للأحدث (أكبر قيمة `since` موجودة فيه 31)، لذلك لم تُستخدم كادعاء تغطية لكل إصدارات Android، ولم يُضمّن XML في المكتبة.

## إعادة فحص مثال من bytecode

يمكن إعادة فحص `accept` باستخدام Python و`androguard==4.1.4`، من جذر المستودع؛ هذه أداة تدقيق اختيارية لا تدخل في AAR:

```bash
python3 -m pip install 'androguard==4.1.4'
python3 - <<'PY'
from zipfile import ZipFile
from androguard.core.dex import DEX
from loguru import logger
logger.remove()
with ZipFile('dex.zip') as archive:
    dex = DEX(archive.read('dex/classes3.dex'))
parser = dex.get_class('Lcom/sun/tools/javac/parser/JavacParser;')
for method in parser.get_methods():
    if method.get_name() == 'accept' and method.get_code():
        print(method.get_name(), method.get_descriptor())
        for instruction in method.get_instructions():
            print(instruction.get_name(), instruction.get_output())
PY
```

## الاستنتاج

المراجع تطبيق مفكك ومموّه مع اعتماديات وطبقات تطبيق، ولم يظهر فيها ترخيص يمنح إعادة توزيع محرك AIDE. لا تكفي أسماء الأصناف أو DEX لإثبات الدقة، الأداء، التكافؤ السلوكي، أو ترخيص النسخ. لذلك المكتبة الجديدة مستقلة وتستخدم قواعد Tree-sitter مرخصة بوضوح، وتستلهم فقط أفكار الفصل والتشخيص وتعديلات النص والمشروع. لم ننسخ محرك AIDE، ولم نثبت أنه يدعم Kotlin أو أنه يطابق التنفيذ الجديد. نقل المنطق الأصلي فعلياً يتطلب مصادر مكتملة وصلاحية استخدام/توزيع، ثم فحص سلوك واختبارات مقارنة مفصلة.
