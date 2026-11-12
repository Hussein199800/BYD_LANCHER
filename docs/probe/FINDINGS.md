# نتائج فحص التوافق — BYD Song Pro DM-i (DiLink 5.0)

ثلاث جولات فحص على السيارة الفعلية بتاريخ 2026-11-12. التقارير الخام في هذا المجلد.

## الجهاز

- Android 12 (SDK 32)، معالج Qualcomm SM7325، نسخة `user/release-keys`.
- الشاشة 1920×1080 بكسل، المتاح للتطبيق 1920×984، كثافة 240dpi (1280×720dp تقريباً)، 60Hz.
- بلوتوث (عادي و LE) و USB host و WiFi و GPS موجودة. لا يوجد `android.car` (ليس Android Automotive).

## واجهات السيارة `android.hardware.bydauto`

- 282 صنفاً موجودة في `framework.jar`، و47 واجهة `*Device` تُنشأ بنجاح.
- الدوال المطلوبة موجودة: `getEnginePower`، `getEngineSpeed`، `getGearboxAutoModeType`،
  `getOutCarTemperature`، `getElecPercentageValue`، `getFuelPercentageValue` وغيرها.
- **كلها مرفوضة** لتطبيق عادي برسالة `[getInt] permission deny!` (634 رفضاً).
  `BYDAutoSpeedDevice` و `BYDAutoBodyworkDevice` يفشلان من `getInstance`.

## الصلاحيات

- `BYDAUTO_*_COMMON`: مستوى `normal` وتُمنح تلقائياً، لكنها لا تكفي للقراءة.
- `BYDAUTO_*_GET`: مستوى `signature`، معرّفة من `com.byd.auto.permission`. لا تُمنح لتطبيق غير موقّع بمفتاح BYD.
- من يملكها: 149 حزمة، كلها حزم نظام. لا يوجد أي تطبيق مثبّت من المستخدم يملكها.
- `com.android.shell` (هوية ADB) لا يملك أياً منها (0/10).

## ADB

- معطّل: `adb_enabled=0`، `adbd` متوقف، لا منافذ محلية.
- كود التفعيل من لوحة الاتصال يُظهر رمز QR يتطلب اعتماداً من نظام BYD (تطبيق الوكيل).
- حتى لو فُعّل، هوية shell لا تملك صلاحيات القراءة، فلا يُتوقع أن يفتح هذه الواجهات.

## الخلاصة

- واجهات BYD المباشرة غير متاحة لتطبيق عادي على هذه النسخة.
- المتاح دون صلاحيات BYD: GPS (السرعة والموقع)، مستشعرات التسارع والدوران، حالة الوسائط.
- المصدر العملي لبيانات المحرك والطاقة: دونجل OBD-II بلوتوث (ELM327)، ويحتاج تجربة على السيارة.
