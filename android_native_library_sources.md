# مصادر Android Native Library

- Android NDK guide: https://developer.android.com/ndk/guides
- Android ABI guide: https://developer.android.com/ndk/guides/abis

توضح وثائق Android الرسمية أن NDK يتيح استخدام C/C++ مع Android، وأن CMake هو الخيار الموصى به لإنشاء مكتبة native جديدة، ثم يربط Gradle ملف CMake ويضع ملف `.so` داخل APK، وتستدعى وظائف المكتبة عبر JNI. كما توضح وثائق ABI أن المكتبات الأصلية يجب بناؤها لكل ABI مستهدف، مثل `armeabi-v7a` و`arm64-v8a` و`x86` و`x86_64`.

## قرار المكتبة

سيتم إنشاء وحدة `avr-android` داخل المشروع مع API Kotlin مستقلة، وطبقة JNI/C++ صغيرة للتعامل مع تشغيل محرك التحقق أو واجهة مستقبلية للمترجم. سيبقى AVR-GCC نفسه منفصلاً عن طبقة JNI لأن avr-gcc ليس مكتبة Android عادية؛ يلزم بناء أدواته واعتمادياته لكل ABI Android قبل تضمينها. ستُعاد نتيجة موحدة إلى Kotlin تشمل النجاح والنص الكامل والتشخيصات.
