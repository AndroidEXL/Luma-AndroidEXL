# LUMA Arduino

مشروع Android أصلي بلغة **Kotlin** يقدّم تطبيق Luma لمبرمجي Arduino، مع مساحة مشاريع وIDE عمودي لمحرر ملفات INO.

## IDE ومحرر الكود

يعمل IDE بالطول على الهاتف، مع شريط أقسام علوي يتغير لونه عند اختيار File أو Action أو Examples أو Library أو Code. عند اختيار New File ينشئ المحرر كوداً فعلياً متعدد الأسطر بهذا الشكل:

```cpp
// اسم المشروع

void setup() {
}

void loop() {
}
```

يبدأ ترقيم الأسطر من الرقم 1 ويتزامن مع تمرير محرر الكود. تظهر نافذة الإكمال فقط بعد بدء كتابة كلمة قابلة للإكمال، وليس مع كل ضغطة، وتختفي فور اختيار أي عنصر. تضم النافذة اقتراحات الكلمات الأساسية، المتغيرات، الدوال الخاصة بالمشروع، والمكتبات المضافة.

## اللوحات والمنافذ

تضم قائمة Select Arduino Board اللوحات الرسمية والشائعة والصينية، مثل Uno وNano وMega وLeonardo وPro Mini وElegoo وKeyestudio وWAVGAT وESP8266 وESP32. أما Select Port For USB فتعرض نافذة تحتوي على زر **Refresh USB** لإعادة قراءة كل أجهزة USB المتصلة. عند اختيار جهاز بدون صلاحية، يطلب التطبيق إذن USB الرسمي من Android، ولا يحتفظ بالمستقبل بعد إغلاق IDE.

## الأمثلة

تم تصحيح الأمثلة لتظهر كأسطر فعلية داخل المحرر، ومنها مثال LiquidCrystal:

```cpp
#include <LiquidCrystal.h>
LiquidCrystal lcd(12, 11, 5, 4, 3, 2);

void setup() {
  lcd.begin(16, 2);
  lcd.print("Hello Arduino");
}

void loop() {}
```

## إدارة المكتبات online

توجد صفحة **Library Manager** مستقلة تحتوي على بحث في فهرس مكتبات Arduino الرسمي online، بدلاً من الاعتماد على قائمة ثابتة داخل التطبيق. يحاول التطبيق قراءة أحدث إصدار لكل مكتبة من ملف `library_index.json` الرسمي، ويدعم gzip والتحويلات وHTTP status. إذا أعاد خادم التنزيل 403 أو تعذر الوصول إليه، يستخدم cache محلياً، ثم fallback من `arduino/library-registry` الرسمي عبر `repositories.txt`، ويحوّل روابط GitHub إلى أرشيفات main/master قابلة للتنزيل. وإذا تعذر كل ذلك يحتفظ بالكتالوج المحلي الاحتياطي ويعرض سبب الخطأ في الواجهة.

عند اختيار مكتبة للتثبيت، ينزّل التطبيق ملف ZIP إلى مساحة تخزين التطبيق، ثم يفكّه بأمان إلى مجلد المكتبة داخل `files/arduino-libraries`. بعد اكتمال التنزيل والاستخراج يضيف المكتبة تلقائياً إلى `Project.libraries` ويحفظ المشروع عبر `ProjectStore`، بما في ذلك التحديد المتعدد أو المكتبة المثبتة مسبقاً. يظهر اسم المكتبة باللون الأزرق عند إضافتها للمشروع، وباللون الأسود أو الأبيض المناسب للوضع الليلي عند عدم إضافتها. تظهر عملية التنزيل داخل شريط تحميل وتعمل في خيط خلفي مع حدود للحجم وعدد الملفات، لذلك لا تُنفّذ حلقة ثقيلة ولا تُجمّد واجهة التطبيق أو نظام الهاتف.

يستند المصدر إلى [Arduino Library Registry][1] وفهرس التحميل الرسمي [library_index.json][2]، بينما تتوافق بنية الحقول مع مواصفة [Arduino CLI Library Specification][3].

## الإكمال والواجهة

تمت إضافة أكثر من خمسين اقتراحاً من دوال Arduino الرسمية، بما في ذلك الدوال الرقمية والتناظرية والتأخيرات والصوت والمقاطعات والرياضيات وSerial والبتات. نافذة الإكمال أصبحت أصغر، متناسبة مع عرض الهاتف، وتعرض أربعة صفوف كحد أقصى في كل مرة حتى لا تغطي المحرر أو تعلق الواجهة. وتظهر فقط عند كتابة كلمة مناسبة، وتختفي بعد اختيار الاقتراح.

