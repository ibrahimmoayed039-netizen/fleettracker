# إعداد Firebase (مرة واحدة)
1. console.firebase.google.com ← Add project.
2. Build ← Realtime Database ← Create database (وضع Locked).
3. Build ← Authentication ← Sign-in method ← فعّل **Email/Password** (وليس Anonymous).
4. Realtime Database ← Rules ← الصق التالي ثم Publish:
```
{
  "rules": {
    ".read": false,
    ".write": false,
    "drivers": {
      ".read": "auth.token.email === 'admin@fleet.local'",
      ".write": "auth.token.email === 'admin@fleet.local'",
      "$uid": {
        ".read": "auth.uid === $uid"
      }
    },
    "data": {
      ".read": "auth.token.email === 'admin@fleet.local'",
      ".write": "auth.token.email === 'admin@fleet.local'",
      "$uid": {
        ".read": "auth.uid === $uid",
        ".write": "auth.uid === $uid && root.child('drivers').child($uid).child('active').val() === true"
      }
    },
    "images": {
      ".read": "auth.token.email === 'admin@fleet.local'",
      ".write": "auth.token.email === 'admin@fleet.local'",
      "$uid": {
        ".read": "auth.uid === $uid",
        ".write": "auth.uid === $uid && root.child('drivers').child($uid).child('active').val() === true"
      }
    },
    "maintenance": {
      ".read": "auth.token.email === 'admin@fleet.local'",
      ".write": "auth.token.email === 'admin@fleet.local'"
    },
    "removed": {
      ".read": "auth.token.email === 'admin@fleet.local'",
      ".write": "auth.token.email === 'admin@fleet.local'",
      "$uid": {
        ".read": "auth.uid === $uid"
      }
    },
    "deletions": {
      ".read": "auth.token.email === 'admin@fleet.local'",
      ".write": "auth.token.email === 'admin@fleet.local'",
      "$uid": {
        ".read": "auth.uid === $uid",
        "$id": {
          ".write": "auth.uid === $uid && !data.exists() && root.child('drivers').child($uid).child('active').val() === true"
        }
      }
    },
    "avatars": {
      ".read": "auth.token.email === 'admin@fleet.local'",
      ".write": "auth.token.email === 'admin@fleet.local'",
      "$uid": {
        ".read": "auth.uid === $uid",
        ".write": "auth.uid === $uid && root.child('drivers').child($uid).child('active').val() === true"
      }
    }
  }
}
```
5. Project settings ← General ← انسخ **Web API Key**. ومن Realtime Database انسخ رابط القاعدة.

# ربط تطبيق أندرويد (google-services.json)
1. Project settings ← General ← Your apps ← **Add app** ← Android.
2. اسم الحزمة (Package name): `com.fleet.tracker` ← Register app.
3. حمّل **google-services.json** (حمّله بعد إنشاء Realtime Database حتى يحتوي رابطها).
4. ضعه في المجلد `android/app/` في المستودع ثم ارفعه إلى GitHub.
5. النتيجة: السائق يدخل **باسم المستخدم وكلمة المرور فقط** (لا حاجة لكود الإعداد).
ملاحظة: اضبط قيود مفتاح API من Google Cloud Console ← APIs & Services ← Credentials عند الحاجة.

# في برنامج الحاسوب
1. الإعدادات ← الصق API Key والرابط ← حفظ.
2. اختر كلمة مرور للمدير ← **إنشاء حساب المدير** (افعلها فوراً أول مرة، قبل أي أحد).
3. إدارة السائقين ← أضف سائقاً (اسم، مستخدم، كلمة مرور، مركبة).
4. أعطِ السائق: **كود الإعداد** (من الإعدادات) + اسم المستخدم + كلمة المرور.
