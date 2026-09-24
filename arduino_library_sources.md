# مصادر مكتبات Arduino online

- Arduino Library Registry الرسمي: https://github.com/arduino/library-registry
- يوضح المصدر أن المستودع يحتوي على قائمة المكتبات الموجودة في فهرس Arduino Library Manager.
- يوضح المصدر أن إضافة مكتبة إلى Library Manager تتم عبر إدراج رابط مستودعها في `repositories.txt`، وأن الفهرس تتم إدارته عبر GitHub وتتم إضافة البيانات بعد مراجعة المتطلبات.
- صفحة Arduino IDE لتثبيت المكتبات المشار إليها من المصدر: https://docs.arduino.cc/software/ide-v1/tutorials/installing-libraries#using-the-library-manager
- مواصفة مكتبات Arduino CLI: https://arduino.github.io/arduino-cli/library-specification/

## قرار التنفيذ

سيتم استخدام مصدر فهرس عام online أثناء التشغيل، مع مهلة اتصال قصيرة وقراءة في خيط خلفي، ثم عرض النتائج في واجهة البحث دون تجميد الشاشة. عند فشل الاتصال سيظهر تنبيه واضح وتبقى المكتبات المثبتة محلياً متاحة. لن يتم تنزيل وتشغيل أي ملفات تعليمات أو سكربتات من الإنترنت؛ سيقتصر التنفيذ على قراءة بيانات الفهرس، وتثبيت ملفات المكتبات التي يختارها المستخدم فقط.

## بنية الفهرس المؤكدة

الرابط التشغيلي المؤكد هو `https://downloads.arduino.cc/libraries/library_index.json`. يبدأ JSON بمصفوفة `libraries`. كل عنصر يتضمن حقولاً مثل `name` و`version` و`author` و`sentence` و`paragraph` و`category` و`architectures` و`types` و`repository` و`url` و`archiveFileName` و`size` و`checksum`. رابط `url` يوجه إلى ملف ZIP للمكتبة في خوادم Arduino، ويمكن استخدامه لتثبيت المكتبة التي يختارها المستخدم.

سيتم اختيار أحدث إصدار لكل اسم مكتبة عند تحليل الفهرس، مع بحث محلي في النتائج بعد التنزيل، وطلب اتصال الإنترنت فقط عند الضغط على تحديث أو إضافة مكتبة. سيتم حفظ الفهرس في ذاكرة التخزين المؤقت محلياً لاستخدامه عند انقطاع الاتصال، مع تنفيذ التنزيل في خيط خلفي وإظهار ProgressBar.
