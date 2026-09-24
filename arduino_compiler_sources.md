# مصادر التحقق والبناء

- Arduino CLI compile: https://arduino.github.io/arduino-cli/latest/commands/arduino-cli_compile/
- توضح الوثائق أن `arduino-cli compile` يترجم Arduino sketches، وأن `--fqbn` يحدد اللوحة مثل `arduino:avr:uno`، وأن `--build-path` و`--output-dir` يحددان مجلدات البناء والمخرجات.
- Arduino AVR Boards: https://docs.arduino.cc/software/ide-v1/tutorials/getting-started/cores/arduino-avr/
- سلسلة البناء الرسمية تحتاج Arduino AVR Boards core مع AVR-GCC وAVR Libc وملفات Arduino core؛ لذلك لا يكفي تشغيل `avr-gcc` على ملف `.ino` مباشرة، بل يجب تجهيز sketch ومجلد core ومكتبات وتعريفات اللوحة.

## قرار التنفيذ

سيُنفذ التحقق عبر طبقة `CompilerRunner` قابلة للتبديل. على Android، لا يُفترض وجود avr-gcc في النظام، لذلك سيبحث التطبيق عن toolchain bundled داخل مساحة التطبيق أو عن Arduino CLI/avr-gcc المثبت بواسطة المستخدم. إذا لم توجد السلسلة، تعرض الواجهة رسالة إعداد واضحة بدلاً من الادعاء أن التحقق تم. ستعمل العملية في Executor منفصل، وستُمرر ملفات مؤقتة فقط، وتُعرض أخطاء GCC بعد تحويل مساراتها إلى أرقام أسطر داخل المحرر.
