# בית חב״ד ERP – אפליקציית Android

עטיפת WebView נייטיבית (Java, ללא תלות ב-Capacitor) למערכת הניהול.
חבילה: `org.chabad.neveyosef.crm` · גרסה 3.0.0 · minSdk 26 · targetSdk 35

## בנייה
**דרך GitHub (בלי להתקין כלום):** מעלים את הפרויקט ל-repository → לשונית Actions → *Build APK* → מורידים את ה-APK מה-Artifacts.

**מקומית:** Android Studio (Open) או `./gradlew assembleRelease` (נדרשים JDK 17 ו-Android SDK 35).
הקובץ: `app/build/outputs/apk/release/app-release.apk`

## כתובת השרת
ברירת המחדל נקבעת ב-`gradle.properties` (`SERVER_URL`, HTTPS בלבד), או בבנייה: `-PSERVER_URL=https://crm.example.org`.
בתוך האפליקציה: כפתור "הגדרת כתובת שרת מערכת" במסך השגיאה, או `ChabadNativeBridge.openServerSettings()` מהמערכת.

## חתימה
בלי הגדרה נוספת ה-APK נחתם במפתח debug (ניתן להתקנה, אך כל בנייה ב-CI חתומה במפתח שונה ולכן לא תתעדכן מעל קודמתה).
לעדכונים רציפים: ליצור keystore (`keytool -genkeypair -v -keystore release.jks -alias crm -keyalg RSA -keysize 2048 -validity 10000`)
ולהגדיר Secrets ב-GitHub: `KEYSTORE_BASE64` (`base64 -w0 release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. לשמור את המפתח במקום בטוח.

## מה שונה לעומת הגרסה הישנה
- DEX תקין (הישן היה פגום ולא נטען).
- HTTPS בלבד, ללא אמון בתעודות משתמש, שגיאות SSL אינן ניתנות לעקיפה.
- אין טוקן מוטמע בכתובת; הדומיין המוגדר בלבד נטען בתוך האפליקציה, שאר הקישורים נפתחים בדפדפן.
- גשר JavaScript, מיקום והורדות מוגבלים לדומיין המוגדר; בקשת הרשאת מיקום בזמן ריצה.
- הורדות עובדות גם ב-Android 8–9; ללא גישה לקבצים מקומיים; targetSdk 35 עם Edge-to-Edge.
- שיחזור מצב, טיפול בקריסת WebView, אייקון adaptive.

## מגבלות ידועות
הורדות `blob:` נתמכות (נשמרות לתיקיית ההורדות); `data:` ובקשות POST אינן נתמכות.