| الجزء | الملف |
|---|---|
| IDE | `app/src/main/java/com/example/animatedsplash/IdeActivity.kt` |
| محرر الكود | `app/src/main/java/com/example/animatedsplash/CodeEditorView.kt` |
| المكتبات | `app/src/main/java/com/example/animatedsplash/LibraryActivity.kt` |
| كتالوج المكتبات | `app/src/main/java/com/example/animatedsplash/LibraryCatalog.kt` |
| تخزين المكتبات | `app/src/main/java/com/example/animatedsplash/LibraryStore.kt` |
| أرقام الأسطر | `app/src/main/java/com/example/animatedsplash/EditorLineNumberView.kt` |
| USB | `app/src/main/AndroidManifest.xml` و`IdeActivity.kt` |

## إصلاح الذاكرة وواجهة IDE

تم إصلاح انهيار `OutOfMemoryError` الذي كان يحدث عند تحويل فهرس Arduino الكبير إلى نص JSON إضافي داخل الذاكرة. أصبحت الخدمة تقرأ الفهرس باستخدام `JsonReader` بشكل تدريجي، وتكتب الملف إلى cache على دفعات محدودة، وتستخدم مهلات اتصال وحجماً أقصى للفهرس. كما أزيلت حالات `Ready` المتكررة من الرأس ومنطقة المحرر لتقليل ازدحام الواجهة.

## مكتبة avr-android

أضيفت وحدة Android Library مستقلة باسم `avr-android`. تصدّر الوحدة API Kotlin من خلال `AvrCompiler` و`AvrCompileRequest` و`AvrCompileResult`، وتحتوي على طبقة JNI/C++ مبنية عبر CMake باسم `libavr_android.so`. تستخدم الطبقة الأصلية فحصاً بنيوياً سريعاً، بينما يتولى `AvrCompiler` تشغيل AVR-G++ الفعلي من toolchain Android المضمّنة.

مثال الاستخدام داخل أي Activity:

```kotlin
val compiler = AvrCompiler(this)
compiler.verify(
    AvrCompileRequest(
        projectName = "Blink",
        code = sourceCode,
        board = AvrBoard.UNO
    )
) { result ->
    // انقل النتيجة إلى Main Thread ثم اعرض Success أو Failure.
}
```

ينتج Gradle ملف `avr-android-debug.aar`، كما يضم APK المكتبة native binaries لـ`arm64-v8a` و`armeabi-v7a`. يتبع الدمج بنية Android NDK الرسمية عبر CMake وJNI [4] [5].

## حزمة AVR-GCC داخل Android

بُنيت toolchain فعلية لتعمل على Android من داخل التطبيق، وليست نسخاً من executables Linux. تشمل الحزمة GCC 7.3.0 مع C وC++، وBinutils 2.30، وAVR-LibC 2.1.0، وArduino AVR core، وvariants للوحات `standard` و`mega` و`leonardo` و`micro`. تم إنتاج نسخة Android-native لكل من `arm64-v8a` و`armeabi-v7a` باستخدام Clang من Android NDK، وتحتوي كل نسخة على `avr-g++` و`avr-gcc` و`avr-cpp` و`avr-as` و`avr-ld` و`avr-objcopy` وبقية أدوات AVR الأساسية.

توجد الملفات داخل `avr-android/src/main/assets/toolchain`. عند أول تنفيذ لخيار **Action → Check Code**، يستخرج `AvrToolchainInstaller` الملفات تدريجياً إلى `files/toolchain`، ثم ينشئ روابطاً رمزية من أسماء GCC داخل `files/toolchain` إلى executables native الموجودة في `ApplicationInfo.nativeLibraryDir`. هذا المسار مهم في Android 12، لأن Android يمنع تنفيذ ELF مباشرةً من `filesDir` ويعيد `Permission denied`. أعيد بناء كل executables باستخدام `-fPIE` و`-pie` لتصبح ELF من النوع `DYN (Position-Independent Executable)`، وهو شرط Android 5+. لا تُحمّل الحزمة كاملة إلى الذاكرة، ويُحفظ marker بإصدار PIE v3 للتحقق من اكتمال التثبيت. يبلغ حجم APK النهائي نحو 136 MB بسبب تضمين ABIين ومكونات GCC التنفيذية داخل `jniLibs`.

## تبويبات الملفات وExamples

أصبح محرر IDE يستخدم تبويبات أفقية قابلة للتمرير للملفات المفتوحة بدلاً من تبويبي Code وLibrary السابقين. يأخذ كل تبويب عرضه من اسم الملف الفعلي، وتظهر التبويبات غير المحددة داخل إطار واضح بينما يظل التبويب النشط مميزاً بلون الواجهة. يبدأ المشروع بملف `.ino` الرئيسي، بينما يؤدي **File → New File** و**File → Import Code** واختيار أي Example إلى فتح ملف مستقل في تبويب جديد. لكل تبويب كود وملف backing مستقل، ويظهر اسم ملف `.ino` مع زر إغلاق صغير `×`، ولا يسمح التطبيق بإغلاق آخر تبويب مفتوح.

