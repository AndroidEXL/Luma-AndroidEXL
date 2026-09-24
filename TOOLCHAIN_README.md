# Luma Android AVR Toolchain

هذه الحزمة مبنية لتعمل داخل Android، وليست نسخ Linux x86 من أدوات AVR. تحتوي على GCC 7.3.0 مع C/C++، وBinutils 2.30، وAVR-LibC 2.1.0، وArduino AVR core، وvariants للوحات Arduino AVR الرسمية الأساسية.

## المعماريات

| ABI | Android host executable | الاستخدام |
|---|---|---|
| `arm64-v8a` | AArch64 Android، dynamic linker `/system/bin/linker64` | الأجهزة الحديثة 64-bit |
| `armeabi-v7a` | ARMv7 Android، dynamic linker `/system/bin/linker` | الأجهزة 32-bit المتوافقة |

تختار `AvrToolchainInstaller` المعمارية من `Build.SUPPORTED_ABIS`، ثم تنسخ الملفات تدريجياً من assets إلى `files/toolchain`. لا تُقرأ الحزمة كاملة في الذاكرة.

## إصلاح Android 12

الخطأ `error=13, Permission denied` يحدث عند محاولة تشغيل `files/toolchain/bin/avr-g++` مباشرةً. تغيير `chmod` وحده لا يكفي في Android 12. لذلك تُضمّن executables أيضاً داخل `jniLibs/<ABI>/` بأسماء native فريدة مثل `libluma_avr_gpp.so`، وينشئ المثبت روابطاً رمزية من `files/toolchain/bin` و`files/toolchain/libexec` إلى `ApplicationInfo.nativeLibraryDir`. يستخدم التشغيل dynamic linker الخاص بـAndroid، مع الحفاظ على مسارات GCC الداخلية. أعيد بناء executables باستخدام `-fPIE` و`-pie`، وأصبحت ELF من النوع `DYN (Position-Independent Executable)` بدلاً من `EXEC`، لتلبية شرط Android 5+.

## المكونات

تتضمن كل ABI الأدوات `avr-g++` و`avr-gcc` و`avr-cpp` و`avr-as` و`avr-ld` و`avr-ar` و`avr-nm` و`avr-objcopy` و`avr-objdump` و`avr-size` و`avr-readelf` و`avr-strip` و`avr-ranlib`، إضافة إلى `cc1` و`cc1plus` و`collect2` و`lto1` وملفات GCC target وdevice specs وAVR-LibC headers/libraries/linker scripts.

تضم الحزمة Arduino AVR core وvariants `standard` و`mega` و`leonardo` و`micro`. يستخدم Luma حالياً هذه المكونات للتحقق بواسطة `-fsyntax-only`. مرحلة التحقق لا تنفذ الربط النهائي أو إنتاج ملف HEX أو رفعه إلى اللوحة.

## اختبار الموارد

تم اختبار موارد Arduino بواسطة AVR-GCC المضيف على برنامج Blink صحيح، ومرّ البرنامج. كما تم اختبار برنامج به فاصلة منقوطة ناقصة، وأعاد GCC تشخيصاً واضحاً برقم السطر والعمود. أما تشغيل executables Android-native نفسها فيحتاج جهاز Android أو محاكي Android؛ بيئة البناء الحالية لا تحتوي جهازاً متصلاً أو QEMU Android.

## المصادر

بُنيت المكونات من مصادر GCC وBinutils وAVR-LibC الرسمية/المرايا المصدرية المعروفة، ثم رُبطت باستخدام Clang من Android NDK. يشرح [دليل Android NDK للبناء مع أنظمة أخرى](https://developer.android.com/ndk/guides/other_build_systems) صيغ target triples مثل `aarch64-linux-android` و`armv7a-linux-androideabi`. ويشرح [دليل AVR-LibC](https://www.nongnu.org/avr-libc/user-manual/overview.html) أن toolchain يتكون من GCC وBinutils وAVR-LibC ومكونات مترابطة.