تقرأ قائمة **Examples** مجلدات `Examples` من المكتبات المثبتة داخل `files/arduino-libraries`، وتعرض اسم المكتبة كفرع ثم أسماء ملفات الأمثلة تحته. عند اختيار مثال، يُحفظ كملف مستقل داخل المشروع ويُفتح في تبويب جديد، مع بقاء الأمثلة الأساسية المدمجة مثل Blink وLiquidCrystal وSerial.

أزيل شريط الأدوات العلوي بالكامل، بما فيه Save وDelete وCopy وUndo وRedo، لتبقى أقسام **File** و**Action** و**Examples** و**Library** و**Code** فقط فوق المحرر. يتم الوصول إلى أدوات التحرير من قسم **Code** وإدارة المكتبات من قسم **Library**. يحتوي قسم **Action** على **Check Code** و**Details**؛ ويعرض Details الملف الحالي والكود الكامل ومعلومات المشروع واللوحة وMCU وvariant وABI ومسارات toolchain والمكتبات وGCC_EXEC_PREFIX وCOMPILER_PATH وLIBRARY_PATH والأمر الكامل ومعاملاته والمدة والحالة ومخرجات AVR-GCC والتحذيرات والأخطاء والتشخيصات، مع إمكانية نسخ التقرير بالكامل.

## Serial Monitor

أضيفت صفحة **Serial Monitor** مستقلة من قائمة **Action**. تحتوي الصفحة على اختيار Baud Rate بالقيم الشائعة من 300 إلى 115200، وحالتي بدء وإيقاف المراقبة، وزر مسح، ووضع عرض نصي، ووضع رسم بياني بشبكة ومحور قيم. يمكن التبديل بين النص والرسم دون فقد حالة الصفحة، وتم تجهيز `SerialChartView` لاستقبال نقاط البيانات عند ربط طبقة USB serial الفعلية.

## تحقق AVR-GCC

يرتبط خيار **Action → Check Code** الآن بـ`AvrCompiler` من وحدة `avr-android`. يجهز التطبيق نسخة مؤقتة من كود المشروع، يضيف `Arduino.h` وArduino AVR core وvariant الخاص باللوحة، ثم يشغل `avr-g++` Android-native مع `-Os` و`-fsyntax-only` ويمرر `-mmcu` و`F_CPU` و`-Wall` و`-Wextra` ومسارات AVR-LibC وGCC target. يمرر التطبيق ملف `specs-${mcu}` بمسار مطلق، ويضبط `GCC_EXEC_PREFIX` و`COMPILER_PATH` و`LIBRARY_PATH` حتى لا يبحث GCC عن `device-specs` في مسار نسبي مفقود. تُقرأ المخرجات في خيط منفصل، وتحوّل أخطاء GCC إلى رسائل تتضمن رقم السطر والعمود داخل نافذة النتيجة. يحتوي Dialog النتيجة على زر **نسخ النص** لنسخ التشخيص الكامل إلى حافظة Android.

لا يكفي نسخ نسخة Linux x86 من `avr-g++` إلى الهاتف؛ يجب أن تكون executable متوافقة مع Android ومعمارية الجهاز. النسخة الحالية تحتوي هذه executables داخل `jniLibs` بأسماء native فريدة مثل `libluma_avr_gpp.so`، وتختار ABI المناسب تلقائياً. يدعم التحقق الحالي المكتبات المضافة للمشروع والمذكورة في `#include`: يضيف AvrCompiler مجلدات المكتبة recursively إلى include paths، ويضمّن ملفات `.c` و`.cc` و`.cpp` الخاصة بالمكتبات، مع استبعاد مجلدات الأمثلة والاختبارات وحدود لعدد المجلدات والمصادر. لا تنفذ هذه المرحلة الربط النهائي أو إنتاج HEX أو رفع البرنامج إلى اللوحة بعد.

تم اختبار البناء وكانت النتيجة **BUILD SUCCESSFUL**.

## المراجع

[1]: https://github.com/arduino/library-registry "Arduino Library Registry"
[2]: https://downloads.arduino.cc/libraries/library_index.json "Arduino official library index"
[3]: https://arduino.github.io/arduino-cli/library-specification/ "Arduino CLI Library Specification"
[4]: https://developer.android.com/ndk/guides "Android NDK guide"
[5]: https://developer.android.com/ndk/guides/abis "Android ABI guide"

## Keywords

Luma Android app
Luma APK
Luma Android
Android application
Kotlin Android app
Android APK
Arduino For Android
Arduino IDE
Arduino Apk
Kotiln Arduino App
Luma GitHub
LUMA Arduino
